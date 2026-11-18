package xyz.mederi.core.bridge

import java.time.Instant
import java.time.format.DateTimeFormatter
import xyz.mederi.core.contract.ToolArgParser
import xyz.mederi.core.contract.models.AgentOption
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CostSummary
import xyz.mederi.core.contract.models.CustomModelEntry
import xyz.mederi.core.contract.models.ReasoningMenu
import xyz.mederi.core.contract.models.FileDiff as UiFileDiff
import xyz.mederi.core.contract.models.LastRequestUsage
import xyz.mederi.core.contract.models.CacheHitSample
import xyz.mederi.core.contract.models.CacheModel
import xyz.mederi.core.contract.models.ModelCacheUsage
import xyz.mederi.core.contract.models.ModelOption
import xyz.mederi.core.contract.models.ProtocolType
import xyz.mederi.core.contract.models.Project as UiProject
import xyz.mederi.core.contract.models.ProviderConfig
import xyz.mederi.core.contract.models.ProviderType as UiProviderType
import xyz.mederi.core.contract.models.TokenUsage
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.FileDiff as CoreFileDiff
import xyz.mederi.domain.model.Message as CoreMessage
import xyz.mederi.domain.model.MessagePart as CoreMessagePart
import xyz.mederi.domain.model.MessageRole as CoreMessageRole
import xyz.mederi.domain.model.Project as CoreProject
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionUsage
import xyz.mederi.metadata.ModelReferencePricing
import xyz.mederi.core.contract.models.ModelUsageCost
import xyz.mederi.core.contract.models.SelectedModelUsage
import xyz.mederi.core.contract.models.UsageStatGroup
import xyz.mederi.core.contract.models.UsageStatistics
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.provider.domain.model.Provider as CoreProvider
import xyz.mederi.provider.domain.model.ProviderType as CoreProviderType
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.domain.model.ReasoningParameter

/**
 * 将 core 领域模型转换为 UI contract 模型。
 *
 * 纯函数，不依赖 coroutine 或外部状态。需要 providerId 的地方由调用方传入。
 */
object MederiModelMapper {

    // ------------------------------------------------------------------
    // Project
    // ------------------------------------------------------------------

    fun toProject(coreProject: CoreProject, conversations: List<Conversation>): UiProject =
        UiProject(
            id = coreProject.id,
            name = coreProject.name,
            directory = coreProject.directory,
            conversations = conversations,
            createdAt = parseIsoToMillis(coreProject.createdAt),
            workDir = coreProject.workDir
        )

    // ------------------------------------------------------------------
    // Conversation / Session
    // ------------------------------------------------------------------

    fun toConversation(session: Session, providerId: String? = null): Conversation =
        Conversation(
            id = session.id,
            projectId = session.projectId,
            title = session.title,
            status = toConversationStatus(session.status),
            createdAt = parseIsoToMillis(session.createdAt),
            updatedAt = parseIsoToMillis(session.updatedAt),
            parentConversationId = null,
            modelId = session.aiModel?.id,
            modelProvider = providerId,
            // 仅回显会话真实存储的推理档（core send 时写回 sessionStore.updateAgentConfig）；
            // 不得用模型声明档（aiModel.reasoningLevel）兜底冒充会话级别——那是第二个真理源
            thinkingLevel = session.reasoningLevel?.name,
            agent = session.agentMode.name,
            apiKeyId = session.apiKeyId,
            directory = null,
            draftText = session.draftText,
            workspaceId = session.workspaceId,
            associatedWorktreeId = session.associatedWorktreeId,
            archivedAt = session.archivedAt,
            pinned = session.pinned
        )

    private fun toConversationStatus(status: SessionStatus): ConversationStatus = when (status) {
        SessionStatus.IDLE -> ConversationStatus.Idle
        SessionStatus.RUNNING -> ConversationStatus.Working
        SessionStatus.ERROR -> ConversationStatus.Error
    }

