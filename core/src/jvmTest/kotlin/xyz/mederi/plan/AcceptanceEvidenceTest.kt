package xyz.mederi.plan

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.tools.PlanTools
import xyz.mederi.tools.ShellTools
import xyz.mederi.tools.VerifyTools
import xyz.mederi.tools.sandbox.CommandSandbox
import xyz.mederi.tools.sandbox.ExecutionPermissionMode
import xyz.mederi.tools.sandbox.ExecutionPermissions
import xyz.mederi.tools.sandbox.SandboxManager
import xyz.mederi.tools.sandbox.SandboxSettings
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcceptanceEvidenceTest {
    private val case = AcceptanceCase("V-01", "One row per ID", "Empty database", "id=m-001",
        "Submit twice and query the same ID", "Database row count", "1", "Duplicate insertion")

    @Test
    fun `a success marker is not evidence and observed data decides each case`() {
        assertEquals(AcceptanceVerdict.INSUFFICIENT_EVIDENCE,
            AcceptanceRun.evaluate(listOf(case), "PASS", 0).observations.single().verdict)
        val wrong = AcceptanceRun.evaluate(listOf(case), """{"V-01":2}""", 0)
        assertEquals("2", wrong.observations.single().actual)
        assertFalse(wrong.passed)
        assertTrue(AcceptanceRun.evaluate(listOf(case), """{"V-01":1}""", 0).passed)
    }

    @Test
    fun `report reads authoritative records and shows failed attempts and observed values`() {
        val root = createTempDirectory().toFile()
        try {
            val project = File(root, "project").apply { mkdir() }
            val authority = File(root, "host")
            val store = PlanStore(listOf(project.path), authorityRoot = authority.path)
            val wrong = AcceptanceRun.evaluate(listOf(case), """{"V-01":2}""", 0)
            val right = AcceptanceRun.evaluate(listOf(case), """{"V-01":1}""", 0)
            val task = task(0).copy(acceptanceCases = listOf(case), acceptanceRuns = listOf(wrong, right))
            store.save(plan(task))
            File(project, ".mederi/plans/plan_test/plan.json").writeText("{}")
            val report = assertNotNull(store.acceptanceReport("session", "plan_test"))
            val verdict = report.substringAfter("### V-01").substringBefore("### 取证与运行记录")
            assertTrue(verdict.contains("**预期值**　1\n- **实测值**　\"1\"\n- **判定**　**PASS**"))
            assertTrue(verdict.contains("第 2 次运行 [^run-${right.id}]"))
            assertTrue(report.contains("实测值与批准的预期值相等"))
            assertTrue(report.contains("实测 \"2\""))
            assertTrue(report.contains(right.id))
            assertTrue(report.contains(wrong.id))
            assertNull(store.acceptanceReport("another-session", "plan_test"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `overall acceptance waits for tasks and changed collector blocks retry`() = runBlocking {
        val root = createTempDirectory().toFile()
        try {
            val store = PlanStore(listOf(root.path))
            val collector = File(root, "collector.sh").apply { writeText("original") }
            val task = task(0).copy(acceptanceFiles = store.pinAcceptanceFiles(listOf(collector.name)))
            val overall = task(1).copy(overallAcceptance = true)
            store.save(plan(task, overall))
            val events = MutableSharedFlow<MederiEvent>(extraBufferCapacity = 8)
            val tool = VerifyTools("session", store, events, ShellTools(listOf(root.path))).VerifySubtaskTool()
            assertTrue(tool.execute(VerifyTools.VerifySubtaskArgs("plan_test", 1, "PASS", "")).contains("before"))
            collector.writeText("changed")
            val result = tool.execute(VerifyTools.VerifySubtaskArgs("plan_test", 0, "PASS", ""))
            assertTrue(result.contains("BLOCKED"))
            val saved = assertNotNull(store.load("plan_test")).subtasks.first()
            assertEquals(SubtaskStatus.FAILED, saved.status)
            assertEquals(AcceptanceVerdict.BLOCKED, saved.acceptanceRuns.single().observations.single().verdict)

            val planTools = PlanTools("session", AgentMode.AUTONOMOUS, store,
                PlanApprovalRequester("session", events), Notebook(listOf(root.path)), events)
            val update = planTools.UpdateVerificationTool().execute(
                PlanTools.UpdateVerificationArgs("plan_test", 0, "echo PASS", reason = "changed")
            )
            assertTrue(update.startsWith("Error"))
            assertEquals(task.verification, store.load("plan_test")?.subtasks?.first()?.verification)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `actual collector mismatch is retained and retry with original contract passes`() = runBlocking {
        val root = createTempDirectory().toFile()
        try {
            val store = PlanStore(listOf(root.path))
            val data = File(root, "result.json").apply { writeText("""{"V-0":2}""") }
            val directories = listOf(root.path)
            val sandbox = CommandSandbox(directories, emptyList())
            val command = if (sandbox.shell == "cmd") "type result.json" else "cat result.json"
            val task = task(0).copy(verification = VerificationSpec(command = command),
                overallAcceptance = true, targetFiles = listOf("result.json"))
            store.save(plan(task))
            val events = MutableSharedFlow<MederiEvent>(extraBufferCapacity = 8)
            val permissions = SandboxManager().apply {
                updateSettings("project", SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS))
            }
            val shell = ShellTools(directories, executionPermissions = ExecutionPermissions(
                permissions, directories, "session", projectId = "project"))
            val tool = VerifyTools("session", store, events, shell).VerifySubtaskTool()
            val args = VerifyTools.VerifySubtaskArgs("plan_test", 0, "PASS", "")
            assertTrue(tool.execute(args).contains("observed=2"))
            assertEquals(SubtaskStatus.FAILED, store.load("plan_test")?.subtasks?.single()?.status)
            data.writeText("""{"V-0":1}""")
            assertTrue(tool.execute(args).contains("observed=1"))
            val archived = store.listBySession("session").single()
            assertEquals(PlanStatus.COMPLETED, archived.status)
            assertEquals(listOf(AcceptanceVerdict.FAIL, AcceptanceVerdict.PASS),
                archived.subtasks.single().acceptanceRuns.map { it.observations.single().verdict })
            assertTrue(File(root, ".mederi/plans-done/plan_test/acceptance-report.md").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `manual cases need explicit signed observations and reject agent verification`() = runBlocking {
        val root = createTempDirectory().toFile()
        try {
            val store = PlanStore(listOf(root.path))
            val manual = task(0).copy(acceptanceCases = listOf(case.copy(manual = true)),
                verification = VerificationSpec(command = ""), overallAcceptance = true)
            store.save(plan(manual))
            val tool = VerifyTools("session", store, MutableSharedFlow()).VerifySubtaskTool()
            assertTrue(tool.execute(VerifyTools.VerifySubtaskArgs("plan_test", 0, "PASS", "trust me")).contains("human operator"))
            assertNull(store.recordManualAcceptance("other", "plan_test", case.id, "1", "row count", "Reviewer"))
            assertNull(store.recordManualAcceptance("session", "plan_test", "V-missing", "1", "row count", "Reviewer"))
            val failed = assertNotNull(store.recordManualAcceptance("session", "plan_test", case.id, "2", "query 2", "Reviewer"))
            assertEquals(SubtaskStatus.FAILED, failed.subtasks.single().status)
            val completed = assertNotNull(store.recordManualAcceptance("session", "plan_test", case.id, "1", "query 1", "Reviewer"))
            assertEquals(PlanStatus.COMPLETED, completed.status)
            val report = assertNotNull(store.acceptanceReport("session", "plan_test"))
            assertTrue(report.contains("人工签认：Reviewer"))
            assertTrue(report.contains("query 2"))
            assertTrue(report.contains("query 1"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun task(index: Int) = Subtask(
        index, "Task $index", planDetail = "Inspect persisted data",
        verification = VerificationSpec(command = "echo PASS"),
        acceptanceCases = listOf(case.copy(id = "V-$index"))
    )

    private fun plan(vararg tasks: Subtask) = Plan(
        id = "plan_test", title = "Acceptance", summary = "", sessionId = "session",
        status = PlanStatus.APPROVED, createdAt = "2026-11-06T00:00:00Z",
        agentMode = AgentMode.AUTONOMOUS, subtasks = tasks.toList()
    )
}
