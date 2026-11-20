package xyz.mederi.plan

import xyz.mederi.domain.model.AgentMode
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * 计划作废机制（P2）行为锁定，三条硬规则之二"同一会话只有最新计划可执行"：
 * - voidActivePlans：作废全部非终态计划（含 APPROVED/IN_PROGRESS），双文件移入 plans-voided/，返回 id 列表
 * - 已 COMPLETED 的计划不受作废影响（complete 是凭据，保留）
 * - 作废后 loadBySession/loadActive 只回最新非终态，不再看到作废残本
 * - loadBySession 按 createdAt 取最新（多份并列时不受目录乱序影响）
 */
class PlanStoreVoidTest {

    private val tmpDir = File.createTempFile("mederi-plan-void-test", "").apply {
        delete(); mkdirs()
        File(this, ".mederi/plans").mkdirs()
    }

    private val planStore = PlanStore(listOf(tmpDir.absolutePath))

    private fun makePlan(
        sessionId: String = "sess_test",
        status: PlanStatus = PlanStatus.PENDING_APPROVAL,
        createdAt: String = "2026-09-08T00:00:00Z"
    ): Plan = Plan(
        id = "plan_${UUID.randomUUID().toString().take(8)}",
        title = "t", summary = "s", sessionId = sessionId, background = "b", problemAnalysis = "p", approach = "a", expectedOutcome = "e", testMethod = "t",
        status = status, createdAt = createdAt, agentMode = AgentMode.APPROVAL,
        subtasks = emptyList()
    )

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `voidActivePlans voids every non-terminal plan and returns their ids`() {
        val pending = makePlan(status = PlanStatus.PENDING_APPROVAL)
        val approved = makePlan(status = PlanStatus.APPROVED)
        val inProgress = makePlan(status = PlanStatus.IN_PROGRESS)
        planStore.save(pending); planStore.save(approved); planStore.save(inProgress)

        val voided = planStore.voidActivePlans("sess_test")

        assertEquals(setOf(pending.id, approved.id, inProgress.id), voided.toSet())
        // 作废后三份 JSON 已移出 plans/
        listOf(pending.id, approved.id, inProgress.id).forEach { id ->
            assertEquals(null, planStore.load(id), "$id 应从 plans/ 移出（load 不可见）")
        }
    }

    @Test
    fun `completed plans are preserved (not voided)`() {
        val done = makePlan(status = PlanStatus.COMPLETED)
        val pending = makePlan(status = PlanStatus.PENDING_APPROVAL, createdAt = "2026-09-09T00:00:00Z")
        planStore.save(done); planStore.save(pending)

        val voided = planStore.voidActivePlans("sess_test")

        assertEquals(listOf(pending.id), voided)
        // 已完成计划留在 plans/ 且仍可 load
        val loaded = planStore.load(done.id)
        assertNotNull(loaded)
        assertEquals(PlanStatus.COMPLETED, loaded.status)
    }

    @Test
    fun `loadBySession returns latest non-terminal plan only`() {
        val older = makePlan(createdAt = "2026-09-08T00:00:00Z")
        val newer = makePlan(createdAt = "2026-09-10T00:00:00Z")
        planStore.save(older); planStore.save(newer)

        val active = planStore.loadBySession("sess_test")
        assertNotNull(active)
        assertEquals(newer.id, active.id, "应取 createdAt 最新的一份")
    }

    @Test
    fun `superseded draft cannot activate and original plan is preserved until approval`() {
        val original = makePlan(status = PlanStatus.IN_PROGRESS)
        planStore.save(original)
        val first = assertNotNull(planStore.submitDraft(
            makePlan(createdAt = "2026-09-09T00:00:00Z").copy(revisionReason = "First proposal")
        ))
        val latest = assertNotNull(planStore.submitDraft(
            makePlan(createdAt = "2026-09-10T00:00:00Z").copy(revisionReason = "Second proposal")
        ))

        assertEquals(listOf(first.plan.id), latest.voidedPlanIds)
        assertEquals(original.id, latest.plan.replacesPlanId)
        assertEquals(original.id, planStore.loadBySession(original.sessionId)?.id)
        assertNull(planStore.approveDraft(first.plan))

        val approval = assertNotNull(planStore.approveDraft(latest.plan))
        assertEquals(listOf(original.id), approval.voidedPlanIds)
        assertEquals(PlanStatus.APPROVED, planStore.loadBySession(original.sessionId)?.status)
        assertEquals(latest.plan.id, planStore.loadBySession(original.sessionId)?.id)
    }

