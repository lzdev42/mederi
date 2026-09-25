package xyz.mederi.provider.infrastructure.koog.sanitize

import ai.koog.prompt.executor.clients.openai.base.models.OpenAIStreamToolCall
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sanitized OpenAI 响应数据模型。
 *
 * 与 Koog 原版模型的唯一区别：message/delta 额外声明了 [reasoningAlias] 字段（`@SerialName("reasoning")`），
 * 用于兜底解析 vLLM 兼容端点（如 Hetzner）返回的非标准 `reasoning` 字段。
 *
 * 兜底原则：[effectiveReasoning] 优先取标准字段 [reasoningContent]（JSON `reasoning_content`），
 * 缺失才取 [reasoningAlias]（JSON `reasoning`）。供应商修好返回标准字段后自动适配。
 *
 * Json 配置需与 Koog 一致：`ignoreUnknownKeys = true` + `namingStrategy = SnakeCase`（见 [sanitizeJson]）。
 */

/**
 * 非流式 chat completion 响应。
 */
@Serializable
data class SanitizedChatCompletionResponse(
    val choices: List<SanitizedChoice>,
    val created: Long,
    val id: String,
    val model: String,
    @SerialName("object") val objectType: String,
    val usage: OpenAIUsage? = null
)

@Serializable
data class SanitizedChoice(
    val finishReason: String,
    val index: Int,
    val message: SanitizedAssistantMessage
)

@Serializable
@SerialName("assistant")
data class SanitizedAssistantMessage(
    val content: String? = null,
    val reasoningContent: String? = null,
    @SerialName("reasoning") val reasoningAlias: String? = null,
    val toolCalls: List<OpenAIToolCall>? = null,
    val refusal: String? = null
) {
    val effectiveReasoning: String? get() = reasoningContent ?: reasoningAlias
}

/**
 * 流式 chat completion 响应。
 */
@Serializable
data class SanitizedStreamResponse(
    val choices: List<SanitizedStreamChoice>,
    val created: Long,
    val id: String,
    val model: String,
    @SerialName("object") val objectType: String,
    val usage: OpenAIUsage? = null
)

@Serializable
data class SanitizedStreamChoice(
    val delta: SanitizedStreamDelta,
    val finishReason: String? = null,
    val index: Int
)

@Serializable
data class SanitizedStreamDelta(
    val content: String? = null,
    val reasoningContent: String? = null,
    @SerialName("reasoning") val reasoningAlias: String? = null,
    val role: String? = null,
    val toolCalls: List<OpenAIStreamToolCall>? = null
) {
    val effectiveReasoning: String? get() = reasoningContent ?: reasoningAlias
}

/**
 * Responses API 响应模型。
 *
 * 替代旧版手动 JsonObject 遍历。关键改进：[SanitizedResponsesContentPart.text] 为可空，
 * 不再需要 `"text":null` → `"text":{}` 字符串替换 hack（Koog 原版模型 text 不可空导致反序列化失败）。
 *
 * 依赖 [sanitizeJson] 的 `ignoreUnknownKeys = true` + `explicitNulls = false`，
 * 扁平化字段缺失或 null 时自动使用默认值。
 */

@Serializable
data class SanitizedResponsesAPIResponse(
    val output: List<SanitizedResponsesOutputItem> = emptyList(),
    val usage: SanitizedResponsesUsage? = null,
    /** response.failed 事件携带的错误明细 */
    val error: SanitizedResponsesError? = null
)

@Serializable
data class SanitizedResponsesError(
    val code: String? = null,
    val message: String? = null
)

/**
 * Responses API 流式事件（SSE data 行）。
 *
 * 只声明 Mederi 关心的字段，其余忽略（sanitizeJson ignoreUnknownKeys）。
 * 事件类型由 [type] 区分（response.output_item.added / response.output_text.delta /
 * response.function_call_arguments.delta / response.completed ...），字段按事件类型各有取舍：
 * - output_item.added/done：[item] 携带完整输出项（含 call_id/name）
 * - 各 *.delta：[delta] 增量文本，[itemId]/[outputIndex] 定位所属输出项
 * - output_text.done / function_call_arguments.done：[text]/[arguments] 完整值
 * - response.completed/failed/incomplete：[response] 完整响应（usage 记账用）
 * - error：顶层 [code]/[message]
 */
@Serializable
data class SanitizedResponsesStreamEvent(
    val type: String,
    val item: SanitizedResponsesOutputItem? = null,
    @SerialName("item_id") val itemId: String? = null,
    @SerialName("output_index") val outputIndex: Int? = null,
    @SerialName("content_index") val contentIndex: Int? = null,
    val delta: String? = null,
    val text: String? = null,
    val arguments: String? = null,
    val response: SanitizedResponsesAPIResponse? = null,
    val code: String? = null,
    val message: String? = null
)

/**
 * 扁平化输出项，覆盖所有 output 类型（reasoning / message / function_call）。
 * 各类型只填充自己相关的字段，其余保持默认值。
 */
@Serializable
data class SanitizedResponsesOutputItem(
    val type: String,
    val id: String? = null,
    val content: List<SanitizedResponsesContentPart> = emptyList(),
    val summary: List<SanitizedResponsesContentPart> = emptyList(),
    val status: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null
)

/**
 * content / summary 数组元素。
 * - reasoning content: `{"text": "..."}` → type=null, text=非空
 * - message output_text: `{"type": "output_text", "text": "..."}` → type="output_text", text=非空
 * - message refusal: `{"type": "refusal", "refusal": "..."}` → type="refusal", refusal=非空
 * - reasoning summary: `{"text": "..."}` → type=null, text=非空
 *
 * [text] 可空：部分 API 返回 `"text": null`（Koog 原版模型不可空导致崩溃，本模型直接兼容）。
 */
@Serializable
data class SanitizedResponsesContentPart(
    val type: String? = null,
    val text: String? = null,
    val refusal: String? = null
)

@Serializable
data class SanitizedResponsesUsage(
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null
)
