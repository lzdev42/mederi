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
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * update_verification 工具直测——构造临时 planStore + FAILED/COMPLETED 子任务，
 * 调 update_verification，断言校验拒绝、命令改写、变更历史追加、cwd/timeout 流转。
 *
 * 构造方式参考 ConvergePlanMechanismTest（临时目录、planStore、planTools、eventBus、makePlan）。
 */
class UpdateVerificationTest {

    private val tmpDir = File.createTempFile("mederi-updver-test", "").apply {
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

    private fun makePlan(
        subtaskStatus: SubtaskStatus = SubtaskStatus.FAILED,
        command: String = "old cmd"
    ): Plan {
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
                    verification = VerificationSpec(command = command),
                    dependsOn = emptyList(),
                    parallelizable = false
                )
            )
        )
        planStore.save(plan)
        return plan
    }

    private fun tool() = planTools.UpdateVerificationTool()

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `update_verification rejects blank reason`() = runBlocking {
        val plan = makePlan()
        // command 非空且 ASCII，专门触发 reason 空白校验
        val result = tool().execute(PlanTools.UpdateVerificationArgs(
            planId = plan.id,
            subtaskIndex = 0,
            command = "test -f file.txt",
            reason = ""
        ))
        assertTrue(result.startsWith("Error"), "Expected error: $result")
        assertTrue(result.contains("reason", ignoreCase = true), "Expected 'reason' in error: $result")
    }

    @Test
    fun `update_verification rejects CJK in command`() = runBlocking {
        val plan = makePlan()
        // 现有 CJK 校验在 command 上（reason 也有，但 command 先检）；传中文 command 触发 ASCII 拒绝
        val result = tool().execute(PlanTools.UpdateVerificationArgs(
            planId = plan.id,
            subtaskIndex = 0,
            command = "检查文件",
            reason = "why"
        ))
        assertTrue(result.startsWith("Error"), "Expected error: $result")
        assertTrue(result.contains("ASCII"), "Expected 'ASCII' in error: $result")
    }

    @Test
    fun `update_verification rejects COMPLETED subtask`() = runBlocking {
        val plan = makePlan(subtaskStatus = SubtaskStatus.COMPLETED)
        val result = tool().execute(PlanTools.UpdateVerificationArgs(
            planId = plan.id,
            subtaskIndex = 0,
            command = "test -f file.txt",
            reason = "fix wrong command"
        ))
        assertTrue(result.startsWith("Error"), "Expected error: $result")
        assertTrue(result.contains("COMPLETED"), "Expected 'COMPLETED' in error: $result")
    }

    @Test
    fun `FAILED subtask rewrite appends change and keeps old command`() = runBlocking {
        val plan = makePlan(command = "old cmd")
        val result = tool().execute(PlanTools.UpdateVerificationArgs(
            planId = plan.id,
            subtaskIndex = 0,
            command = "new cmd",
            reason = "old command counted comments as false-positives"
        ))
        assertTrue(result.contains("Updated verification"), "Expected success: $result")

        val updated = planStore.load(plan.id)
        assertNotNull(updated)
        val st = updated.subtasks[0]
        assertEquals("new cmd", st.verification.command)
        assertEquals(1, st.verificationChanges.size)
        val change = st.verificationChanges[0]
        // append-only：保存完整旧/新契约（含预期字段），信息零销毁
        assertEquals("old cmd", change.oldSpec.command, "old contract must be preserved")
        assertEquals("new cmd", change.newSpec.command)
        assertTrue(change.reason.isNotBlank(), "reason must be non-blank in audit trail")
        assertEquals("old command counted comments as false-positives", change.reason)
    }

    @Test
    fun `multiple rewrites accumulate in verificationChanges`() = runBlocking {
        val plan = makePlan(command = "cmd v0")
        tool().execute(PlanTools.UpdateVerificationArgs(
            planId = plan.id, subtaskIndex = 0,
            command = "cmd v1", reason = "first fix"
        ))
        tool().execute(PlanTools.UpdateVerificationArgs(
            planId = plan.id, subtaskIndex = 0,
            command = "cmd v2", reason = "second fix"
        ))

        val updated = planStore.load(plan.id)
        assertNotNull(updated)
        val st = updated.subtasks[0]
        assertEquals("cmd v2", st.verification.command, "latest command must win")
        assertEquals(2, st.verificationChanges.size, "two changes must accumulate")
        assertEquals("cmd v0", st.verificationChanges[0].oldSpec.command)
        assertEquals("cmd v1", st.verificationChanges[0].newSpec.command)
        assertEquals("cmd v1", st.verificationChanges[1].oldSpec.command)
        assertEquals("cmd v2", st.verificationChanges[1].newSpec.command)
    }

    @Test
    fun `cwd and timeout flow through to verification`() = runBlocking {
        val plan = makePlan(command = "old cmd")
        val result = tool().execute(PlanTools.UpdateVerificationArgs(
            planId = plan.id,
            subtaskIndex = 0,
            command = "test -f file.txt",
            cwd = "sub/dir",
            timeoutSeconds = 120,
            reason = "needs longer timeout in subdir"
        ))
        assertTrue(result.contains("Updated verification"), "Expected success: $result")

        val updated = planStore.load(plan.id)
        assertNotNull(updated)
        val ver = updated.subtasks[0].verification
        assertEquals("sub/dir", ver.cwd, "cwd must flow through")
        assertEquals(120, ver.timeoutSeconds, "timeoutSeconds must flow through")
    }
}
