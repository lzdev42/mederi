package xyz.mederi.provider.infrastructure.koog.sanitize

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Sanitized 模型专用的 Json 实例。
 *
 * 与 Koog [AbstractOpenAILLMClient] 内部的 `defaultJson` 配置一致：
 * - `ignoreUnknownKeys = true`：忽略 vLLM 内部字段（prompt_logprobs / token_ids / routed_experts 等）
 * - `namingStrategy = SnakeCase`：Kotlin 驼峰属性映射到 JSON snake_case（reasoningContent → reasoning_content）
 * - `explicitNulls = false`：不序列化 null 字段
 *
 * [SanitizedStreamDelta.reasoningAlias] / [SanitizedAssistantMessage.reasoningAlias] 用 `@SerialName("reasoning")`
 * 显式指定 JSON 字段名，覆盖 SnakeCase 策略，用于兜底解析 vLLM 的非标准 `reasoning` 字段。
 */
@OptIn(ExperimentalSerializationApi::class)
val sanitizeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    namingStrategy = JsonNamingStrategy.SnakeCase
}
