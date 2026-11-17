package xyz.mederi.core.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.dto.MessagesPage
import xyz.mederi.core.contract.models.CacheHitSample
import xyz.mederi.core.contract.models.CacheModel
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
    @Test
    fun cacheRateCountsWritesInTotalInput() {
        assertEquals(60.0, CacheHitSample(1000, 600, 300).percent)
        assertEquals(0.0, CacheHitSample(1000, 0, 1000).percent)
        assertNull(CacheHitSample(1000, 800, 300).percent)
    }

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
                "cachedTokens" to "80",
                "cacheWriteTokens" to "40",
            ),
        )
        val updated = SnapshotReducer.apply(initial, event)
        assertEquals(4, updated.requestCount)
        val usage = updated.lastRequestUsage
        assertEquals(120L, usage?.inputTokens)
        assertEquals(30L, usage?.outputTokens)
        assertEquals(80L, usage?.cachedTokens)
        assertEquals(40L, usage?.cacheWriteTokens)
        assertEquals(66.7, usage?.cacheHitPercent)
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
        assertNull(usage?.cacheWriteTokens)
        assertNull(usage?.cacheHitPercent)
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

    @Test
    fun durableCompletionReconcilesCountsAndDoesNotReplaceStreamingMessages() = runTest {
        val model = CacheModel("gateway", "https://example.com/v1/responses", "remote-model", "HIGH")
        val latest = LastRequestUsage(inputTokens = 120, cachedTokens = 80, cacheWriteTokens = 20, cacheModel = model)
        val aggregate = TokenUsage(input = 120, cacheRead = 80, cacheHitSample = CacheHitSample(120, 80, 20))
        val initial = baseSnapshot(requestCount = 1)
        val event = CoreEvent(
            type = CoreEventType.LLM_REQUEST_COMPLETED,
            sessionId = "s",
            messageId = "persisted",
            payload = mapOf("inputTokens" to "120", "cachedTokens" to "80", "cacheWriteTokens" to "20",
                "providerId" to model.providerId, "providerEndpoint" to model.endpoint.orEmpty(),
                "providerModelId" to model.modelId, "reasoningLevel" to model.variant.orEmpty()),
        )
        assertEquals(latest, SnapshotReducer.apply(initial, event).lastRequestUsage)
        val result = SnapshotReducer.applyWithRefresh(initial, event) {
            MessagesPage(emptyList(), aggregate, requestCount = 1, lastRequestUsage = latest)
        }.single()
        assertEquals(1, result.requestCount)
        assertEquals(aggregate, result.tokenUsage)
        assertEquals(initial.messages, result.messages)
        assertEquals(latest, result.lastRequestUsage)
    }

    @Test
    fun ledgerStartKeepsSuccessCountAndRepeatedCompletionDoesNotDoubleCount() = runTest {
        val initial = baseSnapshot(requestCount = 1)
        val start = CoreEvent(
            type = CoreEventType.LLM_REQUEST_STARTED,
            sessionId = "s",
            messageId = "request-2",
            payload = mapOf("executionSessionId" to "child"),
        )
        val inFlight = MessagesPage(emptyList(), TokenUsage(), requestCount = 1)
        val started = SnapshotReducer.applyWithRefresh(initial, start) { inFlight }.single()
        val repeated = SnapshotReducer.applyWithRefresh(started, start) { inFlight }.single()
        val complete = start.copy(type = CoreEventType.LLM_REQUEST_COMPLETED)
        val completed = SnapshotReducer.applyWithRefresh(repeated, complete) { inFlight.copy(requestCount = 2) }.single()
        assertEquals(1, started.requestCount)
        assertEquals(2, completed.requestCount)
        assertEquals(initial.messages, completed.messages)
        assertEquals(2, SnapshotReducer.applyWithRefresh(completed, complete) { null }.single().requestCount)
    }
}
