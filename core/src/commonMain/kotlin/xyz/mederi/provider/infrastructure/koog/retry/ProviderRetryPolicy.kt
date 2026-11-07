package xyz.mederi.provider.infrastructure.koog.retry

import ai.koog.http.client.KoogHttpClientException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.contentOrNull
import xyz.mederi.http.ProviderHttpException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 当前失败的重试决定。null 表示仍由既有网络/临时故障分类判断；不保存到下一次请求。 */
internal data class ProviderRetryAdvice(
    val retryable: Boolean? = null,
    val delayMs: Long? = null,
    val source: String? = null,
)

/**
 * 三条 API 链路共用明确的服务端提示，不把限流桶回满(reset)时间误作重试时间。
 * 解析失败安全回到原重试分类/随机等待；不从人类可读错误消息猜等待秒数。
 */
internal object ProviderRetryPolicy {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    // insufficient_quota 在兼容端也可能表示 TPM/RPM 限流，不能据此全局拒绝重试。
    // 这里只收录明确的余额/账单/消费限额信号，未知配额语义仍按 HTTP 429 重试。
    private val permanentCodes = setOf(
        "credit_balance_exhausted", "billing_hard_limit_reached",
        "organization_spend_limit_exceeded", "project_spend_limit_exceeded",
        "organization_usage_limit_exceeded", "exceeded_current_quota_error",
    )

    fun inspect(error: Throwable, nowMillis: Long = System.currentTimeMillis()): ProviderRetryAdvice {
        val chain = generateSequence(error) { it.cause }.take(12).toList()
        val response = chain.filterIsInstance<ProviderHttpException>().firstOrNull()
        val koog = chain.filterIsInstance<KoogHttpClientException>().firstOrNull { it.statusCode != null || it.errorBody != null }
        val headers = response?.responseHeaders.orEmpty()
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()
        val body = response?.responseBody ?: koog?.errorBody
        val envelope = body?.let { runCatching { json.decodeFromString<ErrorEnvelope>(it) }.getOrNull() }
        val detail = envelope?.error
        val codes = listOfNotNull(content(detail?.code), content(detail?.type)).map { it.lowercase() }
        // 供应商扩展或某一条格式错误的详情不能破坏错误码及其他合法详情。
        val details = (detail?.details as? JsonArray).orEmpty().mapNotNull {
            runCatching { json.decodeFromJsonElement<ProviderErrorDetail>(it) }.getOrNull()
        }
        // 明确的余额/消费限额优先于重试头，避免有短 retryDelay 的永久429死循环。
        if (codes.any { it in permanentCodes }) return ProviderRetryAdvice(retryable = false)
        // 产品策略：明确日配额耗尽不能靠数秒重试恢复，反馈用户并结束本轮。
        if (details.any { d ->
            d.type in setOf("type.googleapis.com/google.rpc.QuotaFailure", "google.rpc.QuotaFailure") &&
                d.violations.any { it.quotaId?.contains("PerDay", ignoreCase = true) == true }
        }) return ProviderRetryAdvice(retryable = false)
        val status = response?.statusCode ?: koog?.statusCode
        val bodyTransient = content(detail?.code) in setOf("408", "429", "500", "502", "503", "504") ||
            content(detail?.status) == "RESOURCE_EXHAUSTED"
        if (status == 400 && !bodyTransient) return ProviderRetryAdvice(retryable = false)
        if (status in setOf(401, 403, 404, 405, 422)) return ProviderRetryAdvice(retryable = false)
        if (header("x-should-retry").equals("false", ignoreCase = true)) return ProviderRetryAdvice(retryable = false)
        val retryable = when {
            header("x-should-retry").equals("true", ignoreCase = true) -> true
            status in setOf(408, 429, 500, 502, 503, 504, 529) -> true
            bodyTransient -> true
            status != null -> false
            else -> null
        }
        header("retry-after-ms")?.let { decimalMillis(it, BigDecimal.ONE) }?.let {
            return ProviderRetryAdvice(if (status == 402) true else retryable, it, "retry-after-ms")
        }
        header("retry-after")?.let { value ->
            val millis = decimalMillis(value, BigDecimal(1000)) ?: httpDateDelay(value, header("date"), nowMillis)
            millis?.let { return ProviderRetryAdvice(if (status == 402) true else retryable, it, "retry-after") }
        }
        val retryDelays = details.filter {
            it.type == "type.googleapis.com/google.rpc.RetryInfo" || it.type == "google.rpc.RetryInfo"
        }.mapNotNull { it.retryDelay?.let(::protobufDurationMillis) }
        // 多个RetryInfo都视为下限，取最大值；不把QuotaFailure或reset时间当等待时间。
        retryDelays.maxOrNull()?.let { return ProviderRetryAdvice(retryable, it, "google.rpc.RetryInfo") }
        return ProviderRetryAdvice(retryable)
    }

    private fun decimalMillis(value: String, multiplier: BigDecimal): Long? = runCatching {
        // 禁止NaN/Infinity/负值/指数记法；亚毫秒向上取整，不能早于服务端下限。
        if (!value.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) return null
        BigDecimal(value).multiply(multiplier).setScale(0, RoundingMode.CEILING).longValueExact()
    }.getOrNull()

    private fun protobufDurationMillis(value: String): Long? {
        if (!value.matches(Regex("[0-9]+(?:\\.[0-9]{1,9})?s"))) return null
        return decimalMillis(value.dropLast(1), BigDecimal(1000))
    }

    private fun content(value: JsonElement?): String? = (value as? JsonPrimitive)?.contentOrNull

    private fun httpDateDelay(value: String, serverDate: String?, nowMillis: Long): Long? = runCatching {
        val target = parseHttpDate(value, nowMillis) ?: return null
        // 有Date时按服务器时钟算差，避免本机时钟快导致提前重试。
        val baseline = serverDate?.let { parseHttpDate(it, nowMillis) } ?: Instant.ofEpochMilli(nowMillis)
        java.time.Duration.between(baseline, target).toMillis().coerceAtLeast(0)
    }.getOrNull()

    /** RFC 9110要求接收方兼容IMF-fixdate及两种历史HTTP-date格式。 */
    private fun parseHttpDate(value: String, nowMillis: Long): Instant? {
        runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            .getOrNull()?.let { return it }
        val currentYear = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).year
        val rfc850 = DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("EEEE, dd-MMM-")
            .appendValueReduced(ChronoField.YEAR, 2, 2, currentYear - 49)
            .appendPattern(" HH:mm:ss zzz").toFormatter(Locale.US)
        runCatching { ZonedDateTime.parse(value, rfc850).toInstant() }.getOrNull()?.let { return it }
        val asctime = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu", Locale.US)
        return runCatching {
            LocalDateTime.parse(value.replace(Regex("\\s+"), " "), asctime).toInstant(ZoneOffset.UTC)
        }.getOrNull()
    }

}

@Serializable
private data class ErrorEnvelope(val error: ProviderError? = null)

@Serializable
private data class ProviderError(
    val code: JsonElement? = null,
    val type: JsonElement? = null,
    val status: JsonElement? = null,
    val details: JsonElement? = null,
)

@Serializable
private data class ProviderErrorDetail(
    @SerialName("@type") val type: String? = null,
    val retryDelay: String? = null,
    val violations: List<QuotaViolation> = emptyList(),
)

@Serializable
private data class QuotaViolation(val quotaId: String? = null)
