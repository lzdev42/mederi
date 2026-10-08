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
 * - STATUS / STOP 委托 SubagentManager
 * - agentId 空白时各管理 action 返回 Error 文本（不抛异常）
 * - SPAWN / SPAWN_RESEARCHER 委托原 spawn 工具（深链由 SpawnToolDynamicModelTest 覆盖）
 */
class SubagentToolTest {

    private class FakeRunner : SubagentRunner {
        override suspend fun run(
            task: String, briefing: String?, plan: String?, role: SubagentRole,
            directories: List<String>, aiModel: AIModel,
            reasoningLevel: ReasoningLevel, projectId: String, parentSessionId: String,
            apiKeyId: String?, planId: String?, executorSubtaskIndex: Int?,
            planStore: xyz.mederi.plan.PlanStore?,
            agentId: String?, onProgress: (suspend (activity: String, delta: String, toolName: String?, isMessage: Boolean) -> Unit)?
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
                manager = manager,
                parentSessionId = "sess"
            )

            val agentId = manager.spawn(
                task = "t", briefing = null, plan = null, role = SubagentRole.EXECUTOR,
                directories = emptyList(), aiModel = model,
                reasoningLevel = ReasoningLevel.NONE, projectId = "p1", parentSessionId = "sess"
            )
            waitFor { manager.status(agentId).contains("\"status\":\"COMPLETED\"") }

            val status = tool.execute(SubagentArgs(action = SubagentAction.STATUS, agentId = agentId))
            assertTrue(status.contains("\"status\":\"COMPLETED\""), "STATUS 应委托 manager: $status")

            val blankStop = tool.execute(SubagentArgs(action = SubagentAction.STOP))
            assertTrue(blankStop.startsWith("Error: STOP"), "STOP 缺 agentId 应返回 Error: $blankStop")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stop via SubagentTool vs user stop distinguishes operator in result and event`() = runBlocking {
        val bus = kotlinx.coroutines.flow.MutableSharedFlow<xyz.mederi.domain.model.MederiEvent>(replay = 64)
        val hangRunner = object : SubagentRunner {
            override suspend fun run(
                task: String, briefing: String?, plan: String?, role: SubagentRole,
                directories: List<String>, aiModel: AIModel,
                reasoningLevel: ReasoningLevel, projectId: String, parentSessionId: String,
                apiKeyId: String?, planId: String?, executorSubtaskIndex: Int?,
                planStore: xyz.mederi.plan.PlanStore?,
                agentId: String?, onProgress: (suspend (activity: String, delta: String, toolName: String?, isMessage: Boolean) -> Unit)?
            ): String {
                delay(Long.MAX_VALUE)
                return ""
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val manager = SubagentManager(hangRunner, scope, bus)
            val tool = SubagentTool(
                spawnExecutor = SpawnAgentTool(
                    subagentManager = manager, directories = emptyList(), aiModel = model,
                    reasoningLevel = ReasoningLevel.NONE, projectId = "p1", parentSessionId = "sess"
                ),
                spawnResearcher = SpawnResearcherTool(
                    subagentManager = manager, directories = emptyList(), aiModel = model,
                    reasoningLevel = ReasoningLevel.NONE, projectId = "p1", parentSessionId = "sess"
                ),
                manager = manager,
                parentSessionId = "sess"
            )

            val aiStoppedId = manager.spawn(
                task = "ai task", briefing = null, plan = null, role = SubagentRole.RESEARCHER,
                directories = emptyList(), aiModel = model,
                reasoningLevel = ReasoningLevel.NONE, projectId = "p1", parentSessionId = "sess"
            )
            val userStoppedId = manager.spawn(
                task = "user task", briefing = null, plan = null, role = SubagentRole.RESEARCHER,
                directories = emptyList(), aiModel = model,
                reasoningLevel = ReasoningLevel.NONE, projectId = "p1", parentSessionId = "sess"
            )

            val aiStopResult = tool.execute(SubagentArgs(action = SubagentAction.STOP, agentId = aiStoppedId))
            val userStopResult = manager.stop(userStoppedId)
            println("[DIAG] aiStopResult=$aiStopResult")
            println("[DIAG] userStopResult=$userStopResult")

            waitFor {
                bus.replayCache.count { it.type == xyz.mederi.domain.model.EventType.SUBAGENT_STOPPED } >= 2
            }
            val aiEvent = bus.replayCache.first {
                it.type == xyz.mederi.domain.model.EventType.SUBAGENT_STOPPED && it.payload["agentId"] == aiStoppedId
            }
            val userEvent = bus.replayCache.first {
                it.type == xyz.mederi.domain.model.EventType.SUBAGENT_STOPPED && it.payload["agentId"] == userStoppedId
            }
            println("[DIAG] aiEvent.payload=${aiEvent.payload}")
            println("[DIAG] userEvent.payload=${userEvent.payload}")

            assertTrue(aiStopResult.contains("\"result\":\"已被主agent关闭\""), "主 Agent 关闭应返回 '已被主agent关闭': $aiStopResult")
            assertTrue(userStopResult.contains("\"result\":\"被用户关闭\""), "用户关闭应返回 '被用户关闭': $userStopResult")
            kotlin.test.assertEquals("已被主agent关闭", aiEvent.payload["result"], "主 Agent 关闭事件 result 应为 '已被主agent关闭'")
            kotlin.test.assertEquals("被用户关闭", userEvent.payload["result"], "用户关闭事件 result 应为 '被用户关闭'")
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