package xyz.mederi.provider.infrastructure.koog

import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingConfig
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingLevel
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import xyz.mederi.debug.DebugLog
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.domain.model.ReasoningParameter

/**
 * Koog 请求参数构建器。
 *
 * 根据供应商类型和推理级别构建对应的 Koog [LLMParams]。
 *
 * ## 推理参数注入（v6：用户填什么发什么，OPENAI_CHAT / OPENAI_RESPONSES）
 *
 * [ReasoningParameter.levels] 的每个级别是一段用户编写的请求体 JSON 片段，
 * 发送时**根级合并**进请求体（通过 additionalProperties）：
 *
 * - OPENAI_CHAT: LOW 档 `{"reasoning_effort":"low"}` → 请求体 `"reasoning_effort":"low"`
 * - OPENAI_RESPONSES: LOW 档 `{"reasoning":{"effort":"low"}}` → 请求体 `"reasoning":{"effort":"low"}`
 * - NONE 档缺省 null = 不发参数；也可填显式关闭参数（奇葩端点）
 *
 * 兼容路径（旧配置）：
 * - 标量值（非 "{" 开头）挂 [ReasoningParameter.parameterName] 下注入
 * - [ReasoningParameter.customRequestBody] 始终合并，不覆盖级别片段已写入的 key
 *
 * ## Google 特例
 *
 * GOOGLE 类型走 [GoogleParams.thinkingConfig] 强类型参数（不支持根级合并），
 * levels 值为标量：数字 → thinkingBudget，"low"/"high" → thinkingLevel。
 *
 * 前置条件：
 * 1. [reasoningParameter] 为 null → 不设置任何推理参数
 * 2. 级别值缺失或为 null → 不注入该级别的参数
 *
 * 这是一个无状态的构建器对象，线程安全。
 */
object KoogParamsBuilder {

    /**
     * 构建带有指定推理级别的 LLM 参数。
     *
     * 根据供应商类型构建不同的 Koog 参数类：
     * - [ProviderType.OPENAI_CHAT] -> [OpenAIChatParams]
     * - [ProviderType.OPENAI_RESPONSES] -> [OpenAIResponsesParams]
     * - [ProviderType.GOOGLE] -> [GoogleParams]
     *
     * @param type 供应商类型。
     * @param reasoningLevel 推理级别。
     * @param reasoningParameter 供应商的推理参数配置。如果为 null，则忽略推理级别。
     * @param temperature 温度参数（可选，默认 null）。
     * @param maxTokens 最大输出 token 数（可选，默认 null）。
     * @return Koog LLMParams 实例（具体类型取决于供应商类型）。
     */
    fun build(
        type: ProviderType,
        reasoningLevel: ReasoningLevel,
        reasoningParameter: ReasoningParameter?,
        temperature: Double? = null,
        maxTokens: Int? = null
    ): LLMParams {
        return when (type) {
            ProviderType.OPENAI_CHAT -> buildOpenAIChatParams(
                reasoningLevel, reasoningParameter, temperature, maxTokens
            )
            ProviderType.OPENAI_RESPONSES -> buildOpenAIResponsesParams(
                reasoningLevel, reasoningParameter, temperature, maxTokens
            )
            ProviderType.GOOGLE -> buildGoogleParams(
                reasoningLevel, reasoningParameter, temperature, maxTokens
            )
        }
    }

    /**
     * 解析推理参数，构建根级合并的 additionalProperties（OPENAI_CHAT / OPENAI_RESPONSES 共用）。
     *
     * 注意：直接构造 Map<String, JsonElement>，**不走 Koog 的 additionalPropertiesOf**——
     * 后者对未知类型执行 JsonPrimitive(v.toString())，会把 JsonElement 字符串化（双重编码）。
     *
     * 注入规则（用户填什么发什么）：
     * 1. 级别片段（含 NONE，一律查表无特判）：
     *    - 缺失或 null → 不注入
     *    - 以 "{" 开头 → 必须解析为 JsonObject，逐 key 根级合并（写入时已校验，此处为运行时防御）
     *    - 标量值 → 旧配置兼容，挂 parameterName 下注入
     * 2. customRequestBody（旧配置兼容）：始终合并，不覆盖级别片段已写入的 key
     */
    private fun resolveAdditionalProps(
        reasoningParameter: ReasoningParameter,
        level: ReasoningLevel
    ): Map<String, JsonElement> {
        val props = mutableMapOf<String, JsonElement>()

        // 1) 级别片段（含 NONE）
        val segment = reasoningParameter.levels[level]
        if (segment != null) {
            val trimmed = segment.trim()
            if (trimmed.startsWith("{")) {
                val jsonObject = ReasoningParameter.parseJsonObject(trimmed)
                if (jsonObject != null) {
                    props.putAll(jsonObject)
                } else {
                    DebugLog.event(
                        "ParamsBuilder",
                        "级别 ${level.name} 的推理参数不是合法 JSON 对象，已跳过: $trimmed"
                    )
                }
            } else if (reasoningParameter.parameterName.isNotBlank()) {
                // 旧配置兼容：标量值挂 parameterName 下
                props[reasoningParameter.parameterName] = JsonPrimitive(segment)
            } else {
                DebugLog.event(
                    "ParamsBuilder",
                    "级别 ${level.name} 的推理参数为标量但未配置 parameterName，已跳过: $trimmed"
                )
            }
        }

        // 2) customRequestBody 旧兼容（不覆盖级别片段）
        reasoningParameter.customRequestBody.forEach { (key, raw) ->
            if (key !in props) {
                ReasoningParameter.parseJsonElement(raw)?.let { props[key] = it }
            }
        }

        return props
    }

