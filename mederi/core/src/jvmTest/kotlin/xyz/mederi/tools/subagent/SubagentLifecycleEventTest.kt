package xyz.mederi.tools.subagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.domain.model.WorkType
import xyz.mederi.provider.domain.model.ReasoningLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SubagentManager 生命周期事件（P3）行为锁定：
 * - spawn → SUBAGENT_STARTED，payload 带全量元数据（task/modelName/reasoningLevel/role/briefing）
 * - 正常完成 → SUBAGENT_COMPLETED；runner 抛错 → SUBAGENT_ERROR；stop → SUBAGENT_STOPPED
 * - stopAllForSession 收割也发 SUBAGENT_STOPPED（NonCancellable：取消路径不吞事件）
 * - status() JSON 携带模型元数据
 */
class SubagentLifecycleEventTest {

    private class FakeRunner : SubagentRunner {
        var mode = Mode.COMPLETE
        enum class Mode { COMPLETE, HANG, THROW }
        override suspend fun run(
            task: String, briefing: String?, plan: String?, role: SubagentRole,
            workType: WorkType, directories: List<String>, aiModel: AIModel,
            reasoningLevel: ReasoningLevel, projectId: String, parentSessionId: String,
            apiKeyId: String?, executorPlanId: String?, executorSubtaskIndex: Int?,
            planStore: xyz.mederi.plan.PlanStore?
        ): String = when (mode) {
            Mode.COMPLETE -> "done: $task"
            Mode.HANG -> { kotlinx.coroutines.delay(Long.MAX_VALUE); "" }
            Mode.THROW -> throw IllegalStateException("boom")
        }
    }

    private fun spawn(manager: SubagentManager, task: String = "do it", briefing: String? = null): String =
        manager.spawn(
            task = task, briefing = briefing, plan = null, role = SubagentRole.EXECUTOR,
            workType = WorkType.CODE, directories = emptyList(),
            aiModel = AIModel(id = "m1", providerModelId = "m1", name = "ModelB"),
            reasoningLevel = ReasoningLevel.HIGH,
            projectId = "p1", parentSessionId = "sess_parent"
        )

    private fun newManager(runner: FakeRunner, bus: MutableSharedFlow<MederiEvent>, scope: CoroutineScope) =
        SubagentManager(runner, scope, bus)

    @Test
    fun `started event carries full metadata`() = runBlocking {
        val runner = FakeRunner()
        val bus = MutableSharedFlow<MederiEvent>(replay = 16)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val manager = newManager(runner, bus, scope)
            spawn(manager, task = "implement feature X", briefing = "watch out for Y")

            val started = withTimeout(5_000) { bus.first { it.type == EventType.SUBAGENT_STARTED } }
            assertEquals("sess_parent", started.sessionId, "事件 sessionId 应为父会话")
            assertEquals("implement feature X", started.payload["task"])
            assertEquals("watch out for Y", started.payload["briefing"])
            assertEquals("ModelB", started.payload["modelName"])
            assertEquals("m1", started.payload["modelId"])
            assertEquals("HIGH", started.payload["reasoningLevel"])
            assertEquals("EXECUTOR", started.payload["role"])
            assertTrue(started.payload.containsKey("agentId"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `terminal events match outcome`() = runBlocking {
        val runner = FakeRunner()
        val bus = MutableSharedFlow<MederiEvent>(replay = 64)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val manager = newManager(runner, bus, scope)

            // 1) 正常完成 → COMPLETED
            val ok = spawn(manager, task = "ok task")
            withTimeout(5_000) {
                assertEquals(EventType.SUBAGENT_COMPLETED,
                    bus.first { it.type == EventType.SUBAGENT_COMPLETED && it.payload["agentId"] == ok }.type)
            }

            // 2) runner 抛错 → ERROR
            runner.mode = FakeRunner.Mode.THROW
            val bad = spawn(manager, task = "bad task")
            withTimeout(5_000) {
                assertEquals(EventType.SUBAGENT_ERROR,
                    bus.first { it.type == EventType.SUBAGENT_ERROR && it.payload["agentId"] == bad }.type)
            }

            // 3) stop_agent 收割 → STOPPED（取消路径事件不得被吞）
            runner.mode = FakeRunner.Mode.HANG
            val hung = spawn(manager, task = "hung task")
            withTimeout(5_000) { bus.first { it.type == EventType.SUBAGENT_STARTED && it.payload["agentId"] == hung } }
            manager.stop(hung)
            withTimeout(5_000) {
                assertEquals(EventType.SUBAGENT_STOPPED,
                    bus.first { it.type == EventType.SUBAGENT_STOPPED && it.payload["agentId"] == hung }.type)
            }

            // 4) stopAllForSession 收割 → STOPPED（abort 级联路径）
            val orphan = spawn(manager, task = "orphan task")
            withTimeout(5_000) { bus.first { it.type == EventType.SUBAGENT_STARTED && it.payload["agentId"] == orphan } }
            manager.stopAllForSession("sess_parent")
            withTimeout(5_000) {
                assertEquals(EventType.SUBAGENT_STOPPED,
                    bus.first { it.type == EventType.SUBAGENT_STOPPED && it.payload["agentId"] == orphan }.type)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `status json carries model metadata`() = runBlocking {
        val runner = FakeRunner()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val manager = newManager(runner, MutableSharedFlow(), scope)
            val agentId = spawn(manager)
            withTimeout(5_000) {
                while (manager.status(agentId).contains("\"status\":\"RUNNING\"")) kotlinx.coroutines.delay(20)
            }
            val statusJson = manager.status(agentId)
            assertTrue(statusJson.contains("\"modelName\":\"ModelB\""), "status 应带模型名: $statusJson")
            assertTrue(statusJson.contains("\"reasoningLevel\":\"HIGH\""), "status 应带推理档位: $statusJson")
        } finally {
            scope.cancel()
        }
    }
}
