package xyz.mederi.koog

import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.infrastructure.koog.MessageDiagnostics
import xyz.mederi.infrastructure.koog.extractUsagePayload
import xyz.mederi.infrastructure.koog.withDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 「供应商 + API Key + 模型」跨会话统计的前提：每次请求落库的 Message 携带实际使用的 keyId。 */
class MessageApiKeyDiagnosticsTest {
    @Test
    fun diagnosticsFillEveryFieldAndKeepExistingApiKey() {
        val diagnostics = MessageDiagnostics(
            providerId = "provider",
            modelId = "local-model",
            modelName = "Display name",
            reasoningLevel = "HIGH",
            agentMode = "CODER",
            projectId = "project",
            turnId = "turn-1",
            workStartedAt = 1000L,
            providerEndpoint = "https://example.test/responses",
            providerModelId = "upstream-model",
            providerName = "Provider",
            modelsDevKey = "catalog",
            apiKeyId = "key_x",
        )
        val empty = assistant()
        val filled = empty.withDiagnostics(diagnostics)
        assertEquals("provider", filled.providerId)
        assertEquals("Provider", filled.providerName)
        assertEquals("https://example.test/responses", filled.providerEndpoint)
        assertEquals("local-model", filled.modelId)
        assertEquals("upstream-model", filled.providerModelId)
        assertEquals("catalog", filled.modelsDevKey)
        assertEquals("Display name", filled.modelName)
        assertEquals("HIGH", filled.reasoningLevel)
        assertEquals("CODER", filled.agentMode)
        assertEquals("project", filled.projectId)
        assertEquals("turn-1", filled.turnId)
        assertEquals(1000L, filled.workStartedAt)
        assertEquals(false, filled.isFinalAnswer)
        assertEquals("key_x", filled.apiKeyId)

        val kept = empty.copy(apiKeyId = "keep").withDiagnostics(diagnostics)
        assertEquals("keep", kept.apiKeyId)
    }

    @Test
    fun apiKeyIdSurvivesJsonRoundTripAndDecodesMissingFieldAsNull() {
        val json = Json { ignoreUnknownKeys = true }
        val message = assistant().copy(apiKeyId = "key_x")
        val decoded = json.decodeFromString<Message>(json.encodeToString(Message.serializer(), message))
        assertEquals("key_x", decoded.apiKeyId)
        assertEquals("key_x", extractUsagePayload(decoded)["apiKeyId"])

        // 旧式 JSON：字段从未写入（不含 apiKeyId 键）
        val legacy = """{"id":"msg","sessionId":"session","role":"ASSISTANT","parts":[],""" +
            """"status":"COMPLETED","createdAt":"2026-11-14T08:00:00Z"}"""
        assertNull(json.decodeFromString<Message>(legacy).apiKeyId)
        assertEquals(emptyMap(), extractUsagePayload(assistant()))
    }

    private fun assistant() = Message(
        id = "msg",
        sessionId = "session",
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
        createdAt = "2026-11-14T08:00:00Z",
    )
}
