package xyz.mederi.ui.chat

import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CostSummary
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.core.contract.models.TokenUsage
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.computeChatItems
import xyz.mederi.ui.createdPlansForTurn
import xyz.mederi.ui.planCallIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 计划审批卡锚定回归测试。
 *
 * 背景事故（真实会话 sess_6b1eadb5，2026-11-08）：write_plan 被连续调用 12 次——
 * 6 次校验失败 + 4 次探针 + 1 次失败后才成功，全部共用同一 title 且入参无 planId、
 * 输出无 Plan ID。旧逻辑按 title 匹配后跨轮去重保留**第一条**，审批卡被锚到了
 * 第一次校验失败的尝试后面；真正创建成功的轮次与对话末尾反而无卡，用户看到
 * "计划没渲染出来"。
 */
class PlanApprovalAnchoringTest {

    private val neutralContinued =
        "User did not approve or reject the plan; they continued the conversation. " +
            "The plan stays PENDING_APPROVAL — respond to their message, do not ask why."

    private fun assistant(id: String, blocks: List<ChatBlock>, final: Boolean? = false) = ChatMessage(
        id = id, conversationId = "conv_test", role = ChatRole.Assistant, blocks = blocks,
        createdAt = 1L, completedAt = 2L, parentMessageId = null, model = null, agent = null,
        isStreaming = false, turnId = "turn_$id", isFinalAnswer = final,
    )

    private fun user(id: String, text: String) = ChatMessage(
        id = id, conversationId = "conv_test", role = ChatRole.User,
        blocks = listOf(ChatBlock.Text(id = "b_$id", text = text)),
        createdAt = 1L, completedAt = 1L, parentMessageId = null, model = null, agent = null,
        isStreaming = false, turnId = null, isFinalAnswer = null,
    )

    /** 复刻 write_plan 调用块：入参 title（无 planId），输出按真实工具结果文本。 */
    private fun writePlanCall(id: String, title: String, output: String, planId: String? = null) =
        ChatBlock.ToolCall(
            id = id, name = "write_plan",
            state = ToolCallState.Completed(
                input = buildMap {
                    put("title", title)
                    planId?.let { put("planId", it) }
                },
                output = output,
            ),
        )

    private fun plan(id: String = "plan_a03fb2aa", title: String = "Obscura 内核浏览器一等公民化 + core 层 KBrowser 退出") =
        PlanApprovalRequest(
            id = id, conversationId = "conv_test", planPath = "/plans/$id/plan.md",
            title = title, summary = "s", subtaskCount = 8, status = "PENDING",
        )

    private fun snapshot(request: PlanApprovalRequest, pending: PlanApprovalRequest? = request) =
        ConversationState(
            conversation = Conversation("conv_test", "proj_1", "Title", ConversationStatus.WaitingUser, 1, 1),
            messages = emptyList(), tokenUsage = TokenUsage(), cost = CostSummary(),
            planApprovals = listOf(request), pendingPlanApproval = pending,
        )

    private fun planCards(items: List<ChatListItem>) = items.filterIsInstance<ChatListItem.PlanApproval>()

    @Test
    fun `failed attempts do not steal the card the successful creation call anchors it`() {
        val title = plan().title
        val request = plan()
        // 复刻事故形态：失败尝试（Error 输出，isError=false → Completed 态）与成功调用同 title
        val messages = listOf(
            user("u1", "开工"),
            assistant("a1", listOf(writePlanCall("w1", title, "Error: Plan validation failed — fix ALL of the following items, then retry write_plan once with the complete arguments: 1. Change ... action must be MODIFY, NEW, or DELETE (got \"ADD\")."))),
            user("u2", "继续"),
            assistant("a2", listOf(writePlanCall("w2", title, neutralContinued))),
        )
        val items = computeChatItems(messages, isWorking = false, snapshot = snapshot(request))

        val cards = planCards(items)
        assertEquals(1, cards.size, "必须恰好渲染一张审批卡")
        assertEquals(request.id, cards.single().request.id)
        val traces = items.filterIsInstance<ChatListItem.WorkTraceBlock>()
        assertEquals(2, traces.size)
        assertTrue(
            items.indexOf(cards.single()) > items.indexOf(traces.last()),
            "审批卡必须锚定在成功创建（最后一个工作过程）之后，而不是挂在最早的失败尝试后面",
        )
        assertEquals(cards.size, cards.map { it.key }.distinct().size, "卡片 key 不得重复（LazyColumn key 冲突）")
    }

