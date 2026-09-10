package xyz.mederi.metadata

import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * 模型元数据（来自 models.dev 公开目录）。
 *
 * 价格为**参考价**：目录记录的是各平台官方目录价，不知道用户实际的 plan/折扣，
 * 只用于预估成本的量级参考，不代表真实账单。
 *
 * 所有字段可空：null = 目录没有该项信息（同步时保留本地值，不兜底）。
 */
data class ModelMetadata(
    /** 上下文窗口（输入 token 上限）。 */
    val contextWindow: Int? = null,
    /** 最大输出 token 数。 */
    val maxOutputTokens: Int? = null,
    /** 输入参考价（美元 / 百万 token）。 */
    val inputPricePerMillion: Double? = null,
    /** 输出参考价（美元 / 百万 token）。 */
    val outputPricePerMillion: Double? = null,
    /** 是否支持图片输入。null = 目录未标注。 */
    val supportsImages: Boolean? = null,
    /** 是否支持推理。null = 目录未标注。 */
    val supportsReasoning: Boolean? = null,
    /** 推理档位（供思考菜单展示）。空列表 = 无档位信息。 */
    val reasoningLevels: List<ReasoningLevel> = emptyList(),
    /** 推理控制方式（effort 档位型 / toggle 开关型 / budget 预算型）。 */
    val reasoningOptionType: ReasoningOptionType? = null
)

/**
 * 模型推理控制方式，对应 models.dev 的 reasoning_options 类型。
 */
enum class ReasoningOptionType {
    /** 档位型：low/medium/high…（GPT-5、Claude、Gemini 等）。 */
    EFFORT,

    /** 开关型：只有开/关两档，映射 [NONE, HIGH]（Kimi K2.5、Qwen3/Agnes 等）。 */
    TOGGLE,

    /** 预算型：按 token 预算控制（Anthropic extended thinking）。 */
    BUDGET
}
