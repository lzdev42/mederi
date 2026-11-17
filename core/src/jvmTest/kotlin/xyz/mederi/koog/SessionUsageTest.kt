package xyz.mederi.koog

import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.SessionUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionUsageTest {
    @Test
    fun countsNonErrorResponsesIndependentlyOfReportedTokens() {
        val parent = assistant("parent-step").copy(
            sessionId = "parent", modelId = "main", inputTokens = 100, cachedTokens = 80,
            modelRequestCompleted = true, finishReason = "tool_calls",
        )
        val child = assistant("child-step").copy(
            sessionId = "child", modelId = "main", inputTokens = 200, cachedTokens = 90,
            modelRequestCompleted = true, finishReason = "stop",
        )
        val records = listOf(
            xyz.mederi.domain.model.ModelRequestRecord("parent-step", "parent", "parent", parent.createdAt, parent),
            xyz.mederi.domain.model.ModelRequestRecord("child-step", "parent", "child", child.createdAt, child),
            xyz.mederi.domain.model.ModelRequestRecord("interrupted", "parent", "child", child.createdAt),
            xyz.mederi.domain.model.ModelRequestRecord(
                "compaction", "parent", "parent", parent.createdAt,
                parent.copy(id = "compaction", modelRequestPurpose = "compaction", inputTokens = 50, cachedTokens = 0),
            ),
            xyz.mederi.domain.model.ModelRequestRecord(
                "no-tokens", "parent", "child", child.createdAt,
                child.copy(id = "no-tokens", inputTokens = null, cachedTokens = null, finishReason = "length"),
            ),
            xyz.mederi.domain.model.ModelRequestRecord(
                "error", "parent", "child", child.createdAt,
                child.copy(id = "error", inputTokens = 20, cachedTokens = null, finishReason = "error"),
            ),
            xyz.mederi.domain.model.ModelRequestRecord(
                "missing-end", "parent", "child", child.createdAt,
                child.copy(id = "missing-end", inputTokens = 10, cachedTokens = null, finishReason = null),
            ),
        )
        val usage = SessionUsage.fromMessages(listOf(parent), records)
        assertEquals(4, usage.requestCount)
        assertEquals(380L, usage.totals.inputTokens)
        assertEquals(6, usage.totals.usageCoverageRequests)
        assertEquals(170L, usage.totals.cacheReadTokens)
        assertEquals(3, usage.totals.cacheHitSample?.requestCount)
        assertEquals(parent, usage.latestByModel.values.single())
        assertEquals(parent, usage.latest)
        assertEquals(4, SessionUsage.fromMessages(emptyList(), records).requestCount)
    }
    @Test
    fun explicitZeroOnColdWriteContributesToSessionDenominator() {
        val cold = assistant("cold").copy(inputTokens = 1000, cachedTokens = 0, cacheWriteTokens = 1000)
        val warm = assistant("warm").copy(inputTokens = 1000, cachedTokens = 800)
        val sample = SessionUsage.fromMessages(listOf(cold, warm)).totals.cacheHitSample
        assertEquals(SessionUsage.CacheHitSample(2000, 800, 1000, 2, 0.0, 0.8), sample)
    }
    @Test
    fun aggregatesReportedCountsSeparatelyFromEligibleCacheSamples() {
        val history = listOf(
            assistant("hit").copy(inputTokens = 1000, cachedTokens = 800, cacheWriteTokens = 100),
            assistant("unreported").copy(inputTokens = 8000),
            assistant("miss").copy(inputTokens = 100, cachedTokens = 0, cacheWriteTokens = 0),
            assistant("invalid").copy(inputTokens = 100, cachedTokens = 95, cacheWriteTokens = 20),
            assistant("partial").copy(inputTokens = 1000, cachedTokens = 900, modelRequestCompleted = false),
        )

        val usage = SessionUsage.fromMessages(history)
        assertEquals(4, usage.requestCount)
        assertEquals(9200L, usage.totals.inputTokens)
        assertEquals(800L, usage.totals.cacheReadTokens)
        assertEquals(120L, usage.totals.cacheWriteTokens)
        assertNull(usage.totals.outputTokens)
        assertEquals(SessionUsage.CacheHitSample(1100, 800, 100, 2, 0.0, 0.8), usage.totals.cacheHitSample)
        assertEquals(usage.totals, usage.groups.single().totals)
        assertEquals(history.dropLast(1), usage.groups.single().requests)
        assertNull(SessionUsage.cacheHitSample(history[3]))
        assertNull(SessionUsage.cacheHitSample(history[4]))
    }

    @Test
    fun groupsHistoricalRequestRoutesWithoutReassigningLegacyRecords() {
        val first = assistant("a").copy(inputTokens = 100, cachedTokens = 50)
        val history = listOf(
            first,
            first.copy(id = "b", providerEndpoint = "https://new.example/responses"),
            first.copy(id = "c", providerModelId = "different-model"),
            first.copy(id = "d", reasoningLevel = "LOW"),
            first.copy(id = "e", modelId = "local-alias", providerName = "Renamed", modelName = "New display name"),
            first.copy(
                id = "legacy",
                providerId = null,
                modelId = null,
                providerEndpoint = null,
                providerModelId = null,
                reasoningLevel = null,
                modelRequestCompleted = null,
            ),
        )

        val usage = SessionUsage.fromMessages(history)
        assertEquals(5, usage.groups.size)
        assertEquals(listOf("a", "e"), usage.groups.first().requests.map { it.id })
        assertEquals("Renamed", usage.groups.first().providerName)
        assertEquals("New display name", usage.groups.first().modelName)
        assertEquals(usage.totals.cacheReadTokens, usage.groups.sumOf { it.totals.cacheReadTokens ?: 0 })
        assertNull(usage.groups.last().key.providerId)
        assertNull(usage.groups.last().key.providerModelId)
    }

    @Test
    fun latestContextResetsAtSummaryWhileSelectedModelHistoryRemains() {
        val reported = assistant("older").copy(inputTokens = 100, cachedTokens = 80)
        val unreported = assistant("latest").copy(providerId = "another-provider")
        val partial = assistant("partial").copy(modelRequestCompleted = false)
        val summary = reported.copy(id = "summary", role = MessageRole.SUMMARY)
        val unknownLegacy = assistant("legacy").copy(modelRequestCompleted = null)

        val beforeNextRequest = SessionUsage.fromMessages(listOf(reported, summary, partial, unknownLegacy))
        assertNull(beforeNextRequest.latest)
        assertEquals(reported, beforeNextRequest.latestByModel[SessionUsage.LocalModelKey("provider", "local-model")])
        val afterNextRequest = SessionUsage.fromMessages(listOf(reported, summary, unreported, partial, unknownLegacy))
        assertEquals(unreported, afterNextRequest.latest)
        assertEquals(reported, afterNextRequest.latestByModel[SessionUsage.LocalModelKey("provider", "local-model")])
        assertEquals(unreported, afterNextRequest.latestByModel[SessionUsage.LocalModelKey("another-provider", "local-model")])
        assertNull(SessionUsage.cacheHitSample(unreported))
    }

    private fun assistant(id: String) = Message(
        id = id,
        sessionId = "session",
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
        createdAt = "2026-11-13T08:00:00Z",
        providerId = "provider",
        providerName = "Provider",
        providerEndpoint = "https://example.test/responses",
        providerModelId = "upstream-model",
        modelId = "local-model",
        modelName = "Display name",
        reasoningLevel = "HIGH",
        modelRequestCompleted = true,
    )
}
