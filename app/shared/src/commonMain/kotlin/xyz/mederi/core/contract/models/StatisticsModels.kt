package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class TokenUsage(
    /** 累计输入总量，包含缓存读写 token。 */
    val input: Long = 0,
    /** 有真实源（core msg.outputTokens 累加） */
    val output: Long = 0,
    /** 无 core 源 = 死值，改 nullable：null 表示无数据（UI 显示"—"），不再用 0 伪装 */
    val reasoning: Long? = null,
    /** 已上报的累计缓存读取量。null 表示没有上报。 */
    val cacheRead: Long? = null,
    /** 已上报的累计缓存写入量。null 表示没有上报。 */
    val cacheWrite: Long? = null,
    /** 仅使用同时上报总输入和缓存读取量且计数有效的请求。 */
    val cacheHitSample: CacheHitSample? = null,
    val modelCacheUsage: List<ModelCacheUsage> = emptyList(),
    val inputReported: Boolean = true,
    val outputReported: Boolean = true,
    val latestByModel: List<SelectedModelUsage> = emptyList(),
) {
    val total: Long get() = input + output + (reasoning ?: 0)
}

@Serializable
data class CacheHitSample(
    val inputTokens: Long,
    val readTokens: Long,
    val writeTokens: Long = 0,
    val requestCount: Int? = null,
    val minRate: Double? = null,
    val maxRate: Double? = null,
) {
    val percent: Double? get() = cacheHitPercent(inputTokens, readTokens, writeTokens)
}

/** 使用请求时记录的路由。endpoint 缺失表示旧历史或端点不可用。 */
@Serializable
data class CacheModel(
    val providerId: String,
    val endpoint: String?,
    val modelId: String,
    val variant: String?,
)

@Serializable
data class ModelCacheUsage(
    val model: CacheModel,
    val sample: CacheHitSample?,
    val reportedRequests: Int,
    val totalRequests: Int,
)

@Serializable
data class CostSummary(
    /** 无真实费用源 = 死值，改 nullable：null 表示不可用（UI 显示"不可用"或隐藏），不再用 0 伪装 */
    val total: Double? = null,
    val currency: String = "USD",
    val pricedRequests: Int = 0,
    val totalRequests: Int = 0,
    val models: List<ModelUsageCost> = emptyList(),
    /** 会话累计按该渠道实际目录价的成本。null = 供应商目录无价，UI 显示 — */
    val providerEstimatedTotal: Double? = null,
)

@Serializable
data class ModelUsageCost(
    val providerName: String?,
    val modelName: String?,
    val providerId: String?,
    val modelId: String?,
    val endpoint: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val estimate: Double?,
    val pricedRequests: Int = 0,
    val totalRequests: Int = 0,
    val variant: String? = null,
    val cacheSample: CacheHitSample? = null,
    val successfulRequests: Int = totalRequests,
    /** 会话投影不区分 Key（单会话统计口径），跨会话累计见 [UsageStatGroup]。 */
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val apiKeyId: String? = null,
    val apiKeyName: String? = null,
)

/** 跨会话累计统计的一个分组：供应商 + API Key + 上游模型。 */
@Serializable
data class UsageStatGroup(
    val providerId: String?,
    val providerName: String?,
    /** null=默认/未记录；"__rotate__"=轮询。 */
    val apiKeyId: String?,
    val apiKeyName: String?,
    /** 上游 providerModelId（缺失回落本地 modelId）。 */
    val modelId: String?,
    val modelName: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cacheReadTokens: Long?,
    val cacheWriteTokens: Long?,
    val successfulRequests: Int,
    /** 组内全部请求数（含未完成、未上报 usage 的）。 */
    val totalRequests: Int,
    val pricedRequests: Int,
    val estimate: Double?,
)

/** 跨会话累计使用统计（全部会话的 model_requests 账本聚合）。 */
@Serializable
data class UsageStatistics(
    val groups: List<UsageStatGroup> = emptyList(),
    val totalEstimatedCost: Double? = null,
    val pricedRequests: Int = 0,
    val totalRequests: Int = 0,
    val currency: String = "USD",
)

@Serializable
data class SelectedModelUsage(
    val modelId: String,
    val providerId: String?,
    val usage: LastRequestUsage,
    val estimatedCost: Double? = null,
    /** 本次请求按该渠道实际目录价的成本。null = 供应商目录无价，UI 显示 — */
    val providerEstimatedCost: Double? = null,
)

/**
 * 当前上下文最近一次已记录的模型请求。供应商未报告的计数保持 null。
 */
@Serializable
data class LastRequestUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cachedTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val cacheModel: CacheModel? = null,
    val cacheRateSample: CacheHitSample? = null,
) {
    val cacheHitPercent: Double?
        get() {
            cacheRateSample?.let { return it.percent }
            val input = inputTokens ?: return null
            val read = cachedTokens ?: return null
            return cacheHitPercent(input, read, cacheWriteTokens ?: 0)
        }
}

/** 总输入包含缓存写入，只有读取算命中。 */
private fun cacheHitPercent(inputTokens: Long, readTokens: Long, writeTokens: Long): Double? =
    if (inputTokens > 0 && writeTokens in 0..inputTokens && readTokens in 0..(inputTokens - writeTokens)) {
        (readTokens.toDouble() * 1000 / inputTokens).roundToInt() / 10.0
    } else null
