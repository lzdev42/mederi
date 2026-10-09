package xyz.mederi.core.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole

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

    // ── UI_HIDDEN_MARKER 剥离的角色作用域（回归：ASSISTANT 字面提及标记不得被截断）──

    @Test
    fun toChatMessage_assistantTextWithLiteralMarkerIsNotTruncated() {
        // 回归：ASSISTANT 回复里字面提及 <<<NOT_FOR_UI>>> 时不得被 sanitize 截断。
        // 标记剥离只作用于 USER 消息（环境元数据附在用户消息尾部、内部指令消息以标记开头、
        // 工具边界插话用 <user_intervention> 包装——三者都是 USER 角色）。
        val body = "你看一下如何解决 `<<<NOT_FOR_UI>>>` 这个字符"
        val message = Message(
            sessionId = "s",
            role = MessageRole.ASSISTANT,
            parts = listOf(MessagePart.Text(body)),
            createdAt = "2026-01-01T00:00:00Z",
        )
        val chat = MederiModelMapper.toChatMessage(message)
        val textBlock = chat.blocks.filterIsInstance<ChatBlock.Text>().single()
        assertEquals(body, textBlock.text, "ASSISTANT 原文不得因字面提及标记而被截断")
    }

    @Test
    fun toChatMessage_userTextWithEnvMarkerIsStripped() {
        // 回归守卫：USER 消息尾部的环境元数据块仍须被剥离（上一修复不得误伤 USER 路径）。
        val userText = "帮我看下这个 bug"
        val message = Message(
            sessionId = "s",
            role = MessageRole.USER,
            parts = listOf(MessagePart.Text(userText + "\n<<<NOT_FOR_UI>>>\nNOTE FOR AI (hidden): UTC now")),
            createdAt = "2026-01-01T00:00:00Z",
        )
        val chat = MederiModelMapper.toChatMessage(message)
        val textBlock = chat.blocks.filterIsInstance<ChatBlock.Text>().single()
        assertEquals(userText, textBlock.text, "USER 消息尾部的 <<<NOT_FOR_UI>>> 环境块仍须被剥离")
    }
}