    // ------------------------------------------------------------------
    // Provider / Model
    // ------------------------------------------------------------------

    fun toProviderConfig(provider: CoreProvider, oauthConnected: Boolean = false): ProviderConfig {
        val oauth = provider.authentication == xyz.mederi.provider.domain.model.ProviderAuthentication.CHATGPT_OAUTH
        val isBuiltin = BuiltinProviders.isBuiltinName(provider.name)
        val rp = provider.reasoningParameter
        val effectiveReasoningParameter = ReasoningParameter.effective(provider.type, rp)
        return ProviderConfig(
            id = provider.id,
            name = provider.name,
            type = if (isBuiltin) UiProviderType.Builtin else UiProviderType.Custom,
            baseUrl = provider.baseUrl,
            isConnected = if (oauth) oauthConnected else provider.defaultApiKey != null,
            models = provider.models.map { toModelOption(it, provider.id, effectiveReasoningParameter) },
            customModels = provider.models.map { toCustomModelEntry(it) },
            supportsApiKey = !oauth,
            supportsBaseUrl = !isBuiltin && !oauth,
            protocolType = ProtocolType.valueOf(provider.type.name),
            apiKeys = provider.apiKeys.map { toApiKeyOption(it) },
            // 完整回显每档配置（含 NONE 档），供编辑对话框回显
            reasoningLevels = rp?.levels?.mapKeys { it.key.name } ?: emptyMap(),
            responseSanitization = provider.responseSanitization,
            autoRetryEnabled = provider.autoRetryEnabled,
            retryMaxDelaySeconds = provider.retryMaxDelaySeconds,
            isChatGptOAuth = oauth
        )
    }

    fun toApiKeyOption(key: xyz.mederi.provider.domain.model.ProviderApiKey): xyz.mederi.core.contract.models.ApiKeyOption =
        xyz.mederi.core.contract.models.ApiKeyOption(
            id = key.id,
            name = key.name,
            maskedValue = key.maskedValue,
            isDefault = key.isDefault
        )

    fun toCoreEvent(event: xyz.mederi.domain.model.MederiEvent): xyz.mederi.core.contract.models.CoreEvent =
        xyz.mederi.core.contract.models.CoreEvent(
            type = xyz.mederi.core.contract.models.CoreEventType.valueOf(event.type.name),
            sessionId = event.sessionId,
            messageId = event.messageId,
            payload = event.payload,
            timestamp = event.timestamp
        )

    /**
     * core Todo → 契约 Todo（hydration 投影的唯一映射点；事件路径走 reducer 的 wire 解码）。
     * id 是渲染 key：在投影边界按序合成（"t$index"），不持久化不交换。
     */
    fun toTodos(todos: List<xyz.mederi.domain.model.TodoItem>): List<xyz.mederi.core.contract.models.TodoItem> =
        todos.mapIndexed { i, t ->
            xyz.mederi.core.contract.models.TodoItem(
                id = "t$i",
                content = t.content,
                status = when (t.status) {
                    xyz.mederi.domain.model.TodoStatus.PENDING -> xyz.mederi.core.contract.models.TodoStatus.Pending
                    xyz.mederi.domain.model.TodoStatus.IN_PROGRESS -> xyz.mederi.core.contract.models.TodoStatus.InProgress
                    xyz.mederi.domain.model.TodoStatus.COMPLETED -> xyz.mederi.core.contract.models.TodoStatus.Completed
                    xyz.mederi.domain.model.TodoStatus.CANCELLED -> xyz.mederi.core.contract.models.TodoStatus.Cancelled
                    xyz.mederi.domain.model.TodoStatus.FAILED -> xyz.mederi.core.contract.models.TodoStatus.Failed
                }
            )
        }

