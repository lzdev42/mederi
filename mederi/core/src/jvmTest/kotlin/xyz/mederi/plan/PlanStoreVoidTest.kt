package xyz.mederi.plan

import xyz.mederi.domain.model.AgentMode
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        title = "t", summary = "s", sessionId = sessionId, overview = "o",
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
        assertNotNull(voidDir.listFiles { f -> f.name.startsWith(plan.id) }, "作废双文件应入 plans-voided/")
        // 落盘的 json 状态应为 VOIDED
        val jsonFile = voidDir.listFiles { f -> f.name == "${plan.id}.json" }.first()
        val persisted = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(Plan.serializer(), jsonFile.readText())
        assertEquals(PlanStatus.VOIDED, persisted.status)
        // plans/ 下无残留
        val plansDir = File(tmpDir, ".mederi/plans")
        assertEquals(0, plansDir.listFiles { f -> f.name.startsWith(plan.id) }.size)
    }
}