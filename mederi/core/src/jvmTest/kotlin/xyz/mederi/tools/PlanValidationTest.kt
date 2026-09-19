package xyz.mederi.tools

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.WorkType
import xyz.mederi.plan.Notebook
import xyz.mederi.plan.PlanApprovalRequester
import xyz.mederi.plan.PlanStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * validatePlan 命令格式校验直测（validatePlan 是 private，经 CreatePlanTool.execute 绕道）：
 * - 含 CJK 的 verification（散文描述）→ 拒绝，报错含子任务索引与字段提示
 * - 纯 ASCII 命令 → 通过
 * - SubtaskArg 的 verificationCwd/verificationTimeoutSeconds → 构造出的 VerificationSpec 字段正确
 *
 * 全部用 WorkType.WORK + AUTONOMOUS：避免 CODE 模式额外的必填校验干扰，
 * 且 AUTONOMOUS 下 execute 校验通过即落盘返回，无需等待人工批准。
 */
class PlanValidationTest {

    private val tmpDir = File.createTempFile("mederi-plan-validation-test", "").apply {
        delete()
        mkdirs()
        File(this, ".mederi/plans").mkdirs()
    }

    private val planStore = PlanStore(listOf(tmpDir.absolutePath))
    private val eventBus = MutableSharedFlow<MederiEvent>(replay = 64)
    private val planApprovalRequester = PlanApprovalRequester("sess_validation", eventBus)

    private fun planTools(workType: WorkType) = PlanTools(
        sessionId = "sess_validation",
        agentMode = AgentMode.AUTONOMOUS,
        workType = workType,
        planStore = planStore,
        planApprovalRequester = planApprovalRequester,
        notebook = Notebook(listOf(tmpDir.absolutePath)),
        eventBus = eventBus
    )

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `CJK prose verification is rejected`() = runBlocking {
        val result = planTools(WorkType.WORK).CreatePlanTool().execute(
            PlanTools.CreatePlanArgs(
                title = "test plan",
                summary = "test summary",
                overview = "test overview",
                subtasks = listOf(
                    PlanTools.SubtaskArg(
                        name = "run tests",
                        verification = "跑 pytest 全部通过"
                    )
                )
            )
        )
        assertTrue(result.startsWith("Error"), "Expected rejection, got: $result")
        assertTrue(result.contains("Subtask 0"), "Error should name subtask index: $result")
        assertTrue(result.contains("verification"), "Error should name verification field: $result")
        assertTrue(result.contains("ASCII"), "Error should explain the ASCII requirement: $result")
    }

    @Test
    fun `pure ASCII command passes validation`() = runBlocking {
        val result = planTools(WorkType.WORK).CreatePlanTool().execute(
            PlanTools.CreatePlanArgs(
                title = "test plan",
                summary = "test summary",
                overview = "test overview",
                subtasks = listOf(
                    PlanTools.SubtaskArg(
                        name = "grep check",
                        verification = "grep -q foo file.txt"
                    )
                )
            )
        )
        assertTrue(result.startsWith("Plan created"), "Expected success, got: $result")
    }

    @Test
    fun `cwd and timeout flow into VerificationSpec`() = runBlocking {
        val result = planTools(WorkType.WORK).CreatePlanTool().execute(
            PlanTools.CreatePlanArgs(
                title = "test plan",
                summary = "test summary",
                overview = "test overview",
                subtasks = listOf(
                    PlanTools.SubtaskArg(
                        name = "compile",
                        verification = "grep -q foo file.txt",
                        verificationCwd = "sub/dir",
                        verificationTimeoutSeconds = 120
                    )
                )
            )
        )
        assertTrue(result.startsWith("Plan created"), "Expected success, got: $result")

        val planId = Regex("plan_[0-9a-f]{8}").find(result)?.value
        assertNotNull(planId, "Expected plan id in result: $result")
        val plan = planStore.load(planId)
        assertNotNull(plan, "Plan should be persisted")
        assertEquals(1, plan.subtasks.size)
        assertEquals("grep -q foo file.txt", plan.subtasks[0].verification.command)
        assertEquals("sub/dir", plan.subtasks[0].verification.cwd)
        assertEquals(120, plan.subtasks[0].verification.timeoutSeconds)
    }
}