package xyz.mederi.ui

import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CostSummary
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.core.contract.models.PlanSubtaskItem
import xyz.mederi.core.contract.models.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 计划子任务类工具行（verify_subtask / generate_spec / update_verification）的目标摘要：
 * - 必须显示子任务标题（只有 "#4" 用户看不出是哪个子任务），序号 1-based 与计划卡一致；
 * - 判定只取**计划投影里已记录的结果**，绝不显示模型入参里的 status（那是验证前声明值）。
 */
class PlanSubtaskToolTargetTest {

    private fun snapshot(status: String): ConversationSnapshot = ConversationSnapshot(
        conversation = Conversation(
            id = "conv_1",
            projectId = "proj_1",
            title = "标题计划",
            status = ConversationStatus.Working,
            createdAt = 0L,
            updatedAt = 0L,
        ),
        messages = emptyList(),
        tokenUsage = TokenUsage(),
        cost = CostSummary(),
        planApprovals = listOf(
            PlanApprovalRequest(
                id = "plan_1",
                conversationId = "conv_1",
                planPath = "/tmp/plan_1/plan.md",
                title = "标题计划",
                summary = "",
                subtaskCount = 6,
                status = "IN_PROGRESS",
                subtasks = listOf(
                    PlanSubtaskItem(index = 0, name = "初始化骨架", status = "COMPLETED"),
                    PlanSubtaskItem(index = 4, name = "构造元数据", status = status),
                ),
            )
        ),
    )

    private fun verifyInput(status: String = "PASS") = mapOf(
        "planId" to "plan_1",
        "subtaskIndex" to "4",
        "status" to status,
        "evidence" to "grep 到了新增的调用点",
    )

    @Test
    fun verifySubtaskShowsSubtaskTitleAndNeverDeclaredStatus() {
        // 模型声明 PASS，但计划投影里该子任务还没有判定 → 不能出现 (PASS)
        assertEquals("5「构造元数据」", probeToolTarget("verify_subtask", verifyInput("PASS"), snapshot("PENDING")))
        assertEquals("5「构造元数据」", probeToolTarget("verify_subtask", verifyInput("PASS"), snapshot("IN_PROGRESS")))
    }

    @Test
    fun verifySubtaskShowsRecordedVerdictAfterVerification() {
        assertEquals("5「构造元数据」(PASS)", probeToolTarget("verify_subtask", verifyInput("FAIL"), snapshot("COMPLETED")))
        assertEquals("5「构造元数据」(FAIL)", probeToolTarget("verify_subtask", verifyInput("PASS"), snapshot("FAILED")))
    }

    @Test
    fun specToolsShowSubtaskTitleWithoutVerdict() {
        // 子任务 0 已完成；spec 工具行仍只显示标题，不背验证判定
        assertEquals(
            "1「初始化骨架」",
            probeToolTarget("generate_spec", mapOf("planId" to "plan_1", "subtaskIndex" to "0"), snapshot("PENDING")),
        )
        assertEquals(
            "1「初始化骨架」",
            probeToolTarget("update_verification", mapOf("planId" to "plan_1", "subtaskIndex" to "0"), snapshot("PENDING")),
        )
    }

    @Test
    fun unresolvedPlanFallsBackToOrdinalWithoutFabrication() {
        // 计划不在投影里（例如尚未水合）：只给 1-based 序号，不编标题、不编判定
        assertEquals(
            "5",
            probeToolTarget("verify_subtask", mapOf("planId" to "plan_missing", "subtaskIndex" to "4"), snapshot("COMPLETED")),
        )
        // 子任务索引存在但计划里没有这个 index：同样只给序号
        assertEquals(
            "7",
            probeToolTarget("verify_subtask", mapOf("planId" to "plan_1", "subtaskIndex" to "6"), snapshot("COMPLETED")),
        )
        // 缺 subtaskIndex：不给目标（行标题退回无目标的“正在验证子任务…”），不猜子任务
        assertNull(probeToolTarget("verify_subtask", mapOf("planId" to "plan_1"), snapshot("COMPLETED")))
    }
}