    fun toModelOption(
        model: AIModel,
        providerId: String,
        reasoningParameter: ReasoningParameter? = null
    ): ModelOption {
        // 思考菜单选项：有值档位 ∩ 模型勾选（NONE 恒为关闭档，ReasoningMenu.derive 推导）
        val reasoningLevels = ReasoningMenu.derive(
            providerLevels = reasoningParameter?.levels?.mapKeys { it.key.name },
            modelLevels = model.reasoningLevels.map { it.name }
        )
        return ModelOption(
            id = model.id,
            name = model.name,
            provider = providerId,
            supportsThinking = model.supportsReasoning,
            supportsImages = model.supportsImages,
            supportsImagesOverride = model.supportsImagesOverride,
            reasoningLevels = reasoningLevels,
            providerModelId = model.providerModelId,
            origin = when (model.origin) {
                xyz.mederi.domain.model.ModelOrigin.FETCHED -> xyz.mederi.core.contract.models.ModelOrigin.FETCHED
                xyz.mederi.domain.model.ModelOrigin.MANUAL -> xyz.mederi.core.contract.models.ModelOrigin.MANUAL
            },
            contextWindow = model.contextWindow,
            maxTokens = model.maxTokens,
            inputPricePerMillion = model.inputPricePerMillion,
            outputPricePerMillion = model.outputPricePerMillion,
            isEnabled = model.isEnabled,
            optimizeForRequestCount = model.optimizeForRequestCount,
        )
    }

    fun toCustomModelEntry(model: AIModel): CustomModelEntry =
        CustomModelEntry(
            id = model.id,
            name = model.name,
            supportsThinking = model.supportsReasoning,
            supportsImages = model.supportsImages,
            contextWindow = model.contextWindow,
            maxTokens = model.maxTokens,
            reasoningLevels = model.reasoningLevels.map { it.name },
            isEnabled = model.isEnabled
        )

    // ------------------------------------------------------------------
    // Message / ChatBlock
    // ------------------------------------------------------------------

    fun toChatMessage(message: CoreMessage): ChatMessage =
        toChatMessage(message, null)

    /**
     * 映射单条消息为 ChatMessage。
     *
     * @param toolResultsById 跨消息聚合的工具结果表（ToolCall 在 assistant 消息、ToolResult 在紧随的
     *   user 消息——两条消息，必须跨消息按 id 匹配才能回填 input/output，否则工具调用在重开/刷新后会
     *   退化为 Pending、命令/结果全部丢失）。为 null 时退化为只查本条消息自己的 parts。
     */
    fun toChatMessage(
        message: CoreMessage,
        toolResultsById: Map<String, CoreMessagePart.ToolResult>?,
    ): ChatMessage {
        val results = toolResultsById ?: message.parts
            .filterIsInstance<CoreMessagePart.ToolResult>()
            .filterNot { it.id.isNullOrBlank() }
            .associateBy { it.id!! }

        val blocks = if (message.hiddenFromUi) {
            emptyList()
        } else {
            val mergedParts = message.parts.mergeAdjacentParts()
            mergedParts.mapIndexedNotNull { index, part ->
                toChatBlock(part, index, message, results)
            }
        }

        return ChatMessage(
            id = message.id ?: "msg_${message.createdAt}",
            conversationId = message.sessionId,
            role = toChatRole(message.role),
            blocks = blocks,
            createdAt = parseIsoToMillis(message.createdAt),
            completedAt = if (message.status == xyz.mederi.domain.model.MessageStatus.COMPLETED) {
                // 结束时刻 ≈ 响应创建时刻 + LLM 请求耗时（下界估计）
                val base = parseIsoToMillis(message.createdAt)
                (message.durationMs?.let { base + it } ?: base)
            } else null,
            parentMessageId = null,
            model = message.modelName,
            agent = message.agentMode,
            isStreaming = message.status == xyz.mederi.domain.model.MessageStatus.PROCESSING,
            error = null,
            modelName = message.modelName,
            agentMode = message.agentMode,
            thinkingLevel = message.reasoningLevel,
            durationMs = message.durationMs,
            turnId = message.turnId,
            isFinalAnswer = message.isFinalAnswer,
            pendingSteering = message.pendingSteering,
            workStartedAt = message.workStartedAt,
            workCompletedAt = message.workCompletedAt,
            hiddenFromUi = message.hiddenFromUi,
            turnDiffSummary = message.turnDiffSummary?.let { summary ->
                xyz.mederi.core.contract.models.TurnDiffSummaryUi(
                    files = summary.files.map { f ->
                        xyz.mederi.core.contract.models.FileDiffSummaryUi(
                            path = f.path,
                            status = f.status.name,
                            additions = f.additions,
                            deletions = f.deletions
                        )
                    },
                    totalAdditions = summary.totalAdditions,
                    totalDeletions = summary.totalDeletions
                )
            }
        )
    }

