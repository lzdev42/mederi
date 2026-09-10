package xyz.mederi.core.bridge

import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
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
import xyz.mederi.core.contract.models.ModelOption
import xyz.mederi.core.contract.models.ProtocolType
import xyz.mederi.core.contract.models.Project as UiProject
import xyz.mederi.core.contract.models.ProviderConfig
import xyz.mederi.core.contract.models.ProviderType as UiProviderType
import xyz.mederi.core.contract.models.TokenUsage
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.WorkType as UiWorkType
import xyz.mederi.domain.model.WorkType as CoreWorkType
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.FileDiff as CoreFileDiff
import xyz.mederi.domain.model.Message as CoreMessage
import xyz.mederi.domain.model.MessagePart as CoreMessagePart
import xyz.mederi.domain.model.MessageRole as CoreMessageRole
import xyz.mederi.domain.model.UI_HIDDEN_MARKER
import xyz.mederi.domain.model.Project as CoreProject
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.provider.domain.model.Provider as CoreProvider
import xyz.mederi.provider.domain.model.ProviderType as CoreProviderType
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * 将 core 领域模型转换为 UI contract 模型。
 *
 * 纯函数，不依赖 coroutine 或外部状态。需要 providerId 的地方由调用方传入。
 */
object MederiModelMapper {

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // Project
    // ------------------------------------------------------------------

    fun toProject(coreProject: CoreProject, conversations: List<Conversation>): UiProject =
        UiProject(
            id = coreProject.id,
            name = coreProject.name,
            directories = coreProject.directories,
            conversations = conversations
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
            directory = null,
            workType = when (session.workType) {
                CoreWorkType.WORK -> UiWorkType.WORK
                CoreWorkType.CODE -> UiWorkType.CODE
            }
        )

    private fun toConversationStatus(status: SessionStatus): ConversationStatus = when (status) {
        SessionStatus.IDLE -> ConversationStatus.Idle
        SessionStatus.RUNNING -> ConversationStatus.Working
        SessionStatus.ERROR -> ConversationStatus.Error
    }

    // ------------------------------------------------------------------
    // Provider / Model
    // ------------------------------------------------------------------

    fun toProviderConfig(provider: CoreProvider): ProviderConfig {
        val isBuiltin = BuiltinProviders.isBuiltinName(provider.name)
        val rp = provider.reasoningParameter
        return ProviderConfig(
            id = provider.id,
            name = provider.name,
            type = if (isBuiltin) UiProviderType.Builtin else UiProviderType.Custom,
            baseUrl = provider.baseUrl,
            isConnected = provider.defaultApiKey != null,
            models = provider.models.map { toModelOption(it, provider.id, provider.reasoningParameter) },
            customModels = provider.models.map { toCustomModelEntry(it) },
            supportsApiKey = true,
            supportsBaseUrl = !isBuiltin,
            protocolType = ProtocolType.valueOf(provider.type.name),
            apiKeys = provider.apiKeys.map { toApiKeyOption(it) },
            // 完整回显每档配置（含 NONE 档），供编辑对话框回显
            reasoningLevels = rp?.levels?.mapKeys { it.key.name } ?: emptyMap(),
            responseSanitization = provider.responseSanitization
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

    fun toModelOption(
        model: AIModel,
        providerId: String,
        reasoningParameter: xyz.mederi.provider.domain.model.ReasoningParameter? = null
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
            reasoningLevels = reasoningLevels,
            providerModelId = model.providerModelId,
            contextWindow = model.contextWindow,
            maxTokens = model.maxTokens,
            inputPricePerMillion = model.inputPricePerMillion,
            outputPricePerMillion = model.outputPricePerMillion,
            isEnabled = model.isEnabled
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

    fun toChatMessage(message: CoreMessage): ChatMessage {
        val toolResultsById = message.parts
            .filterIsInstance<CoreMessagePart.ToolResult>()
            .filterNot { it.id.isNullOrBlank() }
            .associateBy { it.id!! }

        val blocks = message.parts.mapIndexedNotNull { index, part ->
            toChatBlock(part, index, message, toolResultsById)
        }

        return ChatMessage(
            id = message.id ?: "msg_${message.createdAt}",
            conversationId = message.sessionId,
            role = toChatRole(message.role),
            blocks = blocks,
            createdAt = parseIsoToMillis(message.createdAt),
            completedAt = if (message.status == xyz.mederi.domain.model.MessageStatus.COMPLETED) {
                parseIsoToMillis(message.createdAt)
            } else null,
            parentMessageId = null,
            model = null,
            agent = null,
            isStreaming = message.status == xyz.mederi.domain.model.MessageStatus.PROCESSING,
            error = null
        )
    }

    private fun toChatRole(role: CoreMessageRole): ChatRole = when (role) {
        CoreMessageRole.SYSTEM -> ChatRole.System
        CoreMessageRole.USER -> ChatRole.User
        CoreMessageRole.ASSISTANT -> ChatRole.Assistant
        CoreMessageRole.SUMMARY -> ChatRole.Summary
    }

    private fun toChatBlock(
        part: CoreMessagePart,
        index: Int,
        message: CoreMessage,
        toolResultsById: Map<String, CoreMessagePart.ToolResult>
    ): ChatBlock? = when (part) {
        is CoreMessagePart.Text -> ChatBlock.Text(
            id = blockId(message, index),
            // `<<<NOT_FOR_UI>>>` 标记之后的元数据（发送时间等）只发给 AI，不渲染给用户
            text = part.text.substringBefore(UI_HIDDEN_MARKER).removeSuffix("\n")
        )
        is CoreMessagePart.Reasoning -> ChatBlock.Reasoning(
            id = blockId(message, index),
            text = part.content.joinToString("\n")
        )
        is CoreMessagePart.ToolCall -> {
            val input = parseArgsJson(part.args)
            val result = part.id?.let { toolResultsById[it] }
            val state = when {
                result == null -> ToolCallState.Pending
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
            name = part.path.substringAfterLast('/').substringAfterLast('\\'),
            url = part.path,
            mimeType = null
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
     * 工具参数是扁平的键值对对象（如 {"command":"ls"}），直接反序列化为 Map。
     * 非对象结构（数组、标量）或解析失败时返回空 map，不崩溃。
     */
    private fun parseArgsJson(args: String): Map<String, String> = try {
        json.decodeFromString<Map<String, String>>(args)
    } catch (_: Exception) {
        emptyMap()
    }

    // ------------------------------------------------------------------
    // Token / Cost
    // ------------------------------------------------------------------

    fun toTokenUsage(messages: List<CoreMessage>): TokenUsage {
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        messages.forEach { msg ->
            if (msg.role == CoreMessageRole.ASSISTANT) {
                msg.inputTokens?.let { input += it }
                msg.outputTokens?.let { output += it }
                msg.cachedTokens?.let { cacheRead += it }
            }
        }
        return TokenUsage(
            input = input,
            output = output,
            reasoning = 0,
            cacheRead = cacheRead,
            cacheWrite = 0
        )
    }

    fun toCostSummary(): CostSummary = CostSummary(total = 0.0, currency = "USD")

    /**
     * 当前上下文真实占用（token）：最近一条 Assistant 消息的 inputTokens，
     * 即 API 报告的最近一次请求 prompt 大小。与自动压缩触发器同源。
     * 注意与 [toTokenUsage]（累计消耗，用于成本）语义不同。
     */
    fun toContextUsedTokens(messages: List<CoreMessage>): Long =
        messages.lastOrNull { it.role == CoreMessageRole.ASSISTANT }?.inputTokens?.toLong() ?: 0L

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
