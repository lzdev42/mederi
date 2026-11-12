package xyz.mederi.plan

import kotlinx.serialization.SerializationException
import xyz.mederi.domain.model.AgentMode
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * plan-store-corrupt 验收：损坏的 plan.json 不得静默变 null。
 *
 * 旧行为：decodePlan 用 runCatching{}.getOrNull() 吞掉解析失败——load(损坏) 返回 null，
 * 调用方只能误报 "Plan not found"；真实损坏原因（JSON 错在哪）丢失。
 * 新行为：
 * - load(损坏) 抛 [SerializationException]（带文件路径上下文），如实报错不兜底；
 * - allPlans()（经 loadActive 暴露）逐目录容忍损坏：跳过坏目录、其余计划照常返回。
 */
class PlanStoreCorruptionTest {

    private val tmpDir = File.createTempFile("mederi-plan-corrupt-test", "").apply {
        delete(); mkdirs()
        File(this, ".mederi/plans").mkdirs()
    }

    private val planStore = PlanStore(listOf(tmpDir.absolutePath))

    private fun makePlan(): Plan = Plan(
        id = "plan_${UUID.randomUUID().toString().take(8)}",
        title = "t",
        summary = "s",
        sessionId = "sess_test",
        background = "b",
        problemAnalysis = "p",
        approach = "a",
        expectedOutcome = "e",
        testMethod = "t",
        status = PlanStatus.APPROVED,
        createdAt = "2026-09-08T00:00:00Z",
        agentMode = AgentMode.APPROVAL,
        subtasks = emptyList()
    )

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `load throws SerializationException for corrupt plan json`() {
        val corruptId = "plan_corrupt_load"
        File(tmpDir, ".mederi/plans/$corruptId").mkdirs()
        File(tmpDir, ".mederi/plans/$corruptId/plan.json").writeText("{ broken json")

        val e = assertFailsWith<SerializationException> {
            planStore.load(corruptId)
        }
        assertEquals(true, e.message!!.contains(corruptId), "error should carry file context: ${e.message}")
    }

    @Test
    fun `allPlans skips corrupt plan directory and keeps valid plans`() {
        val good = makePlan()
        planStore.save(good)
        val corruptId = "plan_corrupt01"
        File(tmpDir, ".mederi/plans/$corruptId").mkdirs()
        File(tmpDir, ".mederi/plans/$corruptId/plan.json").writeText("{ broken json")

        // loadActive 是 allPlans() 的公开出口：坏目录被跳过、正常计划照常返回
        val active = planStore.loadActive()
        assertNotNull(active, "valid plan must still be listed despite corrupt neighbour")
        assertEquals(good.id, active.id)
    }

    @Test
    fun `listBySession skips corrupt plan directory and keeps valid plans`() {
        val good = makePlan()
        planStore.save(good)
        val corruptId = "plan_corrupt02"
        File(tmpDir, ".mederi/plans/$corruptId").mkdirs()
        File(tmpDir, ".mederi/plans/$corruptId/plan.json").writeText("{ broken json")

        // listBySession 是列表聚合路径（loadBySession/voidActivePlans 复用）：
        // 一个坏 plan.json 不得挡住整个会话的计划列表——记日志后跳过，其余照常返回
        val plans = planStore.listBySession(good.sessionId)
        assertEquals(listOf(good.id), plans.map { it.id },
            "listBySession must tolerate corrupt neighbour plan.json and return valid plans")
    }
}
