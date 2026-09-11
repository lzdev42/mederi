package xyz.mederi.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.TodoItem
import xyz.mederi.domain.model.TodoStatus
import xyz.mederi.domain.model.WorkType
import xyz.mederi.domain.model.decodeTodos
import xyz.mederi.domain.model.encodeTodos
import xyz.mederi.plan.Plan
import xyz.mederi.plan.PlanStatus
import xyz.mederi.plan.PlanStore
import xyz.mederi.plan.Subtask
import xyz.mederi.plan.SubtaskStatus
import xyz.mederi.plan.toTodoProjection
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemorySessionStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * update_todo 工具 + Plan 子任务投影直测（不依赖模型行为）。
 *
 * 真理源断言：
 * - 合法调用必须落库（sessions.todos 列）且事件 payload 可经 decodeTodos 无损还原；
 * - 校验拒绝（多 in_progress / FAILED / 空白 content）绝不落库；
 * - Plan 投影是 PlanStore 状态的纯函数，FAILED 忠实映射。
 */
class TodoToolTest {

    private val sessionStore = InMemorySessionStore()
    private val eventBus = MutableSharedFlow<MederiEvent>(replay = 64)
    private val received = mutableListOf<MederiEvent>()

    private fun makeSession() = runBlocking {
        sessionStore.insert(
            xyz.mederi.domain.model.Session(
                id = "sess_todo_test",
                projectId = "proj_1",
                title = "t",
                status = xyz.mederi.domain.model.SessionStatus.IDLE,
                agentMode = AgentMode.AUTONOMOUS,
                workType = WorkType.CODE,
                aiModel = null,
                reasoningLevel = null,
                createdAt = "2026-09-11T00:00:00Z",
                updatedAt = "2026-09-11T00:00:00Z"
            )
        )
    }

    private fun makeAgentTools() = AgentTools(
        sessionId = "sess_todo_test",
        historyStore = InMemoryHistoryStore(),
        sessionStore = sessionStore,
        eventBus = eventBus,
        modelContextWindow = null,
        newContextWindowFlag = AtomicBoolean(false)
    )

