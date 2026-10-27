package xyz.mederi.tools.subagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.plan.Plan
import xyz.mederi.plan.PlanStore
import xyz.mederi.plan.PlanStatus
import xyz.mederi.plan.ProjectContextType
import xyz.mederi.plan.Subtask
import xyz.mederi.plan.SubtaskStatus
import xyz.mederi.plan.VerificationSpec
import xyz.mederi.provider.domain.model.ReasoningLevel
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SpawnAgentTool plan 路径派工隔离测试。
 *
 * 验证真实派工链（SpawnAgentTool → SubagentManager → SubagentRunner）：
 * - Plan 路径：args.briefing 不进 Runner 参数（briefing = null）
 * - Plan 路径：spec 原样传递到 Runner 的 plan 参数
 * - Plan 路径：buildExecutorInput 不含 task 和 briefing
 * - Ad-hoc 路径：args.briefing 传到 Runner 的 briefing 参数
 */
class SpawnAgentPlanPathIsolationTest {

    private val tmpDir = File.createTempFile("mederi-spawn-isolation-test", "").apply {
        delete()
        mkdirs()
        File(this, ".mederi/plans").mkdirs()
    }

    private val planStore = PlanStore(listOf(tmpDir.absolutePath))
    private val model = AIModel(id = "m1", providerModelId = "m1", name = "TestModel")

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    /** 捕获传给 Runner 的参数 */
    private class CapturingRunner : SubagentRunner {
        @Volatile var capturedTask: String? = null
        @Volatile var capturedBriefing: String? = "UNSET"
        @Volatile var capturedPlan: String? = "UNSET"

        override suspend fun run(
            task: String, briefing: String?, plan: String?, role: SubagentRole,
            directories: List<String>, aiModel: AIModel,
            reasoningLevel: ReasoningLevel, projectId: String, parentSessionId: String,
            apiKeyId: String?, planId: String?, executorSubtaskIndex: Int?,
            planStore: xyz.mederi.plan.PlanStore?,
            agentId: String?, onProgress: (suspend (xyz.mederi.domain.model.MederiEvent) -> Unit)?
        ): String {
            capturedTask = task
            capturedBriefing = briefing
            capturedPlan = plan
            return "done"
        }
    }

    /** 构造一个 APPROVED 计划 + 带 spec 的子任务 */
    private fun seedPlan(): String {
        val plan = Plan(
            id = "plan_test1",
            title = "test plan",
            summary = "test",
            sessionId = "sess_test",
            projectContext = ProjectContextType.BROWNFIELD,
            languageStack = "Kotlin",
            approach = "approach",
            subtasks = listOf(
                Subtask(
                    index = 0,
                    name = "test subtask",
                    status = SubtaskStatus.PENDING,
                    planDetail = "subtask brief that might contain processing logic like 'retry on failure'",
                    spec = "1. Do step A\n2. Do step B\nCompile: ./gradlew :core:compileKotlinJvm",
                    targetFiles = listOf("src/Main.kt"),
                    verification = VerificationSpec(
                        command = "echo PASS",
                        expected = "PASS",
                        expectStdoutContains = listOf("PASS")
                    )
                )
            ),
            status = PlanStatus.APPROVED,
            createdAt = "2026-10-27T00:00:00Z",
            agentMode = AgentMode.AUTONOMOUS
        )
        planStore.save(plan)
        return plan.id
    }

