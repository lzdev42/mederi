package xyz.mederi.core.bridge

import okio.ByteString.Companion.toByteString
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.CreateApiKeyRequest
import xyz.mederi.api.CreateModelRequest
import xyz.mederi.api.CreateProjectRequest
import xyz.mederi.api.CreateProviderRequest
import xyz.mederi.api.UpdateProviderRequest
import xyz.mederi.core.contract.dto.ChatPromptInput
import xyz.mederi.core.contract.dto.CreateCustomProviderInput
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.dto.ProviderUpdateInput
import xyz.mederi.core.contract.dto.ReasoningConfigInput
import xyz.mederi.core.contract.models.AgentOption
import xyz.mederi.core.contract.models.ProtocolType
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.WorkType
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.domain.model.ReasoningParameter

/**
 * 将 UI contract DTO 转换为 core API 请求对象。
 *
 * 注意：部分请求需要 MederiAiCore 做额外处理（如 configureProvider 要先 update provider 再 addKey）。
 */
object MederiInputMapper {

    // ------------------------------------------------------------------
    // Project
    // ------------------------------------------------------------------

    fun toCreateProjectRequest(input: CreateProjectInput): CreateProjectRequest =
        CreateProjectRequest(
            name = input.name,
            directory = input.directory
        )

    // ------------------------------------------------------------------
    // Provider
    // ------------------------------------------------------------------

    fun toCreateProviderRequest(input: CreateCustomProviderInput): CreateProviderRequest =
        CreateProviderRequest(
            name = input.name,
            type = input.type.name,
            baseUrl = input.baseUrl,
            apiKeys = input.apiKey?.let {
                listOf(
                    CreateApiKeyRequest(
                        name = "default",
                        value = it,
                        isDefault = true
                    )
                )
            } ?: emptyList(),
            models = input.customModels.map { toCreateModelRequest(it) },
            // 用户配置了推理参数则用用户的；否则按协议类型给默认工厂
            reasoningParameter = toReasoningParameter(input.reasoningParameter)
                ?: ReasoningParameter.forType(input.type.toProviderType()),
            responseSanitization = input.responseSanitization,
            // 尝试从端点 /models 拉取（能刷出来的自定义供应商自动获得元数据补全）；
            // 没有 models 路由的稀碎端点拉取失败静默跳过，用户手填的模型照常保存
            fetchModels = true
        )

    fun toUpdateProviderRequest(input: ProviderUpdateInput): UpdateProviderRequest =
        UpdateProviderRequest(
            name = input.name,
            baseUrl = input.baseUrl,
            reasoningParameter = toReasoningParameter(input.reasoningParameter)
        )

    /**
     * UI 层 [ReasoningConfigInput] → core [ReasoningParameter]。
     * 级别名解析失败的条目跳过；levels 为空返回 null（调用方决定回退默认工厂）。
     */
    fun toReasoningParameter(input: ReasoningConfigInput?): ReasoningParameter? {
        if (input == null) return null
        val levels = input.levels.mapNotNull { (name, value) ->
            runCatching { ReasoningLevel.valueOf(name) }.getOrNull()?.let { it to value }
        }.toMap()
        if (levels.isEmpty()) return null
        return ReasoningParameter(levels = levels)
    }

    private fun ProtocolType.toProviderType(): xyz.mederi.provider.domain.model.ProviderType =
        when (this) {
            ProtocolType.GOOGLE -> xyz.mederi.provider.domain.model.ProviderType.GOOGLE
            ProtocolType.OPENAI_RESPONSES -> xyz.mederi.provider.domain.model.ProviderType.OPENAI_RESPONSES
            ProtocolType.OPENAI_CHAT -> xyz.mederi.provider.domain.model.ProviderType.OPENAI_CHAT
        }

    private fun toCreateModelRequest(entry: xyz.mederi.core.contract.models.CustomModelEntry): CreateModelRequest =
        CreateModelRequest(
            providerModelId = entry.id,
            name = entry.name,
            supportsReasoning = entry.supportsThinking,
            reasoningLevel = ReasoningLevel.NONE,
            contextWindow = entry.contextWindow,
            maxTokens = entry.maxTokens,
            supportsImages = entry.supportsImages,
            reasoningLevels = entry.reasoningLevels.mapNotNull { name ->
                runCatching { ReasoningLevel.valueOf(name) }.getOrNull()
            }
        )

    // ------------------------------------------------------------------
    // Agent / Message
    // ------------------------------------------------------------------

    fun toAgentConfig(
        agent: AgentOption?,
        model: AIModel?,
        thinkingLevel: String?
    ): AgentConfig {
        val agentMode = agent?.let {
            runCatching { AgentMode.valueOf(it.mode.name) }.getOrNull()
        } ?: AgentMode.AUTONOMOUS
        val workType = agent?.let {
            runCatching { WorkType.valueOf(it.workType.name) }.getOrNull()
        } ?: WorkType.CODE
        return AgentConfig(
            agentMode = agentMode,
            workType = workType,
            aiModel = model,
            reasoningLevel = toReasoningLevel(thinkingLevel)
        )
    }

    fun toMessageParts(input: ChatPromptInput): List<MessagePart> =
        buildList {
            if (input.text.isNotBlank()) {
                add(MessagePart.Text(input.text))
            }
            for (att in input.attachments) {
                if (att.mimeType.startsWith("image/")) {
                    val base64 = att.bytes.toByteString().base64()
                    val dataUrl = "data:${att.mimeType};base64,$base64"
                    add(MessagePart.Image(url = dataUrl, mimeType = att.mimeType))
                }
            }
        }

    /**
     * 将 UI 字符串转换为 ReasoningLevel。
     * null 表示用户未选择思考级别，返回 null 让 core 回退到 session 存储的级别。
     */
    fun toReasoningLevel(level: String?): ReasoningLevel? {
        if (level == null) return null
        return runCatching { ReasoningLevel.valueOf(level.uppercase()) }.getOrNull()
    }

}