    @Test
    fun `multiple successful attempts with same title anchor the card to the last one`() {
        val title = plan().title
        val request = plan(id = "plan_11111111", title = title)
        val messages = listOf(
            user("u1", "开工"),
            assistant("a1", listOf(writePlanCall("w1", title, "Plan created and auto-approved. Plan ID: plan_11111111. Use subagent(SPAWN, planId=..., subtaskIndex=...) to execute subtasks."))),
            user("u2", "改一下再提交"),
            assistant("a2", listOf(writePlanCall("w2", title, "Plan updated. Plan ID: plan_11111111.", planId = "plan_11111111"))),
        )
        val items = computeChatItems(messages, isWorking = false, snapshot = snapshot(request))

        val cards = planCards(items)
        assertEquals(1, cards.size)
        val traces = items.filterIsInstance<ChatListItem.WorkTraceBlock>()
        assertTrue(
            items.indexOf(cards.single()) > items.indexOf(traces.last()),
            "同计划多次成功创建/更新时，卡片必须锚定最后一次调用所在轮次",
        )
    }

    @Test
    fun `pendingPlanApproval tail fallback still renders when no creation call is findable`() {
        val request = plan()
        val messages = listOf(
            user("u1", "开工"),
            assistant("a1", listOf(ChatBlock.Text(id = "t1", text = "过程说明"))),
        )
        val items = computeChatItems(messages, isWorking = false, snapshot = snapshot(request))
        val cards = planCards(items)
        assertEquals(1, cards.size, "找不到创建调用时 pendingPlanApproval 末尾兜底必须保留")
        assertEquals(request.id, cards.single().request.id)
    }

    @Test
    fun `approved plan keeps its card anchored at the creation turn`() {
        val title = plan().title
        val request = plan().copy(status = "APPROVED")
        val messages = listOf(
            user("u1", "开工"),
            assistant("a1", listOf(writePlanCall("w1", title, neutralContinued))),
            user("u2", "批准了，继续干别的"),
            assistant("a2", listOf(ChatBlock.Text(id = "t2", text = "已开始执行"))),
        )
        // 批准后 pendingPlanApproval 清空，planApprovals 保留全生命周期投影
        val items = computeChatItems(messages, isWorking = false, snapshot = snapshot(request, pending = null))
        val cards = planCards(items)
        assertEquals(1, cards.size, "批准后的计划卡仍须渲染在创建轮次")
        val traces = items.filterIsInstance<ChatListItem.WorkTraceBlock>()
        assertTrue(
            items.indexOf(cards.single()) > items.indexOf(traces.first()) &&
                items.indexOf(cards.single()) < items.indexOf(traces.last()),
            "批准后的卡片应留在创建轮次（第一个过程块之后），不被末轮兜底抢走",
        )
    }

    @Test
    fun `planCallIdentity gates failed attempts and extracts ids from success outputs`() {
        // 校验失败（Error 前缀，isError=false）：无身份，不参与匹配
        assertNull(
            planCallIdentity(ToolCallState.Completed(mapOf("title" to "T"), "Error: Every task needs concrete acceptance cases with valid IDs, setup, input, steps, observation source, expected value and defect detected.")).first
        )
        // 参数解析失败（isError=true → Failed 态）：无身份
        assertNull(planCallIdentity(ToolCallState.Failed(mapOf("title" to "T"), "parse error")).first)
        // 成功路径：输出带 Plan ID
        assertEquals(
            "plan_abcd1234",
            planCallIdentity(ToolCallState.Completed(emptyMap(), "Plan approved. Plan ID: plan_abcd1234. Use subagent(SPAWN, planId=..., subtaskIndex=...) to execute subtasks.")).first,
        )
        // 成功但用户继续对话（无 Plan ID）：title 兜底必须生效
        val neutral = planCallIdentity(ToolCallState.Completed(mapOf("title" to "T"), neutralContinued))
        assertEquals(null, neutral.first)
        assertEquals("T", neutral.second)
        // 更新路径：入参 planId
        assertEquals(
            "plan_11111111",
            planCallIdentity(ToolCallState.Completed(mapOf("planId" to "plan_11111111", "title" to "T"), "Plan updated. Plan ID: plan_11111111.")).first,
        )
        // 挂起中（Running）：允许实时匹配
        assertEquals("T", planCallIdentity(ToolCallState.Running(mapOf("title" to "T"))).second)
    }

    @Test
    fun `createdPlansForTurn skips failed calls and matches via the successful one in the same turn`() {
        val title = plan().title
        val request = plan()
        val turn = listOf(
            assistant("a1", listOf(
                writePlanCall("w1", title, "Error: Plan validation failed — fix ALL of the following items."),
                writePlanCall("w2", title, neutralContinued),
                ChatBlock.ToolCall(id = "w3", name = "bash", state = ToolCallState.Completed(emptyMap(), "ok")),
            )),
        )
        val matched = createdPlansForTurn(turn, snapshot(request))
        assertEquals(listOf(request.id), matched.map { it.id }, "轮内失败调用必须被跳过，仅成功调用匹配")
    }
}
