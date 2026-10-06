package xyz.mederi.core.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
}
