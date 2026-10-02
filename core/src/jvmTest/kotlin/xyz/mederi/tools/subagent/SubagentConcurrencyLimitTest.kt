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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SubagentManager 并发上限（2026-09 代码级硬门禁）行为锁定：
 *
 * - provider 未注入（null）时 spawn 不受限（兼容测试/未装配）
 * - 达上限后继续 spawn 抛 [SubagentLimitReachedException]，modelGuidance 含 limit/running
 * - 上限与类型无关：EXECUTOR + RESEARCHER 同池计数
 * - 终态释放名额：子代理完成后可补派（不需调 setMaxConcurrent）
 * - 浏览器角色（BROWSER_OPERATOR / BROWSER_BRAIN）显式豁免，即便配 provider=1 也不受限
 */
class SubagentConcurrencyLimitTest {

    private class FakeRunner : SubagentRunner {
        var hang = false
        override suspend fun run(
            task: String, briefing: String?, plan: String?, role: SubagentRole,
            directories: List<String>, aiModel: AIModel,
            reasoningLevel: ReasoningLevel, projectId: String, parentSessionId: String,
            apiKeyId: String?, planId: String?, executorSubtaskIndex: Int?,
            planStore: xyz.mederi.plan.PlanStore?,
            agentId: String?, onProgress: (suspend (activity: String, delta: String, toolName: String?, isMessage: Boolean) -> Unit)?
        ): String {
            if (hang) delay(Long.MAX_VALUE) // 挂起直到被 cancel
            return "done: $task"
        }
    }

    private val model = AIModel(id = "m1", providerModelId = "m1", name = "ModelB")

    private fun spawnExecutor(manager: SubagentManager, sessionId: String, role: SubagentRole = SubagentRole.EXECUTOR): String =
        runBlocking {
            manager.spawn(
                task = "t", briefing = null, plan = null, role = role,
                directories = emptyList(), aiModel = model,
                reasoningLevel = ReasoningLevel.NONE,
                projectId = "p1", parentSessionId = sessionId
            )
        }

    private fun statusName(manager: SubagentManager, agentId: String): String {
        val m = Regex("\"status\"\\s*:\\s*\"([A-Z_]+)\"").find(manager.status(agentId))
        return m?.groupValues?.get(1) ?: "PARSE_FAIL"
    }

