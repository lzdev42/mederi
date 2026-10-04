package xyz.mederi.infrastructure.koog

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mederi.domain.model.Message as MederiMessage
import xyz.mederi.domain.model.MessagePart as MederiMessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.MessageStatus
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.tools.estimateTokens

/**
 * [aiViewContextUsedTokens]（上下文占用唯一真理源）测试：
 * - A 无 SUMMARY 标记 → 用 API 报告的真实 inputTokens；
 * - B 窗口以 SUMMARY 开头 → 改走加权估算（不再用压缩前的 inputTokens）；
 * - C 空窗口 → 0。
 */
class ContextUsageTest {

    private fun mederiMessage(
        id: String,
        role: MessageRole,
        parts: List<MederiMessagePart>,
        inputTokens: Int? = null,
        createdAt: String = "2026-10-03T12:00:00Z"
    ): MederiMessage = MederiMessage(
        id = id,
        sessionId = "s1",
        role = role,
        parts = parts,
        status = MessageStatus.COMPLETED,
        createdAt = createdAt,
        inputTokens = inputTokens
    )

    // ── A 无 SUMMARY：走 API 真实值 ──────────────────────────────────────
    @Test
    fun testNoSummaryUsesReportedInputTokens() = runBlocking {
        val store = InMemoryHistoryStore()
        store.append("s1", mederiMessage("u1", MessageRole.USER, listOf(MederiMessagePart.Text("问题"))))
        store.append(
            "s1",
            mederiMessage(
                "a1", MessageRole.ASSISTANT,
                listOf(MederiMessagePart.Text("回答")),
                inputTokens = 12345
            )
        )
        store.append("s1", mederiMessage("u2", MessageRole.USER, listOf(MederiMessagePart.Text("下一个问题"))))

        assertEquals(12345, aiViewContextUsedTokens(store, "s1", true))
    }

    // ── B 窗口以 SUMMARY 开头：走加权估算，不用压缩前的 inputTokens ──────────
    @Test
    fun testSummaryHeadFallsBackToEstimate() = runBlocking {
        val store = InMemoryHistoryStore()
        store.append("s1", mederiMessage("u1", MessageRole.USER, listOf(MederiMessagePart.Text("问题"))))
        store.append(
            "s1",
            mederiMessage(
                "a1", MessageRole.ASSISTANT,
                listOf(MederiMessagePart.Text("回答")),
                inputTokens = 12345
            )
        )
        store.append(
            "s1",
            mederiMessage("s", MessageRole.SUMMARY, listOf(MederiMessagePart.Text("TLDR: 总结")))
        )
        store.append("s1", mederiMessage("u2", MessageRole.USER, listOf(MederiMessagePart.Text("下一个问题"))))

        val used = aiViewContextUsedTokens(store, "s1", true)

        // 核心断言：没有落回压缩前的旧值分支
        assertTrue(used != 12345, "SUMMARY 窗口应改走估算，实际返回了压缩前的 inputTokens: $used")
        // 自洽校验：等于对同一 AI 视图窗口做加权估算
        val expected = estimateTokens(
            KoogMessageMapper.toKoogMessages(
                HistoryStoreChatHistoryProvider.aiViewWindow(store, "s1"),
                true
            )
        )
        assertEquals(expected, used)
    }

    // ── C 空窗口 → 0 ────────────────────────────────────────────────────
    @Test
    fun testEmptyWindowReturnsZero() = runBlocking {
        val store = InMemoryHistoryStore()
        assertEquals(0, aiViewContextUsedTokens(store, "s1", true))
    }
}