    /**
     * 构建 OpenAI Chat Completions 参数。
     *
     * 级别片段根级合并进 additionalProperties，Koog 序列化时原样写入请求 JSON。
     * Koog 的 reasoningEffort 枚举字段不使用（不支持 MAX，且无法表达完整 JSON 片段）。
     */
    private fun buildOpenAIChatParams(
        level: ReasoningLevel,
        reasoningParameter: ReasoningParameter?,
        temperature: Double?,
        maxTokens: Int?
    ): OpenAIChatParams {
        val additionalProps = reasoningParameter
            ?.let { resolveAdditionalProps(it, level) }
            ?.takeIf { it.isNotEmpty() }

        return OpenAIChatParams(
            temperature = temperature,
            maxTokens = maxTokens,
            reasoningEffort = null,
            additionalProperties = additionalProps
        )
    }

    /**
     * 构建 OpenAI Responses API 参数。
     *
     * 级别片段同样根级合并，例如 LOW 档 `{"reasoning":{"effort":"low"}}` →
     * 请求体 `"reasoning":{"effort":"low"}`。
     */
    private fun buildOpenAIResponsesParams(
        level: ReasoningLevel,
        reasoningParameter: ReasoningParameter?,
        temperature: Double?,
        maxTokens: Int?
    ): OpenAIResponsesParams {
        val additionalProps = reasoningParameter
            ?.let { resolveAdditionalProps(it, level) }
            ?.takeIf { it.isNotEmpty() }

        return OpenAIResponsesParams(
            temperature = temperature,
            maxTokens = maxTokens,
            reasoning = null,
            additionalProperties = additionalProps
        )
    }

    /**
     * 构建 Google Gemini 参数。
     *
     * reasoning 通过 [GoogleParams.thinkingConfig] 设置（[GoogleThinkingConfig]）。
     * 根据映射值的类型自动选择使用 thinkingBudget 还是 thinkingLevel：
     * - 数字字符串（如 "8000"）-> thinkingBudget（适用于 Gemini 2.0）
     * - 非数字字符串（如 "low", "high"）-> thinkingLevel（适用于 Gemini 3.0）
     *
     * @param level 推理级别。
     * @param reasoningParameter 供应商的推理参数配置。
     * @param temperature 温度参数。
     * @param maxTokens 最大输出 token 数。
     * @return [GoogleParams] 实例。
     */
    private fun buildGoogleParams(
        level: ReasoningLevel,
        reasoningParameter: ReasoningParameter?,
        temperature: Double?,
        maxTokens: Int?
    ): GoogleParams {
        // 级别值缺失（NONE 缺省/未配置）→ thinkingConfig 置 null，与"不发参数"语义一致
        val levelValue = reasoningParameter?.resolve(level)
        val thinkingConfig = if (levelValue != null) buildGoogleThinkingConfig(levelValue) else null

        return GoogleParams(
            temperature = temperature,
            maxTokens = maxTokens,
            thinkingConfig = thinkingConfig
        )
    }

    /**
     * 构建 Google 的 [GoogleThinkingConfig]。
     *
     * 根据值是否为数字来决定使用 thinkingBudget 还是 thinkingLevel：
     * - 数字值（如 "8000"）: 使用 thinkingBudget（适用于 Gemini 2.0）
     * - 非数字值（如 "low", "high"）: 使用 thinkingLevel（适用于 Gemini 3.0）
     *
     * 两种方式互斥：同一时间只能使用 thinkingBudget 或 thinkingLevel。
     * includeThoughts 始终设为 true，以便获取模型的推理过程。
     * 值为 null（级别缺失/NONE 缺省）→ 空 thinkingConfig（不发 thinking 参数）。
     *
     * @param value 从 [ReasoningParameter.levels] 解析出的 API 值。
     * @return [GoogleThinkingConfig] 实例。
     */
    private fun buildGoogleThinkingConfig(value: String?): GoogleThinkingConfig {
        if (value == null) return GoogleThinkingConfig()

        // 尝试解析为数字 -> thinkingBudget（Gemini 2.0）
        value.toIntOrNull()?.let { budget ->
            return GoogleThinkingConfig(
                includeThoughts = true,
                thinkingBudget = budget
            )
        }

        // 非数字 -> thinkingLevel（Gemini 3.0）
        val thinkingLevel = when (value.lowercase()) {
            "low" -> GoogleThinkingLevel.LOW
            "high" -> GoogleThinkingLevel.HIGH
            else -> null
        }

        return GoogleThinkingConfig(
            includeThoughts = true,
            thinkingLevel = thinkingLevel
        )
    }
}
