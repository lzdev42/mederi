package xyz.mederi.tools.subagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.domain.model.WorkType
import xyz.mederi.provider.domain.model.ReasoningLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * stopAllForSession（abort 级联收割子代理）行为锁定：
 * - 只杀目标父会话的 RUNNING 子代理；其他会话、已终态的不受影响
 * - 被杀的子代理最终翻成 STOPPED（SubagentManager 的取消路径标记）
 * - 不存在的会话返回 0（幂等）
 */
class SubagentManagerStopAllTest {

    /** 可控 fake runner：hang = 挂起直到取消（模拟长跑）；否则立即完成返回文本。 */
    private class FakeRunner : SubagentRunner {
        var hang = false
        override suspend fun run(
            task: String, briefing: String?, plan: String?, role: SubagentRole,
            workType: WorkType, directories: List<String>, aiModel: AIModel,
            reasoningLevel: ReasoningLevel, projectId: String, parentSessionId: String,
            apiKeyId: String?, executorPlanId: String?, executorSubtaskIndex: Int?,
            planStore: xyz.mederi.plan.PlanStore?
        ): String {
            if (hang) {
                delay(Long.MAX_VALUE) // 挂起直至被 cancel
            }
            return "done: $task"
        }
    }

    private fun spawn(manager: SubagentManager, sessionId: String): String = manager.spawn(
        task = "t", briefing = null, plan = null, role = SubagentRole.EXECUTOR,
        workType = WorkType.CODE, directories = emptyList(),
        aiModel = AIModel(id = "m", providerModelId = "m", name = "m"), reasoningLevel = ReasoningLevel.NONE,
        projectId = "p", parentSessionId = sessionId
    )

    private fun statusName(json: String): String {
        val m = Regex("\"status\"\\s*:\\s*\"([A-Z_]+)\"").find(json)
        return m?.groupValues?.get(1) ?: "PARSE_FAIL"
    }

    @Test
    fun `stops only running agents of the target session`() = runBlocking {
        val runner = FakeRunner().apply { hang = true }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val manager = SubagentManager(runner, scope)
        try {
            val a1 = spawn(manager, "sess_A")
            val a2 = spawn(manager, "sess_A")
            val b1 = spawn(manager, "sess_B")
            delay(100) // 让后台协程进入 running

            val stopped = manager.stopAllForSession("sess_A")

            assertEquals(2, stopped)
            // 取消是异步信号，轮询等状态翻 STOPPED
            waitFor { statusName(manager.status(a1)) == "STOPPED" }
            waitFor { statusName(manager.status(a2)) == "STOPPED" }
            // 其他会话不受影响
            assertEquals("RUNNING", statusName(manager.status(b1)))
        } finally {
            manager.stopAllForSession("sess_B")
            scope.cancel()
        }
    }

    @Test
    fun `does not touch terminal agents and unknown session yields zero`() = runBlocking {
        val runner = FakeRunner().apply { hang = false } // 立即完成
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val manager = SubagentManager(runner, scope)
        try {
            val a1 = spawn(manager, "sess_A")
            waitFor { statusName(manager.status(a1)) == "COMPLETED" }

            assertEquals(0, manager.stopAllForSession("sess_A"))
            assertEquals("COMPLETED", statusName(manager.status(a1)))
            assertEquals(0, manager.stopAllForSession("no_such_session"))
        } finally {
            scope.cancel()
        }
    }

    /** 简单轮询等待（上限 5s），避免竞态导致的偶发失败。 */
    private inline fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not met within ${timeoutMs}ms")
            Thread.sleep(20)
        }
    }
}