    /**
     * 跨消息聚合全部 ToolResult（整个会话的消息列表）：
     * ToolCall 在 assistant 消息、ToolResult 在紧随其后的 user 消息，必须合并才能还原工具状态。
     */
    fun buildToolResultsById(messages: List<CoreMessage>): Map<String, CoreMessagePart.ToolResult> =
        messages.asSequence()
            .flatMap { it.parts.asSequence() }
            .filterIsInstance<CoreMessagePart.ToolResult>()
            .filterNot { it.id.isNullOrBlank() }
            .associateBy { it.id!! }

    private fun toChatRole(role: CoreMessageRole): ChatRole = when (role) {
        CoreMessageRole.SYSTEM -> ChatRole.System
        CoreMessageRole.USER -> ChatRole.User
        CoreMessageRole.ASSISTANT -> ChatRole.Assistant
        CoreMessageRole.SUMMARY -> ChatRole.Summary
    }

    private fun List<CoreMessagePart>.mergeAdjacentParts(): List<CoreMessagePart> {
        if (size <= 1) return this
        val merged = mutableListOf<CoreMessagePart>()
        for (part in this) {
            val last = merged.lastOrNull()
            if (part is CoreMessagePart.Reasoning && last is CoreMessagePart.Reasoning) {
                val combinedContent = last.content + part.content
                val lastSummary = last.summary
                val partSummary = part.summary
                val combinedSummary = when {
                    lastSummary != null && partSummary != null -> lastSummary + partSummary
                    lastSummary != null -> lastSummary
                    else -> partSummary
                }
                merged[merged.lastIndex] = CoreMessagePart.Reasoning(
                    content = combinedContent,
                    summary = combinedSummary,
                    encrypted = last.encrypted ?: part.encrypted,
                    id = last.id ?: part.id
                )
            } else if (part is CoreMessagePart.Text && last is CoreMessagePart.Text) {
                merged[merged.lastIndex] = CoreMessagePart.Text(text = last.text + part.text)
            } else {
                merged.add(part)
            }
        }
        return merged
    }

    private fun toChatBlock(
        part: CoreMessagePart,
        index: Int,
        message: CoreMessage,
        toolResultsById: Map<String, CoreMessagePart.ToolResult>
    ): ChatBlock? = when (part) {
        is CoreMessagePart.Text -> ChatBlock.Text(
            id = blockId(message, index),
            // `<user_intervention>` 系统干预包装只注入进 USER 角色消息，故只在 USER 消息上剥离；
            // ASSISTANT 等角色原文渲染，避免正文里字面提及包装标签时被误洗。
            text = (if (message.role == CoreMessageRole.USER)
                xyz.mederi.util.PromptComposer.sanitizeUserVisibleText(part.text)
            else part.text).removeSuffix("\n")
        )
        is CoreMessagePart.Reasoning -> ChatBlock.Reasoning(
            id = blockId(message, index),
            text = part.content.joinToString("")
        )
        is CoreMessagePart.ToolCall -> {
            val input = parseArgsJson(part.args)
            val result = part.id?.let { toolResultsById[it] }
            val state = when {
                result == null -> ToolCallState.Pending(input)
                result.isError -> ToolCallState.Failed(input, result.output)
                else -> ToolCallState.Completed(input, result.output)
            }
            ChatBlock.ToolCall(
                id = blockId(message, index),
                name = part.tool,
                state = state
            )
        }
        is CoreMessagePart.File -> ChatBlock.File(
            id = blockId(message, index),
            name = part.name ?: part.path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" },
            url = part.path,
            mimeType = part.mimeType
        )
        is CoreMessagePart.Image -> ChatBlock.File(
            id = blockId(message, index),
            name = part.url.substringAfterLast('/').substringAfterLast('\\'),
            url = part.url,
            mimeType = part.mimeType
        )
        is CoreMessagePart.ToolResult -> null // 被 ToolCall block 吸收
    }

