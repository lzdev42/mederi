package xyz.mederi.tools.subagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.provider.domain.model.ReasoningLevel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SpawnAgentTool / SpawnResearcherTool 捕获 [SubagentLimitReachedException] 并返回
 * 模型可理解的 Error 文案（2026-09 并发硬门禁）行为锁定：
 *
 * - ad-hoc（无 planId）spawn 达上限 → 工具结果 = modelGuidance（含 "Do NOT spawn"）
 * - plan 路径 spawn 达上限 → 工具结果 = modelGuidance（不抛异常、不污染 PlanStore 状态）
 * - SPAWN_RESEARCHER 达上限 → 工具结果 = modelGuidance
 */
class SpawnConcurrencyLimitToolTest {

    private class HangingRunner : SubagentRunner {
        override suspend fun run(
            task: String, briefing: String?, plan: String?, role: SubagentRole,
            directories: List<String>, aiModel: AIModel,
            reasoningLevel: ReasoningLevel, projectId: String, parentSessionId: String,
            apiKeyId: String?, planId: String?, executorSubtaskIndex: Int?,
            planStore: xyz.mederi.plan.PlanStore?,
            agentId: String?, onProgress: (suspend (activity: String, delta: String, toolName: String?, isMessage: Boolean) -> Unit)?
        ): String {
            kotlinx.coroutines.delay(Long.MAX_VALUE) // 挂起直到 cancel，保持 RUNNING 占名额
            return "done"
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val model = AIModel(id = "m1", providerModelId = "m1", name = "ModelB")

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun newManager(limit: Int): SubagentManager = SubagentManager(
        HangingRunner(), scope, eventBus = null,
        maxConcurrentProvider = { limit }
    )

    private fun spawnAdhoc(manager: SubagentManager) = SpawnAgentTool(
        subagentManager = manager,
        directories = emptyList(),
        aiModel = model,
        reasoningLevel = ReasoningLevel.NONE,
        projectId = "p1",
        parentSessionId = "sess_parent",
        planStore = null,
        eventBus = null,
        apiKeyId = null,
        sessionStore = null,
        subagentConfigManager = null
    )

    private fun spawnResearcher(manager: SubagentManager) = SpawnResearcherTool(
        subagentManager = manager,
        directories = emptyList(),
        aiModel = model,
        reasoningLevel = ReasoningLevel.NONE,
        projectId = "p1",
        parentSessionId = "sess_parent",
        apiKeyId = null,
        sessionStore = null,
        subagentConfigManager = null,
        planStore = null
    )

    @Test
    fun `ad-hoc spawn returns model guidance when limit reached`() = runBlocking {
        val manager = newManager(1)
        val tool = spawnAdhoc(manager)
        // 占满名额
        val first = tool.execute(SpawnAgentArgs(task = "first"))
        assertTrue(first.contains("\"status\":\"RUNNING\""), "first spawn should succeed: $first")

        // 第二个被拒，返回 Error 文案而非 JSON
        val second = tool.execute(SpawnAgentArgs(task = "second"))
        assertTrue(second.startsWith("Error:"), "rejected spawn should return Error text: $second")
        assertTrue(second.contains("limit=1"), "error should mention limit: $second")
        assertTrue(second.contains("Do NOT spawn"), "error should instruct model: $second")

        manager.stopAllForSession("sess_parent")
        Unit
    }

    @Test
    fun `researcher spawn returns model guidance when limit reached`() = runBlocking {
        val manager = newManager(1)
        val researcher = spawnResearcher(manager)
        // 占满名额（用 executor）
        val first = spawnAdhoc(manager).execute(SpawnAgentArgs(task = "hold"))
        assertTrue(first.contains("\"status\":\"RUNNING\""))

        // researcher 被拒——证明同池计数
        val second = researcher.execute(SpawnResearcherArgs(task = "investigate"))
        assertTrue(second.startsWith("Error:"), "rejected researcher should return Error text: $second")
        assertTrue(second.contains("limit=1"), "error should mention limit: $second")

        manager.stopAllForSession("sess_parent")
        Unit
    }
}
