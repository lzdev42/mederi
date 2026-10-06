package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    /** 有真实源（core msg.inputTokens 累加），保持非 null = 0 表示真无用量 */
    val input: Long = 0,
    /** 有真实源（core msg.outputTokens 累加） */
    val output: Long = 0,
    /** 无 core 源 = 死值，改 nullable：null 表示无数据（UI 显示"—"），不再用 0 伪装 */
    val reasoning: Long? = null,
    /** 有真实源（core msg.cachedTokens 累加） */
    val cacheRead: Long = 0,
    /** 无 core 源 = 死值，改 nullable：null 表示无数据（UI 显示"—"），不再用 0 伪装 */
    val cacheWrite: Long? = null,
) {
    val total: Long get() = input + output + (reasoning ?: 0)
}

@Serializable
data class CostSummary(
    /** 无真实费用源 = 死值，改 nullable：null 表示不可用（UI 显示"不可用"或隐藏），不再用 0 伪装 */
    val total: Double? = null,
    val currency: String = "USD",
)

/**
 * 最近一次 LLM HTTP 请求的用量（快照级、非累加）。
 * 诚实原则沿用 [TokenUsage]：cachedTokens 为 null = 供应商未报缓存数，不用 0 伪装；
 * inputTokens/outputTokens 有真实源（core 消息字段），无数据时为 0。
 */
@Serializable
data class LastRequestUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long? = null,
)
