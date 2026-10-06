package xyz.mederi.tools

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.plan.Notebook
import xyz.mederi.plan.Plan
import xyz.mederi.plan.PlanApprovalRequester
import xyz.mederi.plan.PlanStore
import xyz.mederi.plan.PlanStatus
import xyz.mederi.plan.Subtask
import xyz.mederi.plan.SubtaskStatus
import xyz.mederi.plan.VerificationSpec
import xyz.mederi.tools.PlanTools
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * converge_plan 机制直测——不依赖模型行为（端到端测试 flaky：agnes 有时不调
 * converge_plan 而直接自己修）。本测试直接构造已批准计划 + 已 FAIL 的子任务，
 * 调 converge_plan，断言补救子任务被追加到 plan JSON。
 *
 * 同时测 verify_subtask PASS 归档机制（不经过模型，直接调工具）。
 */
class ConvergePlanMechanismTest {

    private val tmpDir = File.createTempFile("mederi-conv-test", "").apply {
        delete(); mkdirs()
        File(this, ".mederi/plans").mkdirs()
    }

    private val planStore = PlanStore(listOf(tmpDir.absolutePath))

    private val eventBus = MutableSharedFlow<MederiEvent>(replay = 64)

    private val planApprovalRequester = PlanApprovalRequester("sess_test", eventBus)

    private val planTools = PlanTools(
        sessionId = "sess_test",
        agentMode = AgentMode.AUTONOMOUS,
        planStore = planStore,
        planApprovalRequester = planApprovalRequester,
        notebook = Notebook(listOf(tmpDir.absolutePath)),
        eventBus = eventBus
    )

    private fun makePlan(subtaskStatus: SubtaskStatus = SubtaskStatus.FAILED): Plan {
        val plan = Plan(
            id = "plan_${UUID.randomUUID().toString().take(8)}",
            title = "test plan",
            summary = "test",
            sessionId = "sess_test",
            overview = "test overview",
            status = PlanStatus.IN_PROGRESS,
            createdAt = "2026-09-08T00:00:00Z",
            agentMode = AgentMode.AUTONOMOUS,
            subtasks = listOf(
                Subtask(
                    index = 0,
                    name = "subtask 0",
                    status = subtaskStatus,
                    planDetail = "do something",
                    spec = "spec content",
                    targetFiles = listOf("file.txt"),
                    verification = VerificationSpec(command = "test -f file.txt"),
                    // executor 完成时记录了改动文件（规则 2 空产出门禁：非空即放行 PASS）
                    executorTouchedFiles = listOf("file.txt"),
                    dependsOn = emptyList(),
                    parallelizable = false
                )
            )
        )
        planStore.save(plan)
        return plan
    }

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `converge_plan appends remediation subtask to plan`() = runBlocking {
        val plan = makePlan()
        val convergeTool = planTools.ConvergePlanTool()

        val result = convergeTool.execute(PlanTools.ConvergePlanArgs(
            planId = plan.id,
            remediationSubtasks = listOf(PlanTools.SubtaskArg(
                name = "fix permission",
                planDetail = "chmod u+w then rewrite",
                targetFiles = listOf("file.txt"),
                verification = "test -w file.txt",
                dependsOn = listOf(0)
            ))
        ))

        assertTrue(result.contains("appended", ignoreCase = true), "Expected 'appended': $result")

        val updated = planStore.load(plan.id)
        assertNotNull(updated)
        assertEquals(2, updated.subtasks.size)
        // 原子任务仍 FAILED
        assertEquals(SubtaskStatus.FAILED, updated.subtasks[0].status)
        // 补救子任务在 index 1，PENDING
        val remediation = updated.subtasks[1]
        assertEquals(SubtaskStatus.PENDING, remediation.status)
        assertEquals("fix permission", remediation.name)
        assertEquals(listOf(0), remediation.dependsOn)
    }

    @Test
    fun `converge_plan rejects empty remediation`() = runBlocking {
        val plan = makePlan()
        val result = planTools.ConvergePlanTool().execute(PlanTools.ConvergePlanArgs(
            planId = plan.id,
            remediationSubtasks = emptyList()
        ))
        assertTrue(result.startsWith("Error"), "Expected error: $result")
    }

    @Test
    fun `converge_plan rejects nonexistent plan`() = runBlocking {
        val result = planTools.ConvergePlanTool().execute(PlanTools.ConvergePlanArgs(
            planId = "plan_nonexistent",
            remediationSubtasks = listOf(PlanTools.SubtaskArg(
                name = "x", verification = "v"
            ))
        ))
        assertTrue(result.startsWith("Error"), "Expected error: $result")
    }

    @Test
    fun `verify_subtask PASS archives plan when all complete`() = runBlocking {
        val plan = makePlan(SubtaskStatus.PENDING)
        // verify_subtask 现在无条件执行验证命令——先创建 file.txt 让 `test -f file.txt` 真实通过
        File(tmpDir, "file.txt").writeText("created by test")
        // 传真实 ShellTools：验证器需要执行命令（2026-09-24 无条件执行改造）
        val verifyTools = xyz.mederi.tools.VerifyTools(
            sessionId = "sess_test",
            planStore = planStore,
            eventBus = eventBus,
            shellTools = xyz.mederi.tools.ShellTools(listOf(tmpDir.absolutePath))
        )
        val result = verifyTools.VerifySubtaskTool().execute(
            xyz.mederi.tools.VerifyTools.VerifySubtaskArgs(
                planId = plan.id,
                subtaskIndex = 0,
                status = "PASS",
                evidence = "test passed"
            )
        )

        assertTrue(result.contains("archived"), "Expected 'archived': $result")
        // 归档后 plan 从 plans/ 移到 plans-done/——planStore.load 只查 plans/，归档后返回 null
        assertTrue(File(tmpDir, ".mederi/plans-done/${plan.id}/plan.json").exists(),
            "Plan not in plans-done/{planId}/")
        // 从 plans-done/ 直接读 JSON 验证状态
        val archivedJson = File(tmpDir, ".mederi/plans-done/${plan.id}/plan.json").readText()
        assertTrue(archivedJson.contains("\"COMPLETED\""), "Plan status not COMPLETED in archived JSON")
        assertTrue(archivedJson.contains("\"COMPLETED\"") && archivedJson.contains("subtask 0"),
            "Subtask not COMPLETED in archived JSON")
    }
}
