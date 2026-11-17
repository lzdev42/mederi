package xyz.mederi.koog

import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mederi.domain.model.Message as StoredMessage
import xyz.mederi.infrastructure.koog.KoogMessageMapper
import xyz.mederi.infrastructure.koog.MessageDiagnostics
import xyz.mederi.infrastructure.koog.TurnIncrementalPersister
import xyz.mederi.infrastructure.koog.collectAssistantPreservingPartial
import xyz.mederi.infrastructure.koog.extractUsagePayload
import xyz.mederi.infrastructure.koog.recordRequestUsage
import xyz.mederi.infrastructure.koog.usageProviderEndpoint
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemoryModelRequestStore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TurnExecutorEventTest {

    @Test
    fun extractUsagePayload_returnsTokens() {
        val meta = ResponseMetaInfo.create(
            KoogClock.System,
            totalTokensCount = 150,
            inputTokensCount = 100,
            outputTokensCount = 50,
            metadata = buildJsonObject {
                put("cachedTokens", 80)
                put("cacheWriteTokens", 0)
            },
        )
        val message = KoogMessageMapper.fromKoogMessage("session", Message.Assistant("reply", meta))
        val payload = extractUsagePayload(message)
        assertEquals(
            mapOf("inputTokens" to "100", "outputTokens" to "50", "cachedTokens" to "80", "cacheWriteTokens" to "0"),
            payload,
        )
    }

    @Test
    fun cacheUsageSurvivesHistoryPayloadRoundTrip() {
        val meta = ResponseMetaInfo.create(
            KoogClock.System,
            inputTokensCount = 100,
            metadata = buildJsonObject {
                put("cachedTokens", 0)
                put("cacheWriteTokens", 80)
            },
        )
        val message = KoogMessageMapper.fromKoogMessage("session", Message.Assistant("reply", meta))
        val restored = Json.decodeFromString<StoredMessage>(Json.encodeToString(message))
        val replayed = KoogMessageMapper.toKoogMessage(restored) as Message.Assistant
        assertEquals(0, restored.cachedTokens)
        assertEquals(80, restored.cacheWriteTokens)
        assertEquals(extractUsagePayload(message), extractUsagePayload(KoogMessageMapper.fromKoogMessage("session", replayed)))
    }

    @Test
    fun extractUsagePayload_keepsReportedOutputWhenInputIsAbsent() {
        val meta = ResponseMetaInfo.create(
            KoogClock.System,
            inputTokensCount = null,
            outputTokensCount = 50,
        )
        val message = KoogMessageMapper.fromKoogMessage("session", Message.Assistant("reply", meta))
        assertEquals(mapOf("outputTokens" to "50"), extractUsagePayload(message))
    }

    @Test
    fun extractUsagePayload_inputOnly_omitsAbsent() {
        val meta = ResponseMetaInfo.create(
            KoogClock.System,
            inputTokensCount = 100,
        )
        val message = KoogMessageMapper.fromKoogMessage("session", Message.Assistant("reply", meta))
        assertEquals(mapOf("inputTokens" to "100"), extractUsagePayload(message))
    }

    @Test
    fun usageEndpointIdentifiesRouteAndStripsCredentials() {
        val base = "HTTPS://name:secret@Example.test:443/Deployment/V1/?key=secret#fragment"
        assertEquals("https://example.test/Deployment/V1/chat/completions", usageProviderEndpoint(base, ProviderType.OPENAI_CHAT))
        assertEquals("https://example.test/Deployment/V1/responses", usageProviderEndpoint(base, ProviderType.OPENAI_RESPONSES))
        assertEquals("http://[::1]:8080/v1beta/models", usageProviderEndpoint("http://[::1]:8080/v1beta", ProviderType.GOOGLE))
        assertNull(usageProviderEndpoint("file:///v1", ProviderType.OPENAI_CHAT))
        assertNull(usageProviderEndpoint("https://invalid host", ProviderType.OPENAI_CHAT))
    }

    @Test
    fun completedUsageMatchesStoredAssistantAndSkipsPartials() = runBlocking {
        val history = InMemoryHistoryStore()
        val requests = InMemoryModelRequestStore()
        val persister = TurnIncrementalPersister(
            sessionId = "session",
            historyStore = history,
            diagnostics = MessageDiagnostics(
                providerId = "provider",
                providerName = "Provider",
                providerEndpoint = "https://example.test/v1/responses",
                modelId = "local-model",
                providerModelId = "upstream-model",
                modelsDevKey = "catalog",
                modelName = "Upstream",
                reasoningLevel = "HIGH",
            ),
            modelRequestStore = requests,
        )
        val response = Message.Assistant("reply", ResponseMetaInfo.create(KoogClock.System, inputTokensCount = 100))
        val completed = persister.beginResponse()
        persister.recordResponseUsage(completed, StreamFrame.End("stop", response.metaInfo))
        persister.persistAssistant(response.copy(id = completed))
        val partial = persister.beginResponse()
        persister.persistAssistant(response.copy(id = partial), requestCompleted = false)

        val completedUsage = requireNotNull(requests.list("session").first().usage)
        assertEquals(extractUsagePayload(history.load("session").first()), extractUsagePayload(completedUsage))
        assertEquals(false, history.load("session").last().modelRequestCompleted)
        assertEquals(completed, completedUsage.id)
        assertEquals(false, requests.list("session").last().usage?.modelRequestCompleted)
        assertNull(requests.list("session").last().usage?.inputTokens)
        assertEquals(
            mapOf(
                "inputTokens" to "100",
                "providerId" to "provider",
                "providerName" to "Provider",
                "providerEndpoint" to "https://example.test/v1/responses",
                "modelId" to "local-model",
                "providerModelId" to "upstream-model",
                "modelsDevKey" to "catalog",
                "modelName" to "Upstream",
                "reasoningLevel" to "HIGH",
            ),
            extractUsagePayload(completedUsage),
        )
    }

    @Test
    fun failedAssistantPersistencePreservesReportedUsage() = runBlocking {
        val history = object : HistoryStore by InMemoryHistoryStore() {
            override suspend fun updateHistory(sessionId: String, transform: (List<StoredMessage>) -> List<StoredMessage>) {
                error("write failed")
            }
        }
        val requests = InMemoryModelRequestStore()
        val persister = TurnIncrementalPersister("session", history, modelRequestStore = requests)
        val requestId = persister.beginResponse()
        val response = flowOf(
            StreamFrame.TextComplete("reply"),
            StreamFrame.End("stop", ResponseMetaInfo.create(KoogClock.System, inputTokensCount = 100)),
        ).collectAssistantPreservingPartial(persister, requestId)
        assertFailsWith<IllegalStateException> {
            persister.persistAssistant(response.copy(id = requestId))
        }
        assertEquals(emptyList(), history.load("session"))
        val record = requests.list("session").single()
        assertEquals(requestId, record.requestId)
        assertEquals(100, record.usage?.inputTokens)
        assertEquals(true, record.usage?.modelRequestCompleted)
    }

    @Test
    fun compressionUsageUsesSameLedgerWithoutAssistantResponse() = runBlocking {
        val history = InMemoryHistoryStore()
        val requests = InMemoryModelRequestStore()
        val persister = TurnIncrementalPersister(
            sessionId = "child",
            historyStore = history,
            modelRequestStore = requests,
            usageOwnerSessionId = "parent",
        )
        val requestId = persister.beginModelRequest("compaction")
        assertEquals("compaction", requests.list("parent").single().usage?.modelRequestPurpose)
        val end = StreamFrame.End("stop", ResponseMetaInfo.create(KoogClock.System, inputTokensCount = 120))
        assertEquals(listOf(end), flowOf(end).recordRequestUsage(persister, requestId, "compaction").toList())
        val usage = requests.list("parent").single().usage
        assertEquals("compaction", usage?.modelRequestPurpose)
        assertEquals(120, usage?.inputTokens)
        assertEquals(true, usage?.modelRequestCompleted)
        assertEquals(emptyList(), history.load("child"))
        assertNull(persister.currentResponseId.get())
    }
}
