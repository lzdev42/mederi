package xyz.mederi.core.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.CostSummary
import xyz.mederi.core.contract.models.TokenUsage
import xyz.mederi.core.contract.models.LastRequestUsage

/**
 * LLM_REQUEST_COMPLETED 事件在 [SnapshotReducer] 上的行为：
 * 实时累加 requestCount、更新 lastRequestUsage，且不触发 refreshPage（落库对齐走另一条路）。
 */
class SnapshotReducerTest {

    private fun baseSnapshot(
        requestCount: Int = 0,
        lastRequestUsage: LastRequestUsage? = null
    ): ConversationSnapshot = ConversationSnapshot(
        conversation = Conversation(
            id = "s",
            projectId = "p",
            title = "t",
            status = ConversationStatus.Idle,
            createdAt = 0L,
            updatedAt = 0L,
        ),
        messages = emptyList<ChatMessage>(),
        tokenUsage = TokenUsage(),
        cost = CostSummary(),
        requestCount = requestCount,
        lastRequestUsage = lastRequestUsage,
    )

    @Test
    fun llmRequestCompleted_incrementsAndUpdatesUsage() {
        val initial = baseSnapshot(requestCount = 3, lastRequestUsage = null)
        val event = CoreEvent(
            type = CoreEventType.LLM_REQUEST_COMPLETED,
            sessionId = "s",
            payload = mapOf(
                "inputTokens" to "120",
                "outputTokens" to "30",
                "cachedTokens" to "80"
            ),
        )
        val updated = SnapshotReducer.apply(initial, event)
        assertEquals(4, updated.requestCount)
        val usage = updated.lastRequestUsage
        assertEquals(120L, usage?.inputTokens)
        assertEquals(30L, usage?.outputTokens)
        assertEquals(80L, usage?.cachedTokens)
    }

    @Test
    fun llmRequestCompleted_withoutCached_keepsNull() {
        val initial = baseSnapshot()
        val event = CoreEvent(
            type = CoreEventType.LLM_REQUEST_COMPLETED,
            sessionId = "s",
            payload = mapOf(
                "inputTokens" to "55",
                "outputTokens" to "10"
            ),
        )
        val updated = SnapshotReducer.apply(initial, event)
        assertEquals(1, updated.requestCount)
        val usage = updated.lastRequestUsage
        assertEquals(55L, usage?.inputTokens)
        assertEquals(10L, usage?.outputTokens)
        assertNull(usage?.cachedTokens, "payload 无 cachedTokens 应保持 null（不用 0 伪装）")
    }

    @Test
    fun applyWithRefresh_llmRequestCompleted_noRefresh() = runTest {
        var refreshCalled = false
        val initial = baseSnapshot()
        val event = CoreEvent(
            type = CoreEventType.LLM_REQUEST_COMPLETED,
            sessionId = "s",
            payload = mapOf(
                "inputTokens" to "120",
                "outputTokens" to "30",
                "cachedTokens" to "80"
            ),
        )
        val results = SnapshotReducer.applyWithRefresh(initial, event) {
            refreshCalled = true
            null
        }
        // LLM_REQUEST_COMPLETED 走 else 分支（实时）：只出一个快照、且不回查落库
        assertEquals(1, results.size)
        assertEquals(false, refreshCalled, "LLM_REQUEST_COMPLETED 不得触发 refreshPage（实时路径）")
        assertEquals(1, results.first().requestCount)
        assertEquals(80L, results.first().lastRequestUsage?.cachedTokens)
    }
}
