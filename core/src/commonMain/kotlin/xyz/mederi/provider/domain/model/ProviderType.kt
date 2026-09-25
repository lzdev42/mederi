package xyz.mederi.provider.domain.model

import kotlinx.serialization.Serializable

/**
 * 供应商类型枚举（可序列化）。
 *
 * Mederi 系统支持的 AI 模型供应商类型。
 * 用户在创建供应商时选择类型，系统根据类型决定使用哪种 Koog 客户端和 API 协议。
 *
 * 支持的类型：
 * - [OPENAI_CHAT]: OpenAI Chat Completions API。
 *   同时兼容所有 OpenAI 兼容的 API 端点（如 DeepSeek、OpenRouter、本地模型等）。
 *   thinking 通过 reasoning_effort 参数控制。
 * - [OPENAI_RESPONSES]: OpenAI Responses API（实验性）。
 *   使用 reasoning.effort 参数控制 thinking，支持更丰富的推理配置（含 summary）。
 *   注意：此 API 在 Koog 中标记为 @ApiStatus.Experimental，接口可能变化。
 * - [GOOGLE]: Google Gemini API。
 *   thinking 通过 thinkingConfig 控制。
 *   Gemini 2.0 使用 thinkingBudget（int token 预算），
 *   Gemini 3.0 使用 thinkingLevel（low/high 级别）。
 */
@Serializable
enum class ProviderType {
    OPENAI_CHAT,
    OPENAI_RESPONSES,
    GOOGLE
}