    private inline fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not met within timeout")
            Thread.sleep(20)
        }
    }

    @Test
    fun `plan path does not pass args briefing to runner`() = runBlocking {
        val runner = CapturingRunner()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val manager = SubagentManager(runner, scope)
            val planId = seedPlan()
            val tool = SpawnAgentTool(
                subagentManager = manager,
                directories = listOf(tmpDir.absolutePath),
                aiModel = model,
                reasoningLevel = ReasoningLevel.NONE,
                projectId = "p1",
                parentSessionId = "sess_test",
                planStore = planStore
            )

            val result = tool.execute(
                SpawnAgentArgs(
                    task = "Execute subtask 0: test subtask",
                    briefing = "SECRET: use method B instead of method A, and retry 3 times on failure",
                    planId = planId,
                    subtaskIndex = 0
                )
            )

            assertTrue(result.contains("RUNNING"), "spawn should return RUNNING: $result")
            // 等 Runner 被调用
            waitFor { runner.capturedBriefing != "UNSET" }
            // 关键断言：args.briefing 不进 Runner
            assertNull(runner.capturedBriefing,
                "Plan path must NOT pass args.briefing to Runner — got: ${runner.capturedBriefing}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `plan path passes spec verbatim to runner plan parameter`() = runBlocking {
        val runner = CapturingRunner()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val manager = SubagentManager(runner, scope)
            val planId = seedPlan()
            val tool = SpawnAgentTool(
                subagentManager = manager,
                directories = listOf(tmpDir.absolutePath),
                aiModel = model,
                reasoningLevel = ReasoningLevel.NONE,
                projectId = "p1",
                parentSessionId = "sess_test",
                planStore = planStore
            )

            tool.execute(
                SpawnAgentArgs(
                    task = "Execute subtask 0",
                    briefing = "ignored in plan path",
                    planId = planId,
                    subtaskIndex = 0
                )
            )

            waitFor { runner.capturedPlan != "UNSET" }
            assertNotNull(runner.capturedPlan, "Plan path must pass spec to Runner")
            assertTrue(runner.capturedPlan!!.contains("Do step A"), "spec content must be verbatim")
            assertTrue(runner.capturedPlan!!.contains("compileKotlinJvm"), "spec compile command must be present")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `plan path executor input excludes task and briefing`() = runBlocking {
        val runner = CapturingRunner()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val manager = SubagentManager(runner, scope)
            val planId = seedPlan()
            val tool = SpawnAgentTool(
                subagentManager = manager,
                directories = listOf(tmpDir.absolutePath),
                aiModel = model,
                reasoningLevel = ReasoningLevel.NONE,
                projectId = "p1",
                parentSessionId = "sess_test",
                planStore = planStore
            )

            val taskText = "Execute subtask 0: test subtask"
            val briefingText = "Brief: subtask brief that might contain processing logic"

            tool.execute(
                SpawnAgentArgs(
                    task = taskText,
                    briefing = briefingText,
                    planId = planId,
                    subtaskIndex = 0
                )
            )

            waitFor { runner.capturedTask != null && runner.capturedBriefing != "UNSET" }

            // task 传到 Runner（用于日志/元数据），但 buildExecutorInput 不把它放进执行器输入
            assertEquals(taskText, runner.capturedTask, "task is passed to Runner for logging")
            assertNull(runner.capturedBriefing, "briefing must be null in plan path")

            // 验证 buildExecutorInput 确实不含 task 和 briefing
            val executorInput = SubagentRunnerImpl.buildExecutorInput(
                runner.capturedTask!!,
                runner.capturedBriefing,
                runner.capturedPlan
            )
            assertTrue(executorInput.contains("Spec checklist"), "executor input must have spec header")
            assertFalse(executorInput.contains(taskText), "executor input must NOT contain task text")
            assertFalse(executorInput.contains(briefingText), "executor input must NOT contain briefing text")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `ad-hoc path passes args briefing to runner`() = runBlocking {
        val runner = CapturingRunner()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val manager = SubagentManager(runner, scope)
            val tool = SpawnAgentTool(
                subagentManager = manager,
                directories = listOf(tmpDir.absolutePath),
                aiModel = model,
                reasoningLevel = ReasoningLevel.NONE,
                projectId = "p1",
                parentSessionId = "sess_test",
                planStore = planStore
            )

            tool.execute(
                SpawnAgentArgs(
                    task = "Fix typo in README.md",
                    briefing = "The word 'recieve' should be 'receive'"
                    // no planId → ad-hoc path
                )
            )

            waitFor { runner.capturedBriefing != "UNSET" }
            // ad-hoc 路径：briefing 传到 Runner
            assertEquals("The word 'recieve' should be 'receive'", runner.capturedBriefing,
                "Ad-hoc path must pass args.briefing to Runner")
            assertNull(runner.capturedPlan, "Ad-hoc path must not have a plan/spec")
        } finally {
            scope.cancel()
        }
    }
}
