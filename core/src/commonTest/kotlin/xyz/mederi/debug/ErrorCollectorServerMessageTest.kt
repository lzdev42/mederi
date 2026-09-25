package xyz.mederi.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * serverMessage 提取 + 简报优先级：429/502 这类 HTTP 错误，用户需要看到
 * errorBody 里供应商返回的真实原因（欠费/过载），而不是干瘪的异常名。
 */
class ErrorCollectorServerMessageTest {

    @Test
    fun extractServerMessage_fromOpenAIShape() {
        val body = """{"error":{"message":"Insufficient Balance","type":"insufficient_quota","code":429}}"""
        assertEquals("Insufficient Balance", ErrorCollector.extractServerMessage(body))
    }

    @Test
    fun extractServerMessage_fromAnthropicShape() {
        val body = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
        assertEquals("Overloaded", ErrorCollector.extractServerMessage(body))
    }

    @Test
    fun extractServerMessage_fromGeminiShape() {
        val body = """{"error":{"code":429,"message":"RESOURCE_EXHAUSTED: quota","status":"RESOURCE_EXHAUSTED"}}"""
        assertEquals("RESOURCE_EXHAUSTED: quota", ErrorCollector.extractServerMessage(body))
    }

    @Test
    fun extractServerMessage_fromFastApiDetail() {
        val body = """{"detail":"model not found"}"""
        assertEquals("model not found", ErrorCollector.extractServerMessage(body))
    }

    @Test
    fun extractServerMessage_fromPlainTextBody() {
        val body = "Too Many Requests\nRetry after 10s"
        assertEquals("Too Many Requests", ErrorCollector.extractServerMessage(body))
    }

    @Test
    fun extractServerMessage_blankReturnsNull() {
        assertNull(ErrorCollector.extractServerMessage(null as String?))
        assertNull(ErrorCollector.extractServerMessage("   "))
    }

    @Test
    fun formatShortMessage_prefersServerMessage() {
        val record = ErrorRecord(
            id = "err_1",
            timestamp = "2026-09-14T00:00:00Z",
            category = ErrorCategory.RATE_LIMIT,
            severity = ErrorSeverity.RECOVERABLE,
            exceptionType = "ai.koog.http.client.KoogHttpClientException",
            exceptionMessage = "Error from client: xxx\nStatus code: 429\nError body: {...}",
            causeChain = emptyList(),
            stackTrace = "st",
            httpStatusCode = 429,
            errorBody = """{"error":{"message":"Insufficient Balance"}}""",
            serverMessage = "Insufficient Balance",
            phase = "model_call",
            fullDiagnostic = "diag"
        )
        assertEquals("[RATE_LIMIT] Insufficient Balance (HTTP 429)", record.formatShortMessage())
    }

    @Test
    fun formatShortMessage_fallsBackToLegacyFormat() {
        val record = ErrorRecord(
            id = "err_2",
            timestamp = "2026-09-14T00:00:00Z",
            category = ErrorCategory.API,
            severity = ErrorSeverity.FATAL,
            exceptionType = "xyz.SomeException",
            exceptionMessage = "Model not found",
            causeChain = emptyList(),
            stackTrace = "st",
            phase = "model_call",
            fullDiagnostic = "diag"
        )
        assertEquals("[API] SomeException: Model not found", record.formatShortMessage())
    }

    @Test
    fun toPayload_carriesServerMessage() {
        val record = ErrorRecord(
            id = "err_3",
            timestamp = "2026-09-14T00:00:00Z",
            category = ErrorCategory.RATE_LIMIT,
            severity = ErrorSeverity.RECOVERABLE,
            exceptionType = "KoogHttpClientException",
            exceptionMessage = "",
            causeChain = emptyList(),
            stackTrace = "st",
            httpStatusCode = 429,
            serverMessage = "Insufficient Balance",
            phase = "model_call",
            fullDiagnostic = "diag"
        )
        assertEquals("Insufficient Balance", record.toPayload()["serverMessage"])
        assertEquals("[RATE_LIMIT] Insufficient Balance (HTTP 429)", record.toPayload()["error"])
    }
}