    private fun blockId(message: CoreMessage, index: Int): String =
        "${message.id ?: "msg"}_${message.createdAt}_$index"

    /**
     * 解析工具调用参数 JSON。
     * 工具参数是扁平的键值对对象（如 {"command":"ls"}）。走 [ToolArgParser] 宽容解析
     * （容忍 max_lines / timeout_seconds 等数字参数，避免整条参数丢失）。
     */
    private fun parseArgsJson(args: String): Map<String, String> = ToolArgParser.parse(args)

    // ------------------------------------------------------------------
    // Token / Cost
    // ------------------------------------------------------------------

    fun toTokenUsage(messages: List<CoreMessage>): TokenUsage =
        toUsageProjection(messages).tokens

    data class UsageBackfill(val requestCount: Int, val lastRequestUsage: LastRequestUsage?)

    fun toUsageBackfill(messages: List<CoreMessage>): UsageBackfill =
        SessionUsage.fromMessages(messages).let { UsageBackfill(it.requestCount, it.latest?.toRequestUsage()) }

    internal data class UsageProjection(
        val tokens: TokenUsage,
        val requestCount: Int,
        val lastRequest: LastRequestUsage?,
        val cost: CostSummary,
    )

    internal fun toUsageProjection(
        messages: List<CoreMessage>,
        records: List<xyz.mederi.domain.model.ModelRequestRecord> = emptyList(),
        referencePricing: (CoreMessage) -> ModelReferencePricing? = { null },
        providerPricing: (CoreMessage) -> ModelReferencePricing? = { null },
    ): UsageProjection {
        val usage = SessionUsage.fromMessages(messages, records)
        val estimates = java.util.IdentityHashMap<CoreMessage, Double?>()
        val providerEstimates = java.util.IdentityHashMap<CoreMessage, Double?>()
        var total = 0.0
        var priced = 0
        var providerTotal = 0.0
        var providerPriced = 0
        val modelCosts = usage.groups.map { group ->
            var groupCost = 0.0
            var groupPriced = 0
            for (request in group.requests) {
                val estimate = referencePricing(request)?.let { estimateRequestCost(request, it) }
                estimates[request] = estimate
                if (estimate != null) {
                    groupCost += estimate
                    groupPriced++
                }
                // 供应商实际价并行估价：与标准价共用 [estimateRequestCost] 公式（零分叉，仅价格输入不同），
                // 一有一无互不传染（标准价链路不因供应商价为 null 受影响，反之亦然）。
                val providerEstimate = providerPricing(request)?.let { estimateRequestCost(request, it) }
                providerEstimates[request] = providerEstimate
                if (providerEstimate != null) {
                    providerTotal += providerEstimate
                    providerPriced++
                }
            }
            total += groupCost
            priced += groupPriced
            ModelUsageCost(
                providerName = group.providerName,
                modelName = group.modelName,
                providerId = group.key.providerId,
                modelId = group.key.providerModelId,
                endpoint = group.key.providerEndpoint,
                inputTokens = group.totals.inputTokens,
                outputTokens = group.totals.outputTokens,
                estimate = groupCost.takeIf { groupPriced > 0 },
                pricedRequests = groupPriced,
                totalRequests = group.totals.usageCoverageRequests,
                successfulRequests = group.totals.requestCount,
                variant = group.key.reasoningLevel,
                cacheSample = group.totals.cacheHitSample?.toContract(),
                cacheReadTokens = group.totals.cacheReadTokens,
                cacheWriteTokens = group.totals.cacheWriteTokens,
            )
        }
        val totals = usage.totals
        return UsageProjection(
            tokens = TokenUsage(
                input = totals.inputTokens ?: 0,
                output = totals.outputTokens ?: 0,
                cacheRead = totals.cacheReadTokens,
                cacheWrite = totals.cacheWriteTokens,
                cacheHitSample = totals.cacheHitSample?.toContract(),
                modelCacheUsage = usage.groups.mapNotNull { group ->
                    val model = group.key.toCacheModel() ?: return@mapNotNull null
                    val sample = group.totals.cacheHitSample
                    ModelCacheUsage(model, sample?.toContract(), sample?.requestCount ?: 0, group.totals.usageCoverageRequests)
                },
                inputReported = totals.inputTokens != null,
                outputReported = totals.outputTokens != null,
                latestByModel = usage.latestByModel.map { (key, request) ->
                    SelectedModelUsage(key.modelId, key.providerId, request.toRequestUsage(), estimates[request], providerEstimates[request])
                },
            ),
            requestCount = usage.requestCount,
            lastRequest = usage.latest?.toRequestUsage(),
            cost = CostSummary(
                total = total.takeIf { priced > 0 },
                pricedRequests = priced,
                totalRequests = totals.usageCoverageRequests,
                models = modelCosts,
                providerEstimatedTotal = providerTotal.takeIf { providerPriced > 0 },
            ),
        )
    }

