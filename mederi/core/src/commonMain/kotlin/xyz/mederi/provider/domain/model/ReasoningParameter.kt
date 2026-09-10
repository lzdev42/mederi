package xyz.mederi.provider.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 推理参数配置（可序列化，用于 JSON 配置）。
 *
 * ## 设计（v6：用户填什么发什么）
 *
 * 每个级别对应一段**用户自己编写的请求体 JSON 片段**，发送请求时**根级合并**进请求体
 * （通过 Koog additionalProperties）。我们不对供应商参数做任何语义解释：
 *
 * - OpenAI Responses 型：LOW 档填 `{"reasoning":{"effort":"low"}}`
 * - Qwen3 开关型：`{"enable_thinking":true}`（顶层布尔，每档都带开关状态）
 * - Anthropic 式嵌套：`{"thinking":{"type":"enabled","budget_tokens":2000}}`
 * - Agnes 型（无级别概念，常开开关）：NONE 档填 `{"chat_template_kwargs":{"enable_thinking":true}}`
 *
 * ## NONE 语义
 *
 * NONE 档缺省为 null = 不发推理参数（大多数 API：不发即关）。
 * 奇葩端点需要显式关闭时，在 NONE 档填关闭参数（如 `{"enable_thinking":false}`）。
 *
 * ## 兼容字段（已降级，仅用于读取旧配置）
 *
 * - [parameterName]：旧版"标量值挂参数名"机制（如 levels[HIGH]="high" + parameterName="reasoning_effort"
 *   → 注入 `{"reasoning_effort":"high"}`）。新配置使用完整 JSON 片段，此字段留空。
 * - [customRequestBody]：旧版"级别无关静态合并"。新配置把级别无关参数直接写进各档位片段。
 *
 * ## Google 特例
 *
 * GoogleParams 的 thinkingConfig 是强类型参数，不支持根级合并，因此 GOOGLE 类型供应商的
 * [levels] 值仍为标量：数字字符串 → thinkingBudget，"low"/"high" → thinkingLevel。
 *
 * @param levels 级别 → 请求体 JSON 片段。值为 null 或键缺失表示该级别不发参数。
 * @param parameterName 旧版标量值的挂载参数名，新配置留空。
 * @param customRequestBody 旧版级别无关静态参数，始终合并（不覆盖级别片段已写入的 key）。
 */
@Serializable
data class ReasoningParameter(
    val levels: Map<ReasoningLevel, String?>,
    val parameterName: String = "",
    val customRequestBody: Map<String, String> = emptyMap()
) {
    /**
     * 获取指定级别对应的原始值（JSON 片段或标量），null/缺失表示不发参数。
     */
    fun resolve(level: ReasoningLevel): String? = levels[level]

    /**
     * 是否配置了指定级别。
     */
    fun supportsLevel(level: ReasoningLevel): Boolean = level in levels

    /**
     * 获取所有可选的推理级别（不包括 NONE）。
     * 对话界面据此显示级别选项。
     */
    val availableLevels: Set<ReasoningLevel>
        get() = levels.keys.filter { it != ReasoningLevel.NONE }.toSet()

    /**
     * 写入前校验：所有以 "{" 开头的级别值必须是合法 JSON 对象。
     *
     * @return 非法时返回错误描述，合法返回 null。
     */
    fun validate(): String? {
        for ((level, value) in levels) {
            val trimmed = value?.trim() ?: continue
            if (trimmed.startsWith("{") && parseJsonObject(trimmed) == null) {
                return "级别 ${level.name} 的推理参数不是合法 JSON 对象: $trimmed"
            }
        }
        return null
    }

    companion object {
        private val json = Json

        /**
         * 将字符串解析为任意 JSON 元素，失败返回 null。
         */
        fun parseJsonElement(raw: String): JsonElement? = try {
            json.parseToJsonElement(raw)
        } catch (_: Exception) {
            null
        }

        /**
         * 将字符串解析为 JSON 对象，失败（含非对象类型）返回 null。
         */
        fun parseJsonObject(raw: String): JsonObject? =
            parseJsonElement(raw) as? JsonObject

        /**
         * OpenAI Chat Completions 默认推理配置。
         * 每档为根级合并的请求体片段；NONE 不发参数（不发即关）。
         */
        fun forOpenAIChat(): ReasoningParameter = ReasoningParameter(
            levels = mapOf(
                ReasoningLevel.NONE to null,
                ReasoningLevel.LOW to """{"reasoning_effort":"low"}""",
                ReasoningLevel.MEDIUM to """{"reasoning_effort":"medium"}""",
                ReasoningLevel.HIGH to """{"reasoning_effort":"high"}""",
                ReasoningLevel.MAX to """{"reasoning_effort":"max"}"""
            )
        )

        /**
         * OpenAI Responses API 默认推理配置。
         * effort 取值随模型而异（none/minimal/low/medium/high/xhigh/max），端点不支持时
         * 用户可编辑对应档位或取消勾选该级别。
         */
        fun forOpenAIResponses(): ReasoningParameter = ReasoningParameter(
            levels = mapOf(
                ReasoningLevel.NONE to null,
                ReasoningLevel.LOW to """{"reasoning":{"effort":"low"}}""",
                ReasoningLevel.MEDIUM to """{"reasoning":{"effort":"medium"}}""",
                ReasoningLevel.HIGH to """{"reasoning":{"effort":"high"}}""",
                ReasoningLevel.MAX to """{"reasoning":{"effort":"max"}}"""
            )
        )

        /**
         * Google Gemini 默认推理配置。
         * Google 走标量解释（数字 → thinkingBudget，"low"/"high" → thinkingLevel），不支持 JSON 片段。
         * Google 只支持 LOW/HIGH。
         */
        fun forGoogle(): ReasoningParameter = ReasoningParameter(
            parameterName = "thinkingConfig",
            levels = mapOf(
                ReasoningLevel.NONE to null,
                ReasoningLevel.LOW to ReasoningEffort.LOW,
                ReasoningLevel.HIGH to ReasoningEffort.HIGH
            )
        )

        /**
         * 根据供应商类型创建默认推理配置。
         */
        fun forType(type: ProviderType): ReasoningParameter = when (type) {
            ProviderType.OPENAI_CHAT -> forOpenAIChat()
            ProviderType.OPENAI_RESPONSES -> forOpenAIResponses()
            ProviderType.GOOGLE -> forGoogle()
        }
    }
}
