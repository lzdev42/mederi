package xyz.mederi.core.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.computeChatItems

/**
 * [MederiModelMapper.toUsageBackfill] 从历史 assistant 消息回填 requestCount / lastRequestUsage。
 */
class MederiModelMapperTest {

    private fun msg(role: MessageRole, input: Int? = null, output: Int? = null, cached: Int? = null) = Message(
        sessionId = "s",
        role = role,
        parts = listOf(MessagePart.Text("x")),
        createdAt = "2026-01-01T00:00:00Z",
        inputTokens = input,
        outputTokens = output,
        cachedTokens = cached,
    )

    @Test
    fun backfill_countsAllAssistantMessages() {
        val messages = listOf(
            msg(MessageRole.USER),
            msg(MessageRole.ASSISTANT, input = 10, output = 5),
            msg(MessageRole.USER),
            msg(MessageRole.ASSISTANT, input = 20, output = 8, cached = 3),
            msg(MessageRole.ASSISTANT), // 无 usage 的 assistant 仍计入 requestCount
        )
        val backfill = MederiModelMapper.toUsageBackfill(messages)
        assertEquals(3, backfill.requestCount, "requestCount = assistant 消息数（含无 usage 的）")
    }

    @Test
    fun backfill_lastUsageFromLastAssistantWithUsage() {
        val messages = listOf(
            msg(MessageRole.ASSISTANT, input = 100, output = 40),
            msg(MessageRole.ASSISTANT, input = 200, output = 60, cached = 50),
            msg(MessageRole.ASSISTANT), // 第 3 条无 usage
        )
        val backfill = MederiModelMapper.toUsageBackfill(messages)
        // lastRequestUsage 取"最后一条带 usage"的 assistant（第 2 条），inputTokens 对得上
        assertEquals(200L, backfill.lastRequestUsage?.inputTokens)
        assertEquals(60L, backfill.lastRequestUsage?.outputTokens)
        assertEquals(50L, backfill.lastRequestUsage?.cachedTokens)
        assertEquals(3, backfill.requestCount)
    }

    @Test
    fun backfill_noUsageNull() {
        val messages = listOf(
            msg(MessageRole.USER),
            msg(MessageRole.ASSISTANT),
            msg(MessageRole.ASSISTANT),
        )
        val backfill = MederiModelMapper.toUsageBackfill(messages)
        assertEquals(2, backfill.requestCount)
        assertNull(backfill.lastRequestUsage, "全无 usage → lastRequestUsage 为 null（不用 0 伪装）")
    }

    @Test
    fun toChatMessage_preservesCoreDeliveryAndPendingInputIdentity() {
        val message = msg(MessageRole.ASSISTANT).copy(
            id = "response_1", turnId = "turn_1", isFinalAnswer = true, workStartedAt = 1000L, workCompletedAt = 9000L,
        )
        val chat = MederiModelMapper.toChatMessage(message)
        assertEquals("response_1", chat.id)
        assertEquals("turn_1", chat.turnId)
        assertEquals(1000L, chat.workStartedAt)
        assertEquals(9000L, chat.workCompletedAt)
        assertEquals(true, chat.isFinalAnswer)
        val pending = MederiModelMapper.toChatMessage(msg(MessageRole.USER).copy(
            id = "user_1", turnId = "turn_1", pendingSteering = true,
        ))
        assertEquals("user_1", pending.id)
        assertEquals(true, pending.pendingSteering)
    }

    // ── UI 剥离作用域与内部触发消息隐藏（ASSISTANT 不被剥离；hiddenFromUi 不落渲染块）──

    @Test
    fun toChatMessage_assistantTextIsNotStripped() {
        val body = "你看一下 `<user_intervention>example</user_intervention>` 这个标签"
        val message = Message(
            sessionId = "s",
            role = MessageRole.ASSISTANT,
            parts = listOf(MessagePart.Text(body)),
            createdAt = "2026-01-01T00:00:00Z",
        )
        val chat = MederiModelMapper.toChatMessage(message)
        val textBlock = chat.blocks.filterIsInstance<ChatBlock.Text>().single()
        assertEquals(body, textBlock.text, "ASSISTANT 原文不得被 USER 视图净化规则篡改")
    }

    @Test
    fun toChatMessage_hiddenFromUiClearsBlocksAndSeparatesTurnsWithoutUserBubble() {
        val internalTrigger = Message(
            id = "msg_plan_exec",
            sessionId = "s",
            role = MessageRole.USER,
            parts = listOf(MessagePart.Text("Plan 'x' (id=plan_1) has been approved by the user.")),
            createdAt = "2026-01-01T00:01:00Z",
            hiddenFromUi = true,
        )
        val mappedHidden = MederiModelMapper.toChatMessage(internalTrigger)
        assertTrue(mappedHidden.hiddenFromUi)
        assertTrue(mappedHidden.blocks.isEmpty(), "hiddenFromUi=true 投影不得携带可见渲染块")

        val planTurn = MederiModelMapper.toChatMessage(msg(MessageRole.ASSISTANT).copy(id = "a1"))
        val execTurn = MederiModelMapper.toChatMessage(msg(MessageRole.ASSISTANT).copy(id = "a2"))
        val items = computeChatItems(listOf(planTurn, mappedHidden, execTurn), isWorking = false, snapshot = null)
        assertEquals(0, items.count { it is ChatListItem.TextMessage && it.isUser }, "隐藏内部消息不得渲染为用户气泡")
        assertEquals(2, items.count { it is ChatListItem.Footer }, "隐藏触发消息仍应隔离前后两个 Assistant 轮次")
    }
}
