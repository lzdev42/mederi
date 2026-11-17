package xyz.mederi.domain.model

/** 由会话消息历史投影出的对话请求统计。 */
class SessionUsage private constructor(
    val totals: UsageTotals,
    val groups: List<RouteUsage>,
    val requests: List<Message>,
    val latest: Message?,
    val latestByModel: Map<LocalModelKey, Message>,
) {
    val requestCount: Int get() = totals.requestCount

    data class LocalModelKey(val providerId: String?, val modelId: String)

    /** 分组使用发请求时的身份。旧记录缺失的路由维度保留为 null。 */
    data class RouteKey(
        val providerId: String?,
        val providerEndpoint: String?,
        val providerModelId: String?,
        val reasoningLevel: String?,
    )

    data class RouteUsage(
        val key: RouteKey,
        val providerName: String?,
        val modelName: String?,
        val totals: UsageTotals,
        val requests: List<Message>,
    )

    data class UsageTotals(
        val inputTokens: Long?,
        val outputTokens: Long?,
        val cacheReadTokens: Long?,
        val cacheWriteTokens: Long?,
        val cacheHitSample: CacheHitSample?,
        val requestCount: Int,
        val usageCoverageRequests: Int,
    )

    data class CacheHitSample(
        val inputTokens: Long,
        val readTokens: Long,
        val writeTokens: Long,
        val requestCount: Int,
        val minRate: Double? = null,
        val maxRate: Double? = null,
    )

    companion object {
        fun cacheHitSample(message: Message): CacheHitSample? =
            if (message.role == MessageRole.ASSISTANT && message.modelRequestCompleted != false) {
                RequestUsage(message).cacheHitSample()
            } else null

        fun fromMessages(messages: List<Message>, records: List<ModelRequestRecord> = emptyList()): SessionUsage {
            val requests = mutableListOf<Message>()
            val totals = UsageAccumulator()
            val routes = linkedMapOf<RouteKey, RouteAccumulator>()
            val latestByModel = linkedMapOf<LocalModelKey, Message>()
            var latest: Message? = null

            fun recordUsage(message: Message, successful: Boolean, includeLatest: Boolean) {
                requests += message
                val usage = RequestUsage(message)
                totals.record(usage, successful)
                val route = RouteKey(
                    message.providerId,
                    message.providerEndpoint,
                    message.providerModelId ?: message.modelId,
                    message.reasoningLevel,
                )
                val group = routes.getOrPut(route, ::RouteAccumulator)
                group.record(message, usage, successful)

                // 旧消息没有请求完成标记，只有包含用量时才能识别为一次模型响应。
                if (successful && includeLatest && (message.modelRequestCompleted == true || usage.hasReportedCounts)) {
                    latest = message
                    message.modelId?.let { latestByModel[LocalModelKey(message.providerId, it)] = message }
                }
            }

            val recordedIds = records.mapTo(HashSet()) { it.requestId }
            for (message in messages) {
                if (message.role == MessageRole.SUMMARY) latest = null
                if (message.role != MessageRole.ASSISTANT || message.modelRequestCompleted == false ||
                    message.id in recordedIds
                ) continue
                val successful = message.status != MessageStatus.ERROR && !isModelErrorFinish(message.finishReason)
                recordUsage(message, successful, includeLatest = true)
            }
            // 按发起顺序补齐实际请求。子代理参与累计，主代理的最近请求选择保持独立。
            val summaryIndex = messages.indexOfLast { it.role == MessageRole.SUMMARY }
            val latestSummary = messages.getOrNull(summaryIndex)?.createdAt
            val positions = messages.mapIndexedNotNull { index, message -> message.id?.let { it to index } }.toMap()
            for (record in records) {
                val isMain = record.executionSessionId == record.ownerSessionId
                val request = record.usage ?: Message(
                    id = record.requestId,
                    sessionId = record.executionSessionId,
                    role = MessageRole.ASSISTANT,
                    parts = emptyList(),
                    createdAt = record.startedAt,
                    modelRequestCompleted = false,
                )
                val successful = request.modelRequestCompleted == true && !request.finishReason.isNullOrBlank() &&
                    request.status != MessageStatus.ERROR && !isModelErrorFinish(request.finishReason)
                recordUsage(request, successful, includeLatest = false)
                if (isMain && successful && request.modelRequestPurpose != "compaction") {
                    request.modelId?.let { latestByModel[LocalModelKey(request.providerId, it)] = request }
                    val inContext = positions[record.requestId]?.let { it > summaryIndex }
                        ?: (latestSummary == null || record.startedAt > latestSummary)
                    if (inContext) latest = request
                }
            }

            return SessionUsage(
                totals.snapshot(),
                routes.map { (key, group) -> group.snapshot(key) },
                requests,
                latest,
                latestByModel,
            )
        }
    }

    private class RequestUsage(message: Message) {
        val input = message.inputTokens?.takeIf { it >= 0 }
        val output = message.outputTokens?.takeIf { it >= 0 }
        val write = message.cacheWriteTokens?.takeIf { it >= 0 && (input == null || it <= input) }
        val read = message.cachedTokens?.takeIf { it >= 0 && (input == null || it <= input - (write ?: 0)) }
        val hasReportedCounts = input != null || output != null || read != null || write != null

        private val invalidWrite = message.cacheWriteTokens != null && write == null

        fun cacheHitSample(): CacheHitSample? {
            val total = input ?: return null
            val readTokens = read ?: return null
            val writeTokens = write ?: 0
            if (invalidWrite || total <= 0) return null
            return CacheHitSample(total.toLong(), readTokens.toLong(), writeTokens.toLong(), 1)
        }
    }

    private class UsageAccumulator {
        private var input: Long? = null
        private var output: Long? = null
        private var read: Long? = null
        private var write: Long? = null
        private var sampleInput = 0L
        private var sampleRead = 0L
        private var sampleWrite = 0L
        private var sampleRequests = 0
        private var minRate = 1.0
        private var maxRate = 0.0
        private var requests = 0
        private var usageCoverageRequests = 0

        fun record(usage: RequestUsage, successful: Boolean) {
            if (successful) requests++
            if (successful || usage.hasReportedCounts) usageCoverageRequests++
            usage.input?.let { input = (input ?: 0) + it }
            usage.output?.let { output = (output ?: 0) + it }
            usage.read?.let { read = (read ?: 0) + it }
            usage.write?.let { write = (write ?: 0) + it }
            usage.cacheHitSample()?.let { sample ->
                sampleInput += sample.inputTokens
                sampleRead += sample.readTokens
                sampleWrite += sample.writeTokens
                sampleRequests += sample.requestCount
                val rate = sample.readTokens.toDouble() / sample.inputTokens
                minRate = minOf(minRate, rate)
                maxRate = maxOf(maxRate, rate)
            }
        }

        fun snapshot(): UsageTotals = UsageTotals(
            input,
            output,
            read,
            write,
            if (sampleRequests > 0) {
                CacheHitSample(sampleInput, sampleRead, sampleWrite, sampleRequests, minRate, maxRate)
            } else null,
            requests,
            usageCoverageRequests,
        )
    }

    private class RouteAccumulator {
        private val totals = UsageAccumulator()
        private val requests = mutableListOf<Message>()
        private var providerName: String? = null
        private var modelName: String? = null

        fun record(message: Message, usage: RequestUsage, successful: Boolean) {
            requests += message
            providerName = message.providerName ?: providerName
            modelName = message.modelName ?: modelName
            totals.record(usage, successful)
        }

        fun snapshot(key: RouteKey): RouteUsage = RouteUsage(key, providerName, modelName, totals.snapshot(), requests)
    }
}

private fun isModelErrorFinish(reason: String?): Boolean =
    reason?.trim()?.lowercase() in setOf("error", "failed", "cancelled", "canceled", "aborted")
