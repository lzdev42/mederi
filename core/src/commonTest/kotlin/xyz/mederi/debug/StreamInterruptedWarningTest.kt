package xyz.mederi.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

class StreamInterruptedWarningTest {

    @Test
    fun collectWarning_includesProviderModelAndServerAttribution() {
        val record = ErrorCollector.collectWarning(
            message = "流式连接提前中断：服务器关闭连接但未发送结束标记（已接收 10 帧 / 500 字符）",
            detail = StreamCloseDiagnostics(
                mode = "premature-close",
                linesReceived = 12,
                bytesReceived = 520,
                durationMs = 3500,
                maxGapMs = 800,
                ttfbMs = 250,
                lastRawLines = listOf(
                    "event: error",
                    "data: {\"error\":{\"message\":\"upstream closed\"}}",
                    ": ping"
                ),
                errorSseLines = listOf("event: error")
            ).summary(),
            context = ErrorContext(
                phase = "streaming",
                sessionId = "sess_test",
                providerId = "prov_1",
                providerName = "OpenRouter",
                modelId = "mdl_1",
                modelName = "claude-3.5-sonnet"
            )
        )

        // 责任方判定 + provider/model 写入诊断
        assertEquals(ErrorCategory.NETWORK, record.category)
        assertEquals(ErrorSeverity.WARNING, record.severity)
        assertEquals(ErrorCollector.FailureMode.PREMATURE_CLOSE, record.failureMode)
        assertEquals("OpenRouter", record.providerName)
        assertEquals("claude-3.5-sonnet", record.modelName)

        val diag = record.fullDiagnostic
        assertContains(diag, "Provider: OpenRouter (prov_1)")
        assertContains(diag, "Model: claude-3.5-sonnet (mdl_1)")
        assertContains(diag, "责任方: SERVER/PROXY")
        assertContains(diag, "最后 3 条原始 SSE 行")
        assertContains(diag, "event: error")
        assertContains(diag, "最大间隔: 800ms")

        // payload 带 failureMode
        val payload = record.toPayload()
        assertEquals("PREMATURE_CLOSE", payload["failureMode"])
        assertEquals("OpenRouter", payload["providerName"])
        assertEquals("claude-3.5-sonnet", payload["modelName"])
    }

    @Test
    fun collectWarning_withoutDetail_stillHasServerAttribution() {
        val record = ErrorCollector.collectWarning(
            message = "流式连接提前中断：服务器关闭连接但未发送结束标记（已接收 3 帧 / 12 字符）",
            context = ErrorContext(phase = "streaming", sessionId = "sess_warn")
        )
        assertEquals(ErrorCollector.FailureMode.PREMATURE_CLOSE, record.failureMode)
        assertTrue(record.fullDiagnostic.contains("WARNING（无异常，静默失败）"))
        assertTrue(record.fullDiagnostic.contains("建议："))
    }

    @Test
    fun streamCloseDiagnostics_summary_classifiesModes() {
        val premature = StreamCloseDiagnostics(
            mode = "premature-close", linesReceived = 5, bytesReceived = 100,
            durationMs = 1000, maxGapMs = 300, ttfbMs = 50
        )
        assertContains(premature.summary(), "责任方: SERVER/PROXY")

        val exception = StreamCloseDiagnostics(
            mode = "exception", linesReceived = 0, bytesReceived = 0,
            durationMs = 0, maxGapMs = 0, ttfbMs = -1,
            errorType = "SocketTimeoutException", errorMessage = "Read timed out"
        )
        assertContains(exception.summary(), "SocketTimeoutException")

        val done = StreamCloseDiagnostics(
            mode = "done", linesReceived = 8, bytesReceived = 200,
            durationMs = 800, maxGapMs = 100, ttfbMs = 60
        )
        assertContains(done.summary(), "正常结束")
    }
}
