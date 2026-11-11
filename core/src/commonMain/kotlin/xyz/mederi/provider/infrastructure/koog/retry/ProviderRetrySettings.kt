package xyz.mederi.provider.infrastructure.koog.retry

/**
 * 供应商级 LLM 重试设置（[resolveProviderRetrySettings] 解析后的消费值）。
 *
 * 语义（输入为 Provider.autoRetryEnabled + Provider.retryMaxDelaySeconds）：
 * - autoRetryEnabled=false → enabled=false（不重试，含服务端 Retry-After 建议也忽略）；
 * - true + null → enabled=true，区间 = 全局 [LlmRetryConfig] 默认（默认随机 0~30 秒）；
 * - true + 0 → enabled=true，min=max=0——无延迟立即重试；
 * - true + N ≥ 1 → enabled=true，随机 1~N 秒。
 *
 * enabled=true 时服务端 Retry-After 建议优先（[ProviderRetryPolicy]），无建议才用随机区间。
 */
data class ProviderRetrySettings(
    val enabled: Boolean,
    val minDelayMs: Long,
    val maxDelayMs: Long,
)

/**
 * 按供应商三态解析重试设置（纯函数，独立文件可单测）。
 *
 * @param autoRetryEnabled Provider.autoRetryEnabled；false = 不重试。
 * @param retryMaxDelaySeconds Provider.retryMaxDelaySeconds；负值由 ProviderManager 拒绝，
 *   不在本函数契约内。
 * @param globalMinDelayMs 全局退避下限（默认 [LlmRetryConfig.minDelayMs]）
 * @param globalMaxDelayMs 全局退避上限（默认 [LlmRetryConfig.maxDelayMs]）
 */
fun resolveProviderRetrySettings(
    autoRetryEnabled: Boolean,
    retryMaxDelaySeconds: Int?,
    globalMinDelayMs: Long = LlmRetryConfig.minDelayMs,
    globalMaxDelayMs: Long = LlmRetryConfig.maxDelayMs,
) : ProviderRetrySettings = when {
    !autoRetryEnabled -> ProviderRetrySettings(enabled = false, minDelayMs = globalMinDelayMs, maxDelayMs = globalMaxDelayMs)
    retryMaxDelaySeconds == null -> ProviderRetrySettings(enabled = true, minDelayMs = globalMinDelayMs, maxDelayMs = globalMaxDelayMs)
    retryMaxDelaySeconds == 0 -> ProviderRetrySettings(enabled = true, minDelayMs = 0, maxDelayMs = 0)
    else -> ProviderRetrySettings(enabled = true, minDelayMs = 1000, maxDelayMs = retryMaxDelaySeconds * 1000L)
}
