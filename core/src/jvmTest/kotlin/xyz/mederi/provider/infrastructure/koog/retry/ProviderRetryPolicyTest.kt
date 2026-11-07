package xyz.mederi.provider.infrastructure.koog.retry

import ai.koog.http.client.KoogHttpClientException
import xyz.mederi.http.ProviderHttpException
import kotlin.test.*

class ProviderRetryPolicyTest {
    private val now = 1_700_000_000_000L
    private fun inspect(headers: Map<String, String> = emptyMap(), body: String? = null, status: Int = 429) =
        ProviderRetryPolicy.inspect(IllegalStateException("wrapped", ProviderHttpException(status, headers, body,
            KoogHttpClientException(statusCode = status, errorBody = body))), now)

    @Test
    fun explicitHeadersSupportMillisecondsDecimalSecondsDatesAndLongWaits() {
        val cases = listOf(
            mapOf("ReTrY-AfTeR-Ms" to "1250", "retry-after" to "99") to 1250L,
            mapOf("retry-after-ms" to "0.01") to 1L,
            mapOf("retry-after" to "1.234567") to 1235L,
            mapOf("retry-after" to "0") to 0L,
            mapOf("retry-after" to "3600") to 3_600_000L,
            mapOf("retry-after" to "Tue, 14 Nov 2023 22:13:25 GMT") to 5000L,
            mapOf("retry-after" to "Tuesday, 14-Nov-23 22:13:25 GMT") to 5000L,
            mapOf("retry-after" to "Tue Nov 14 22:13:25 2023") to 5000L,
            mapOf("retry-after" to "Tue, 14 Nov 2023 22:13:00 GMT") to 0L,
            mapOf("date" to "Tue, 14 Nov 2023 22:13:00 GMT",
                "retry-after" to "Tue, 14 Nov 2023 22:13:25 GMT") to 25000L,
        )
        for ((headers, expected) in cases) assertEquals(expected, inspect(headers).delayMs, headers.toString())
    }

    @Test
    fun invalidHintsFallThroughWithoutGuessingResetUnitsOrMessageText() {
        for (bad in listOf("-1", "NaN", "Infinity", "1e10", "garbage", "999999999999999999999999999999999")) {
            assertEquals(2500L, inspect(mapOf("retry-after-ms" to bad, "retry-after" to "2.5")).delayMs)
            assertNull(inspect(mapOf("retry-after" to bad)).delayMs)
        }
        assertNull(inspect(mapOf("x-ratelimit-reset-tokens" to "6m0s", "x-ratelimit-reset" to "1700000900000",
            "x-ms-retry-after-ms" to "2000"), """{"error":{"message":"retry after 30 seconds"}}""").delayMs)
    }

    @Test
    fun googleRetryInfoIsTypedAndHeadersTakePriority() {
        val body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.Help","retryDelay":"999s"},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"3.500000001s"}]}}"""
        assertEquals(3501L, inspect(body = body).delayMs)
        assertEquals("google.rpc.RetryInfo", inspect(body = body).source)
        assertEquals(5000L, inspect(mapOf("retry-after" to "5"), body).delayMs)
        assertEquals(3501L, inspect(mapOf("retry-after" to "invalid"), body).delayMs)
        assertEquals(true, inspect(body = body, status = 400).retryable)
        assertNull(inspect(body = body.replace("3.500000001s", "-1s")).delayMs)
        assertNull(inspect(body = "broken JSON retryDelay=3s").delayMs)
        // 旧异常链无headers仍可读Google结构化响应体。
        assertEquals(3501L, ProviderRetryPolicy.inspect(KoogHttpClientException(statusCode = 429, errorBody = body)).delayMs)
    }

    @Test
    fun permanentStructuredCodesAndServerOptOutWinBeforeDelayOrOptIn() {
        for (code in listOf("credit_balance_exhausted", "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded", "organization_usage_limit_exceeded", "exceeded_current_quota_error")) {
            val body = """{"error":{"type":"rate_limit_error","code":"$code"}}"""
            assertEquals(false, inspect(mapOf("x-should-retry" to "true", "retry-after" to "1"), body).retryable)
        }
        assertEquals(false, inspect(mapOf("x-should-retry" to "FALSE", "retry-after" to "1")).retryable)
        assertEquals(false, inspect(mapOf("x-should-retry" to "true"), status = 401).retryable)
        assertEquals(false, inspect(mapOf("x-should-retry" to "true"), status = 400).retryable)
        assertEquals(true, inspect(mapOf("x-should-retry" to "true"), status = 409).retryable)
        assertEquals(true, inspect(mapOf("retry-after" to "2"), status = 402).retryable)
        assertEquals(false, inspect(mapOf("retry-after" to "garbage"), status = 402).retryable)
    }

    @Test
    fun dailyQuotaStopsButMinuteQuotaAndGenericQuotaDoNot() {
        val body = """{"error":{"code":429,"details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"1s"}]}}"""
        assertEquals(false, inspect(mapOf("retry-after" to "1"), body).retryable)
        assertEquals(true, inspect(body = body.replace("PerDay", "PerMinute")).retryable)
        assertEquals(true, inspect(body = """{"error":{"code":"quota_exceeded","message":"unknown quota"}}""").retryable)
    }
    @Test
    fun diagnosticKeepsRealHttpFailureBehindGenericStreamingWrapper() {
        val body = """{"error":{"code":"credit_balance_exhausted","message":"Add credits"}}"""
        val original = KoogHttpClientException(statusCode = 429, errorBody = body)
        val metadata = ProviderHttpException(429, mapOf("retry-after" to "1"), body, original)
        val wrapper = KoogHttpClientException(message = "streaming failed", cause = metadata)
        val diagnostics = xyz.mederi.debug.ErrorCollector.extractDiagnostics(wrapper)
        assertEquals(429, diagnostics.httpStatusCode)
        assertEquals(body, diagnostics.errorBody)
        assertEquals("Add credits", xyz.mederi.debug.ErrorCollector.extractServerMessage(wrapper))
    }

    @Test
    fun malformedUnrelatedDetailsNeverMaskPermanentCodeOrOtherRetryInfo() {
        val permanent = """{"error":{"code":"credit_balance_exhausted","details":{"trace":"unknown shape"}}}"""
        assertEquals(false, inspect(body = permanent).retryable)
        val mixed = """{"error":{"code":429,"details":[
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":42},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"120s"},
            {"trace":"ignored"}]}}"""
        assertEquals(120_000L, inspect(body = mixed).delayMs)
        assertEquals(true, inspect(body = mixed).retryable)
    }

    @Test
    fun compatibleInsufficientQuotaIsAmbiguousAndMustNotSuppress429Retries() {
        val senseNova = """{"error":{"message":"inference exceeds tpm/rpm limit","type":"rate_limit_error","code":"insufficient_quota"}}"""
        val advice = inspect(body = senseNova)
        assertEquals(true, advice.retryable)
        assertNull(advice.delayMs, "no server delay: use the random fallback")
        assertEquals(120_000L, inspect(mapOf("retry-after" to "120"), senseNova).delayMs)
        assertEquals(false, inspect(mapOf("x-should-retry" to "false"), senseNova).retryable)
        assertEquals(true, inspect(body = """{"error":{"type":"insufficient_quota"}}""").retryable)
        // 宽泛type不能覆盖更具体的余额不足code。
        assertEquals(false, inspect(body = """{"error":{"type":"insufficient_quota","code":"credit_balance_exhausted"}}""").retryable)
    }

}