    // 事件收集用独立 scope：collect 永不完成，绝不能放进会 await 子协程的结构化 scope（挂死根因）
    private val collectorScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)

    private fun collectEvents(): Job = collectorScope.launch {
        eventBus.collect { received.add(it) }
    }

    private fun todoArg(content: String, status: TodoStatus) =
        AgentTools.TodoItemArg(content = content, status = status)

    @Test
    fun `valid call persists and event payload round-trips`() = runBlocking {
        makeSession()
        val collector = collectEvents()
        val items = listOf(
            todoArg("step one", TodoStatus.COMPLETED),
            todoArg("step two", TodoStatus.IN_PROGRESS),
            todoArg("step three", TodoStatus.PENDING)
        )
        val result = makeAgentTools().UpdateTodoTool().execute(AgentTools.UpdateTodoArgs(todos = items))
        yield()

        assertTrue(result.startsWith("Todo list updated (3 items, 1 completed)."), "实际返回: $result")
        val persisted = sessionStore.get("sess_todo_test")!!.todos
        assertEquals(
            listOf(
                TodoItem("step one", TodoStatus.COMPLETED),
                TodoItem("step two", TodoStatus.IN_PROGRESS),
                TodoItem("step three", TodoStatus.PENDING)
            ),
            persisted,
            "落库即真理源：content/status 必须无损"
        )
        val event = received.last { it.type == xyz.mederi.domain.model.EventType.TODO_UPDATED }
        assertEquals(persisted, decodeTodos(event.payload["todos"]!!), "事件 payload 必须可经 decodeTodos 还原为落库状态")
        collector.cancel()
    }

    @Test
    fun `multiple in_progress rejected and nothing persisted`() = runBlocking {
        makeSession()
        val result = makeAgentTools().UpdateTodoTool().execute(
            AgentTools.UpdateTodoArgs(
                todos = listOf(
                    todoArg("a", TodoStatus.IN_PROGRESS),
                    todoArg("b", TodoStatus.IN_PROGRESS)
                )
            )
        )
        assertTrue(result.startsWith("Error:"), "实际返回: $result")
        assertTrue(result.contains("At most ONE todo may be in_progress"))
        assertEquals(emptyList(), sessionStore.get("sess_todo_test")!!.todos, "校验拒绝绝不落库")
    }

    @Test
    fun `failed status rejected for model todos`() = runBlocking {
        makeSession()
        val result = makeAgentTools().UpdateTodoTool().execute(
            AgentTools.UpdateTodoArgs(todos = listOf(todoArg("a", TodoStatus.FAILED)))
        )
        assertTrue(result.startsWith("Error:"), "实际返回: $result")
        assertTrue(result.contains("reserved for plan verification"))
        assertEquals(emptyList(), sessionStore.get("sess_todo_test")!!.todos)
    }

    @Test
    fun `blank content rejected`() = runBlocking {
        makeSession()
        val result = makeAgentTools().UpdateTodoTool().execute(
            AgentTools.UpdateTodoArgs(todos = listOf(todoArg("  ", TodoStatus.PENDING)))
        )
        assertTrue(result.startsWith("Error:"), "实际返回: $result")
    }

    @Test
    fun `empty list clears persisted todos`() = runBlocking {
        makeSession()
        val tools = makeAgentTools()
        tools.UpdateTodoTool().execute(
            AgentTools.UpdateTodoArgs(todos = listOf(todoArg("a", TodoStatus.PENDING)))
        )
        val result = tools.UpdateTodoTool().execute(AgentTools.UpdateTodoArgs(todos = emptyList()))
        assertEquals("Todo list cleared.", result)
        assertEquals(emptyList(), sessionStore.get("sess_todo_test")!!.todos)
    }

    @Test
    fun `plan execution hard-blocks update_todo`() = runBlocking {
        // Plan 执行期（APPROVED/IN_PROGRESS）：update_todo 必须被代码级门禁拒绝且不落库，
        // 确保 todo 面板在 Plan 期只显示 Plan 子任务投影
        val tmpDir = java.io.File.createTempFile("mederi-todo-gate", "").apply {
            delete(); mkdirs()
            java.io.File(this, ".mederi/plans").mkdirs()
        }
        val planStore = PlanStore(listOf(tmpDir.absolutePath))
        val plan = Plan(
            id = "plan_gate",
            title = "t",
            overview = "o",
            sessionId = "sess_todo_test",
            subtasks = listOf(
                Subtask(index = 0, name = "only", planDetail = "d", verification = "v", status = SubtaskStatus.IN_PROGRESS)
            ),
            createdAt = "2026-09-11T00:00:00Z",
            status = PlanStatus.APPROVED,
            agentMode = AgentMode.AUTONOMOUS,
            workType = WorkType.CODE
        )
        planStore.save(plan)
        makeSession()
        val tools = AgentTools(
            sessionId = "sess_todo_test",
            historyStore = InMemoryHistoryStore(),
            sessionStore = sessionStore,
            eventBus = eventBus,
            modelContextWindow = null,
            newContextWindowFlag = AtomicBoolean(false),
            questionRequester = null,
            planStore = planStore
        )
        val result = tools.UpdateTodoTool().execute(
            AgentTools.UpdateTodoArgs(todos = listOf(todoArg("rogue todo", TodoStatus.PENDING)))
        )
        assertTrue(result.startsWith("Error:") && result.contains("Active Plan"), "实际返回: $result")
        assertEquals(emptyList(), sessionStore.get("sess_todo_test")!!.todos, "门禁拒绝绝不落库")

        // PENDING_APPROVAL（含被拒计划）不算执行期：小改动仍可轻量 todo
        planStore.update(plan.copy(status = PlanStatus.PENDING_APPROVAL))
        val ok = tools.UpdateTodoTool().execute(
            AgentTools.UpdateTodoArgs(todos = listOf(todoArg("small fix step", TodoStatus.IN_PROGRESS)))
        )
        assertTrue(ok.startsWith("Todo list updated"), "实际返回: $ok")
    }

    @Test
    fun `plan projection maps all subtask statuses`() {
        val plan = Plan(
            id = "plan_proj",
            title = "t",
            overview = "o",
            sessionId = "s",
            subtasks = listOf(
                Subtask(index = 0, name = "first", planDetail = "d", verification = "v", status = SubtaskStatus.COMPLETED),
                Subtask(index = 1, name = "second", planDetail = "d", verification = "v", status = SubtaskStatus.IN_PROGRESS),
                Subtask(index = 2, name = "third", planDetail = "d", verification = "v", status = SubtaskStatus.FAILED),
                Subtask(index = 3, name = "fourth", planDetail = "d", verification = "v", status = SubtaskStatus.PENDING)
            ),
            createdAt = "2026-09-11T00:00:00Z",
            status = PlanStatus.IN_PROGRESS,
            agentMode = AgentMode.AUTONOMOUS,
            workType = WorkType.CODE
        )
        val projection = plan.toTodoProjection()
        assertEquals(
            listOf(
                TodoItem("Subtask 1: first", TodoStatus.COMPLETED),
                TodoItem("Subtask 2: second", TodoStatus.IN_PROGRESS),
                TodoItem("Subtask 3: third", TodoStatus.FAILED),
                TodoItem("Subtask 4: fourth", TodoStatus.PENDING)
            ),
            projection,
            "FAILED 必须忠实映射（UI 需要如实显示失败的子任务）"
        )
    }

    @Test
    fun `projection survives encode-decode round trip`() = runBlocking {
        val plan = Plan(
            id = "plan_rt",
            title = "t",
            overview = "o",
            sessionId = "s",
            subtasks = listOf(
                Subtask(index = 0, name = "only", planDetail = "d", verification = "v", status = SubtaskStatus.IN_PROGRESS)
            ),
            createdAt = "2026-09-11T00:00:00Z",
            status = PlanStatus.IN_PROGRESS,
            agentMode = AgentMode.AUTONOMOUS,
            workType = WorkType.CODE
        )
        val encoded = plan.toTodoProjection().encodeTodos()
        assertEquals(plan.toTodoProjection(), decodeTodos(encoded), "encode/decode 必须共用同一 Json 配置且对称")
        assertNull(decodeTodos("not-json"), "畸形数据安全降级为 null（调用方丢事件保快照）")
    }
}