    /** 跨会话累计分组键：供应商 + API Key + 上游模型（null 各自成桶 = 旧记录「未知」维度）。 */
    private data class UsageGroupKey(
        val providerId: String?,
        val apiKeyId: String?,
        val modelId: String?,
    )

    /**
     * 跨会话累计使用统计：全库 model_requests 账本按 [UsageGroupKey] 分组聚合。
     *
     * token / 成功计数 / 覆盖率一律由 [SessionUsage] 的账本路径算出（同一真理源，不复制成功判定）；
     * 估价复用本文件 [estimateRequestCost]，故与单会话 cost 口径一致。
     * 与 [toUsageProjection] 对称双算：[referencePricing] 标准价 + [providerPricing] 该渠道实际价
     * 共用估价公式（零分叉），一有一无互不传染（供应商目录无价时 provider 侧保持 null）。
     * 组顺序 = 记录首次出现顺序。
     */
    internal fun toCumulativeUsageStatistics(
        records: List<xyz.mederi.domain.model.ModelRequestRecord>,
        referencePricing: (CoreMessage) -> ModelReferencePricing? = { null },
        providerPricing: (CoreMessage) -> ModelReferencePricing? = { null },
    ): UsageStatistics {
        // records → 一条 usage Message（未完成 usage==null 的由 SessionUsage 合成），与 records 同序同长
        val requests = SessionUsage.fromMessages(emptyList(), records).requests
        val buckets = linkedMapOf<UsageGroupKey, MutableList<Int>>()
        requests.forEachIndexed { index, request ->
            val key = UsageGroupKey(request.providerId, request.apiKeyId, request.providerModelId ?: request.modelId)
            buckets.getOrPut(key) { mutableListOf() }.add(index)
        }

        val groups = buckets.map { (key, indexes) ->
            // 组内 records 重投影：成功计数/覆盖率/非负 token 过滤语义与单会话完全同源
            val groupUsage = SessionUsage.fromMessages(emptyList(), indexes.map { records[it] }).totals
            var providerName: String? = null
            var modelName: String? = null
            var groupCost = 0.0
            var groupPriced = 0
            var groupProviderCost = 0.0
            var groupProviderPriced = 0
            for (index in indexes) {
                val request = requests[index]
                providerName = request.providerName ?: providerName
                modelName = request.modelName ?: modelName
                referencePricing(request)?.let { price -> estimateRequestCost(request, price) }?.let {
                    groupCost += it
                    groupPriced++
                }
                // 供应商实际价并行估价：与标准价共用 [estimateRequestCost] 公式（零分叉，仅价格输入不同），一有一无互不传染
                providerPricing(request)?.let { price -> estimateRequestCost(request, price) }?.let {
                    groupProviderCost += it
                    groupProviderPriced++
                }
            }
            UsageStatGroup(
                providerId = key.providerId,
                providerName = providerName,
                apiKeyId = key.apiKeyId,
                apiKeyName = null,
                modelId = key.modelId,
                modelName = modelName,
                inputTokens = groupUsage.inputTokens,
                outputTokens = groupUsage.outputTokens,
                cacheReadTokens = groupUsage.cacheReadTokens,
                cacheWriteTokens = groupUsage.cacheWriteTokens,
                successfulRequests = groupUsage.requestCount,
                totalRequests = indexes.size,
                pricedRequests = groupPriced,
                estimate = groupCost.takeIf { groupPriced > 0 },
                providerEstimate = groupProviderCost.takeIf { groupProviderPriced > 0 },
            )
        }

        val priced = groups.sumOf { it.pricedRequests }
        val providerPricedGroups = groups.count { group -> group.providerEstimate != null }
        return UsageStatistics(
            groups = groups,
            totalEstimatedCost = groups.sumOf { it.estimate ?: 0.0 }.takeIf { priced > 0 },
            totalProviderEstimatedCost = groups.sumOf { it.providerEstimate ?: 0.0 }.takeIf { providerPricedGroups > 0 },
            pricedRequests = priced,
            totalRequests = records.size,
        )
    }