    private inline fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not met within ${timeoutMs}ms")
            Thread.sleep(20)
        }
    }

    @Test
    fun `provider null means no limit`() = runBlocking {
        val runner = FakeRunner().apply { hang = true }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val manager = SubagentManager(runner, scope, eventBus = null, maxConcurrentProvider = null)
        try {
            // 连派 10 个都不受限
            repeat(10) { spawnExecutor(manager, "sess") }
            assertEquals(10, manager.agentsCount("sess"))
        } finally {
            manager.stopAllForSession("sess")
            scope.cancel()
        }
    }

    @Test
    fun `spawn rejected when limit reached`() = runBlocking {
        val runner = FakeRunner().apply { hang = true }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val manager = SubagentManager(
            runner, scope, eventBus = null,
            maxConcurrentProvider = { 2 }
        )
        try {
            spawnExecutor(manager, "sess")
            spawnExecutor(manager, "sess")
            // 第三个必须被拒
            try {
                spawnExecutor(manager, "sess")
                fail("expected SubagentLimitReachedException")
            } catch (e: SubagentLimitReachedException) {
                assertEquals(2, e.running)
                assertEquals(2, e.limit)
                assertTrue(e.modelGuidance.contains("limit=2"))
                assertTrue(e.modelGuidance.contains("Do NOT spawn"))
            }
        } finally {
            manager.stopAllForSession("sess")
            scope.cancel()
        }
    }

    @Test
    fun `limit is shared across executor and researcher`() = runBlocking {
        val runner = FakeRunner().apply { hang = true }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val manager = SubagentManager(
            runner, scope, eventBus = null,
            maxConcurrentProvider = { 2 }
        )
        try {
            spawnExecutor(manager, "sess", role = SubagentRole.EXECUTOR)
            spawnExecutor(manager, "sess", role = SubagentRole.RESEARCHER)
            // 混派 2 个后第三个被拒——证明 EXECUTOR/RESEARCHER 同池
            try {
                spawnExecutor(manager, "sess", role = SubagentRole.RESEARCHER)
                fail("expected SubagentLimitReachedException")
            } catch (_: SubagentLimitReachedException) {
            }
        } finally {
            manager.stopAllForSession("sess")
            scope.cancel()
        }
    }

    @Test
    fun `terminal agent frees a slot`() = runBlocking {
        val runner = FakeRunner().apply { hang = false } // 立即完成
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val manager = SubagentManager(
            runner, scope, eventBus = null,
            maxConcurrentProvider = { 1 }
        )
        try {
            val a1 = spawnExecutor(manager, "sess")
            waitFor { statusName(manager, a1) == "COMPLETED" }

            // 第一个完成后名额释放，可补派
            val a2 = spawnExecutor(manager, "sess")
            assertTrue(a2.startsWith("sub_"))
            waitFor { statusName(manager, a2) == "COMPLETED" }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `limit is per parent session`() = runBlocking {
        val runner = FakeRunner().apply { hang = true }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val manager = SubagentManager(
            runner, scope, eventBus = null,
            maxConcurrentProvider = { 1 }
        )
        try {
            // sess_A 占一个名额
            spawnExecutor(manager, "sess_A")
            // sess_B 不受 sess_A 占用影响
            spawnExecutor(manager, "sess_B")
            // sess_A 第二个被拒
            try {
                spawnExecutor(manager, "sess_A")
                fail("expected SubagentLimitReachedException")
            } catch (_: SubagentLimitReachedException) {
            }
        } finally {
            manager.stopAllForSession("sess_A")
            manager.stopAllForSession("sess_B")
            scope.cancel()
        }
    }

    @Test
    fun `browser roles are exempt from the limit`() = runBlocking {
        val runner = FakeRunner().apply { hang = true }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        // 即便上限=1，浏览器角色也不受限制
        val manager = SubagentManager(
            runner, scope, eventBus = null,
            maxConcurrentProvider = { 1 }
        )
        try {
            repeat(5) {
                spawnExecutor(manager, "sess", role = SubagentRole.BROWSER_OPERATOR)
            }
            repeat(5) {
                spawnExecutor(manager, "sess", role = SubagentRole.BROWSER_BRAIN)
            }
            // 浏览器角色不计入限额——但非浏览器角色仍受限
            try {
                spawnExecutor(manager, "sess", role = SubagentRole.EXECUTOR)
                fail("expected SubagentLimitReachedException for non-browser role")
            } catch (_: SubagentLimitReachedException) {
            }
        } finally {
            manager.stopAllForSession("sess")
            scope.cancel()
        }
    }

    @Test
    fun `setting change takes effect immediately on next spawn`() = runBlocking {
        val runner = FakeRunner().apply { hang = true }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        // provider 每次返回当前值，模拟设置页改值
        var currentLimit = 2
        val manager = SubagentManager(
            runner, scope, eventBus = null,
            maxConcurrentProvider = { currentLimit }
        )
        try {
            spawnExecutor(manager, "sess")
            spawnExecutor(manager, "sess")
            // 上限提到 3，可派第三个
            currentLimit = 3
            spawnExecutor(manager, "sess")
            // 上限降回 2，第四个被拒
            currentLimit = 2
            try {
                spawnExecutor(manager, "sess")
                fail("expected SubagentLimitReachedException after lowering limit")
            } catch (_: SubagentLimitReachedException) {
            }
        } finally {
            manager.stopAllForSession("sess")
            scope.cancel()
        }
    }
}

/** 辅助：查某会话 RUNNING 子代理数（测试内部）。 */
private fun SubagentManager.agentsCount(sessionId: String): Int {
    val field = SubagentManager::class.java.getDeclaredField("agents")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val agents = field.get(this) as java.util.concurrent.ConcurrentHashMap<String, SubagentManager.BackgroundAgent>
    return agents.values.count {
        it.parentSessionId == sessionId && it.status == SubagentManager.SubagentStatus.RUNNING
    }
}
