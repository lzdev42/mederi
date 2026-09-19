package xyz.mederi.tools.subagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.plan.Plan
import xyz.mederi.plan.PlanStatus
import xyz.mederi.plan.PlanStore
import xyz.mederi.plan.Subtask
import xyz.mederi.plan.SubtaskStatus
import xyz.mederi.plan.VerificationSpec
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.store.InMemorySessionStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * spawn 工具动态读 session 模型（P2）行为锁定：
 * - session 有模型（计划批准时写入的新选择）→ spawn 用 session 值，而非工具构造时捕获的 turn 模型
 * - session 无模型/查不到 → fallback 到构造值
 * - spawn_researcher 同策略
 */
class SpawnToolDynamicModelTest {

    /** 记录 spawn 实收参数的 runner（ran 用于等后台协程实际执行完）。 */
    private class RecordingRunner : SubagentRunner {
        val ran = kotlinx.coroutines.CompletableDeferred<Unit>()
        var lastModel: AIModel? = null
        var lastReasoning: ReasoningLevel? = null
        override suspend fun run(
            task: String, briefing: String?, plan: String?, role: SubagentRole,
            directories: List<String>, aiModel: AIModel,
            reasoningLevel: ReasoningLevel, projectId: String, parentSessionId: String,
            apiKeyId: String?, executorPlanId: String?, executorSubtaskIndex: Int?,
            planStore: xyz.mederi.plan.PlanStore?
        ): String {
            lastModel = aiModel
            lastReasoning = reasoningLevel
            ran.complete(Unit)
            return "ok"
        }
    }

    private val tmpDir = Files.createTempDirectory("spawn-model-test")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tmpDir.toFile().deleteRecursively()
    }

    private fun model(id: String) = AIModel(id = id, providerModelId = id, name = "Model $id")

    private suspend fun sessionStoreWith(model: AIModel?, reasoning: ReasoningLevel?): InMemorySessionStore {
        val store = InMemorySessionStore()
        val now = java.time.Instant.now().toString()
        store.insert(Session(
            id = "sess_parent", projectId = "p1", title = "t", status = SessionStatus.IDLE,
            agentMode = AgentMode.AUTONOMOUS,
            aiModel = model, reasoningLevel = reasoning,
            env = emptyMap(), createdAt = now, updatedAt = now
        ))
        return store
    }

    /** 带单个带 spec 子任务的最小计划（已批准）。 */
    private fun planStoreWithSpecdSubtask(): Pair<PlanStore, String> {
        val planStore = PlanStore(listOf(tmpDir.toAbsolutePath().toString()))
        val plan = Plan(
            id = "plan_test", title = "t", sessionId = "sess_parent", overview = "o",
            subtasks = listOf(Subtask(
                index = 0, name = "s0", status = SubtaskStatus.PENDING,
                planDetail = "brief", spec = "1. do the thing",
                verification = VerificationSpec(command = "true")
            )),
            status = PlanStatus.APPROVED, createdAt = "2026-01-01T00:00:00Z",
            agentMode = AgentMode.AUTONOMOUS
        )
        planStore.save(plan)
        return planStore to plan.id
    }

    private fun newSpawnTool(
        runner: RecordingRunner,
        turnModel: AIModel,
        turnReasoning: ReasoningLevel,
        sessionStore: InMemorySessionStore?,
        planStore: PlanStore
    ) = SpawnAgentTool(
        subagentManager = SubagentManager(runner, scope),
        directories = emptyList(),
        aiModel = turnModel,
        reasoningLevel = turnReasoning,
        projectId = "p1",
        parentSessionId = "sess_parent",
        planStore = planStore,
        eventBus = null,
        apiKeyId = null,
        sessionStore = sessionStore
    )

    @Test
    fun `spawn uses session model when present`() = runBlocking {
        val runner = RecordingRunner()
        val (planStore, planId) = planStoreWithSpecdSubtask()
        // 构造时 turn 模型 = A；session（批准时写入）= B + HIGH —— spawn 必须用 B + HIGH
        val tool = newSpawnTool(
            runner, turnModel = model("A"), turnReasoning = ReasoningLevel.NONE,
            sessionStore = sessionStoreWith(model("B"), ReasoningLevel.HIGH),
            planStore = planStore
        )

        val result = tool.execute(SpawnAgentArgs(task = "do it", planId = planId, subtaskIndex = 0))
        kotlinx.coroutines.withTimeout(5_000) { runner.ran.await() }

        assert(result.contains("\"status\":\"RUNNING\"")) { "unexpected spawn result: $result" }
        assertEquals("B", runner.lastModel?.id, "spawn 应使用 session 现值 B（批准时刻选择），而非 turn 构造值 A")
        assertEquals(ReasoningLevel.HIGH, runner.lastReasoning, "spawn 应使用 session 推理档位 HIGH")
    }

    @Test
    fun `spawn falls back to turn model when session has none`() = runBlocking {
        val runner = RecordingRunner()
        val (planStore, planId) = planStoreWithSpecdSubtask()
        val tool = newSpawnTool(
            runner, turnModel = model("A"), turnReasoning = ReasoningLevel.MEDIUM,
            sessionStore = sessionStoreWith(model = null, reasoning = null),
            planStore = planStore
        )

        tool.execute(SpawnAgentArgs(task = "do it", planId = planId, subtaskIndex = 0))
        kotlinx.coroutines.withTimeout(5_000) { runner.ran.await() }

        assertEquals("A", runner.lastModel?.id, "session 无模型时 fallback 到构造值 A")
        assertEquals(ReasoningLevel.MEDIUM, runner.lastReasoning, "session 无档位时 fallback 到构造值 MEDIUM")
    }

    @Test
    fun `researcher spawn follows same session-first policy`() = runBlocking {
        val runner = RecordingRunner()
        val tool = SpawnResearcherTool(
            subagentManager = SubagentManager(runner, scope),
            directories = emptyList(),
            aiModel = model("A"),
            reasoningLevel = ReasoningLevel.NONE,
            projectId = "p1",
            parentSessionId = "sess_parent",
            apiKeyId = null,
            sessionStore = sessionStoreWith(model("C"), ReasoningLevel.LOW)
        )

        tool.execute(SpawnResearcherArgs(task = "investigate"))
        kotlinx.coroutines.withTimeout(5_000) { runner.ran.await() }

        assertEquals("C", runner.lastModel?.id, "researcher 同样以 session 现值优先")
        assertEquals(ReasoningLevel.LOW, runner.lastReasoning)
    }
}
