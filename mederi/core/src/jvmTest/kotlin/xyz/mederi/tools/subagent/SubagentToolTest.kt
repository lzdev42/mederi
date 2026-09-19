package xyz.mederi.tools.subagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.provider.domain.model.ReasoningLevel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SubagentTool（6 工具合一，action 分流）行为锁定：
 * - STATUS / STOP / WAIT 委托 SubagentManager
 * - agentId 空白时各管理 action 返回 Error 文本（不抛异常）
 * - SPAWN / SPAWN_RESEARCHER 委托原 spawn 工具（深链由 SpawnToolDynamicModelTest 覆盖）
 */
class SubagentToolTest {

    private class FakeRunner : SubagentRunner {
        override suspend fun run(
            task: String, briefing: String?, plan: String?, role: SubagentRole,
            directories: List<String>, aiModel: AIModel,
            reasoningLevel: ReasoningLevel, projectId: String, parentSessionId: String,
            apiKeyId: String?, executorPlanId: String?, executorSubtaskIndex: Int?,
            planStore: xyz.mederi.plan.PlanStore?
        ): String {
            delay(50)
            return "done: $task"
        }
    }

    private val model = AIModel(id = "m1", providerModelId = "m1", name = "ModelB")

    @Test
    fun `status routes to manager and blank agentId yields error`() = runBlocking {
        val runner = FakeRunner()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val manager = SubagentManager(runner, scope)
            // SPAWN/SPAWN_RESEARCHER 分支本次不触发，构造实例足够（代理延迟到真正 execute）
            val tool = SubagentTool(
                spawnExecutor = SpawnAgentTool(
                    subagentManager = manager, directories = emptyList(), aiModel = model,
                    reasoningLevel = ReasoningLevel.NONE, projectId = "p1", parentSessionId = "sess"
                ),
                spawnResearcher = SpawnResearcherTool(
                    subagentManager = manager, directories = emptyList(), aiModel = model,
                    reasoningLevel = ReasoningLevel.NONE, projectId = "p1", parentSessionId = "sess"
                ),
                manager = manager
            )

            val agentId = manager.spawn(
                task = "t", briefing = null, plan = null, role = SubagentRole.EXECUTOR,
                directories = emptyList(), aiModel = model,
                reasoningLevel = ReasoningLevel.NONE, projectId = "p1", parentSessionId = "sess"
            )
            waitFor { manager.status(agentId).contains("\"status\":\"COMPLETED\"") }

            val status = tool.execute(SubagentArgs(action = SubagentAction.STATUS, agentId = agentId))
            assertTrue(status.contains("\"status\":\"COMPLETED\""), "STATUS 应委托 manager: $status")

            val blankWait = tool.execute(SubagentArgs(action = SubagentAction.WAIT))
            assertTrue(blankWait.startsWith("Error: WAIT"), "WAIT 缺 agentId 应返回 Error: $blankWait")

            val blankStop = tool.execute(SubagentArgs(action = SubagentAction.STOP))
            assertTrue(blankStop.startsWith("Error: STOP"), "STOP 缺 agentId 应返回 Error: $blankStop")
        } finally {
            scope.cancel()
        }
    }

    private inline fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not met")
            Thread.sleep(20)
        }
    }
}