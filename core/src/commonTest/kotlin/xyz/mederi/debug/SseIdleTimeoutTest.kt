package xyz.mederi.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mederi.http.SseIdleTimeoutException
import xyz.mederi.provider.infrastructure.koog.retry.RetryableLLMClient

class SseIdleTimeoutTest {

    @Test
    fun sseIdleTimeoutException_isClassifiedAsPrematureClose() {
        val ex = SseIdleTimeoutException("SSE stream idle timeout: no data received for 90s (emitted=true)")
        val record = ErrorCollector.collect(
            throwable = ex,
            context = ErrorContext(
                phase = "streaming",
                sessionId = "sess_idle_test",
                providerId = "prov_openai",
                providerName = "OpenAI",
                modelId = "gpt-4o",
                modelName = "GPT-4o"
            )
        )

        assertEquals(ErrorCategory.NETWORK, record.category)
        assertEquals(ErrorCollector.FailureMode.PREMATURE_CLOSE, record.failureMode)
        assertEquals("PREMATURE_CLOSE", record.toPayload()["failureMode"])
        assertEquals("SseIdleTimeout", record.toPayload()["networkErrorType"])
        assertTrue(record.fullDiagnostic.contains("SseIdleTimeout"))
    }

    @Test
    fun sseIdleTimeoutException_isRecognizedAsTransientError() {
        val ex = SseIdleTimeoutException("SSE stream idle timeout: no data received for 90s")
        assertTrue(RetryableLLMClient.isTransientError(ex))
    }

    @Test
    fun socketTimeoutException_isRecognizedAsTransientError() {
        val ex = java.net.SocketTimeoutException("Read timed out")
        assertTrue(RetryableLLMClient.isTransientError(ex))
    }
}