    @Test
    fun `revisions preserve snapshots and only the document preview reads the previous one`() {
        val task = Subtask(0, "Retry", planDetail = "Implement the retry policy.", verification = VerificationSpec("true"))
        val original = makePlan(status = PlanStatus.IN_PROGRESS).copy(
            approach = "Retry three times.",
            subtasks = listOf(task.copy(spec = "Legacy execution details")),
        )
        val other = makePlan(sessionId = "other", status = PlanStatus.APPROVED)
        planStore.save(original)
        planStore.save(other)
        val replacement = makePlan(createdAt = "2026-09-10T00:00:00Z").copy(
            approach = "Retry twice.",
            subtasks = listOf(task),
        )
        assertFailsWith<IllegalArgumentException> { planStore.submitDraft(replacement) }
        val draft = assertNotNull(planStore.submitDraft(replacement.copy(revisionReason = "Limit the wait."))).plan
        assertEquals(original.id, planStore.loadBySession(original.sessionId)?.id)
        assertEquals(other.id, planStore.loadBySession(other.sessionId)?.id)
        val pendingPreview = planStore.planDocumentMarkdown(draft)
        assertTrue(pendingPreview.contains("## 修订记录"))
        assertTrue(pendingPreview.substringAfter("## 修订记录").contains("~~Retry three times"))
        assertTrue(pendingPreview.substringAfter("## 修订记录").contains("**Retry twice"))
        assertTrue(pendingPreview.contains("Limit the wait"))
        assertFalse(pendingPreview.contains("Legacy execution details"))

        val approved = assertNotNull(planStore.approveDraft(draft)).plan
        assertEquals(approved.id, planStore.loadBySession(original.sessionId)?.id)
        assertEquals(original.id, planStore.loadPlanForDocument(original.sessionId, original.id)?.id)
        assertNull(planStore.loadPlanForDocument(other.sessionId, original.id))
        assertEquals(pendingPreview, planStore.planDocumentMarkdown(approved))
        val bodyPath = assertNotNull(planStore.getPlanAbsolutePath(approved.id))
        assertFalse(File(bodyPath).readText().contains("## 修订记录"))

        val snapshot = File(File(bodyPath).parentFile, "proposal.md")
        val captured = snapshot.readText()
        val updated = assertNotNull(planStore.updatePlan(approved.id) {
            it.copy(
                status = PlanStatus.COMPLETED,
                subtasks = listOf(task.copy(spec = "Current execution details", status = SubtaskStatus.COMPLETED)),
            )
        })
        assertEquals(captured, snapshot.readText())
        planStore.archive(updated.id)
        val archivedPreview = planStore.planDocumentMarkdown(
            assertNotNull(planStore.loadPlanForDocument(updated.sessionId, updated.id))
        )
        assertTrue(archivedPreview.substringBefore("## 修订记录").contains("Current execution details"))
        assertEquals(pendingPreview.substringAfter("## 修订记录"), archivedPreview.substringAfter("## 修订记录"))
    }

    @Test
    fun `voided plans no longer surfaced by loadBySession loadActive`() {
        val p1 = makePlan(createdAt = "2026-09-08T00:00:00Z")
        val p2 = makePlan(createdAt = "2026-09-09T00:00:00Z")
        planStore.save(p1); planStore.save(p2)

        planStore.voidActivePlans("sess_test")

        assertNull(planStore.loadBySession("sess_test"), "作废后无活跃计划")
        assertNull(planStore.loadActive(), "作废后无全局活跃计划")
    }

    @Test
    fun `voided plan json status is VOIDED and files moved to plans-voided`() {
        val plan = makePlan(status = PlanStatus.APPROVED)
        planStore.save(plan)

        planStore.voidActivePlans("sess_test")

        val voidDir = File(tmpDir, ".mederi/plans-voided")
        // 新结构：一个计划一个目录，下挂 plan.json + plan.md
        val planVoidDir = File(voidDir, plan.id)
        assertTrue(planVoidDir.isDirectory, "作废计划目录应入 plans-voided/{planId}/")
        // 落盘的 json 状态应为 VOIDED
        val jsonFile = File(planVoidDir, "plan.json")
        val persisted = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(Plan.serializer(), jsonFile.readText())
        assertEquals(PlanStatus.VOIDED, persisted.status)
        // plans/ 下无残留目录
        val plansDir = File(tmpDir, ".mederi/plans")
        assertFalse(File(plansDir, plan.id).exists(), "plans/ 下不应残留 {planId} 目录")
    }
}
