package xyz.mederi.core.contract.dto

import kotlinx.serialization.Serializable
import xyz.mederi.core.contract.models.AgentOption
import xyz.mederi.core.contract.models.CustomModelEntry
import xyz.mederi.core.contract.models.ProtocolType

/**
 * 推理参数配置（UI 层表达，与 core ReasoningParameter 对应）。
 *
 * v6：每个级别一段用户编写的请求体 JSON 片段，发送时根级合并进请求体。
 * key 为 ReasoningLevel 枚举名（NONE/LOW/MEDIUM/HIGH/MAX）；
 * value 为 JSON 片段字符串，null 表示该级别不发参数。
 */
@Serializable
data class ReasoningConfigInput(
    val levels: Map<String, String?> = emptyMap()
)

@Serializable
data class ProviderUpdateInput(
    val name: String? = null,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val enabled: Boolean? = null,
    val customModels: List<CustomModelEntry>? = null,
    val reasoningParameter: ReasoningConfigInput? = null,
)

@Serializable
data class CreateCustomProviderInput(
    val name: String,
    val baseUrl: String,
    val apiKey: String?,
    val customModels: List<CustomModelEntry> = emptyList(),
    val type: ProtocolType = ProtocolType.DEFAULT,
    val responseSanitization: Boolean = false,
    val reasoningParameter: ReasoningConfigInput? = null,
)

// ---------------------------------------------------------------------------
// Wire 请求 DTO：wasmJs 客户端 ↔ server 之间的请求体。
// 与 AiCore 方法一一对应，server 端转调 MederiAiCore，客户端端组装发送。
// ---------------------------------------------------------------------------

/** createConversation：POST /v1/sessions */
@Serializable
data class CreateConversationInput(
    val projectId: String,
    val agent: AgentOption? = null,
)

/** renameConversation：PATCH /v1/sessions/{id} */
@Serializable
data class RenameConversationInput(val title: String)

/** renameProject：PATCH /v1/projects/{id} */
@Serializable
data class RenameProjectInput(val name: String)

/** addBuiltinProvider：POST /v1/providers/builtin */
@Serializable
data class BuiltinProviderInput(val name: String, val apiKey: String)

/** addProviderModel：POST /v1/providers/{id}/models */
@Serializable
data class AddModelInput(
    val providerModelId: String,
    val name: String,
    val supportsThinking: Boolean = false,
    val supportsImages: Boolean = false,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val reasoningLevels: List<String> = emptyList(),
    val isEnabled: Boolean = true,
)

/** updateProviderModel：PATCH /v1/providers/{id}/models/{modelId} */
@Serializable
data class UpdateModelInput(
    val name: String? = null,
    val supportsThinking: Boolean? = null,
    val supportsImages: Boolean? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val reasoningLevels: List<String>? = null,
    val isEnabled: Boolean? = null,
)

/** setModelEnabled：POST /v1/providers/{id}/models/{modelId}/enabled */
@Serializable
data class SetModelEnabledInput(val enabled: Boolean)

/** addProviderApiKey：POST /v1/providers/{id}/keys */
@Serializable
data class AddApiKeyInput(
    val name: String,
    val key: String,
    val isDefault: Boolean = false,
)

/** resolveQuestion：POST /v1/sessions/{id}/questions/{questionId} */
@Serializable
data class ResolveQuestionInput(val answers: List<List<String>>)

/** resolvePlanApproval：POST /v1/sessions/{id}/plans/{planId}/approve */
@Serializable
data class ResolvePlanApprovalInput(val approved: Boolean)

/** server 错误响应体 */
@Serializable
data class ApiError(val error: String)

/** GET /v1/ready：server 就绪探针 */
@Serializable
data class ReadyInfo(val ready: Boolean, val configDir: String)

/** GET /v1/plan-content：按 planPath 读取计划文件内容（服务端受限读取） */
@Serializable
data class PlanContent(val content: String?)