    private fun SessionUsage.CacheHitSample.toContract() =
        CacheHitSample(inputTokens, readTokens, writeTokens, requestCount, minRate, maxRate)

    private fun SessionUsage.RouteKey.toCacheModel(): CacheModel? {
        return CacheModel(providerId ?: return null, providerEndpoint, providerModelId ?: return null, reasoningLevel)
    }

    private fun CoreMessage.toRequestUsage() = LastRequestUsage(
        inputTokens = inputTokens?.toLong()?.takeIf { it >= 0 },
        outputTokens = outputTokens?.toLong()?.takeIf { it >= 0 },
        cachedTokens = cachedTokens?.toLong()?.takeIf { it >= 0 },
        cacheWriteTokens = cacheWriteTokens?.toLong()?.takeIf { it >= 0 },
        cacheModel = SessionUsage.RouteKey(providerId, providerEndpoint, providerModelId ?: modelId, reasoningLevel).toCacheModel(),
        cacheRateSample = SessionUsage.cacheHitSample(this)?.toContract(),
    )

    private fun estimateRequestCost(message: CoreMessage, price: ModelReferencePricing): Double? {
        val input = message.inputTokens?.takeIf { it >= 0 } ?: return null
        val output = message.outputTokens?.takeIf { it >= 0 } ?: return null
        val read = message.cachedTokens ?: 0
        val write = message.cacheWriteTokens ?: 0
        if (read < 0 || write < 0 || write > input || read > input - write) return null
        val readPrice = price.cacheReadPerMillion ?: price.inputPerMillion
        val writePrice = price.cacheWritePerMillion ?: price.inputPerMillion
        return (
            (input - read - write) * price.inputPerMillion + output * price.outputPerMillion +
                read * readPrice + write * writePrice
            ).div(1_000_000).takeIf { it.isFinite() }
    }

    // ------------------------------------------------------------------
    // Diff
    // ------------------------------------------------------------------

    fun toFileDiff(diff: CoreFileDiff): UiFileDiff =
        UiFileDiff(
            filePath = diff.filePath,
            before = diff.before,
            after = diff.after,
            additions = diff.additions,
            deletions = diff.deletions
        )

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    fun parseIsoToMillis(iso: String): Long = try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    fun formatMillisToIso(millis: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))
}
