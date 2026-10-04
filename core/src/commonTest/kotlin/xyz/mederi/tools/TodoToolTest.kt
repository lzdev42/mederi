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
import xyz.mederi.domain.model.decodeTodos
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemorySessionStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * update_todo 工具直测（不依赖模型行为）。
 *
 * 真理源断言：
 * - 合法调用必须落库（sessions.todos 列）且事件 payload 可经 decodeTodos 无损还原；
 * - 校验拒绝（多 in_progress / FAILED / 空白 content）绝不落库。
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

    
}
