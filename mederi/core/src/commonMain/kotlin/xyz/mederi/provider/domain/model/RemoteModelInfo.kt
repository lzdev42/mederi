package xyz.mederi.provider.domain.model

/**
 * 远端拉取的模型信息（不落库的中间结构）。
 *
 * 由 [ProviderManager.fetchRemoteModels] 从供应商 /models 端点解析，
 * 随后在合并前用 models.dev 元数据目录补充缺口（端点给的值优先，目录只填空），
 * 调用方决定如何合并进 Provider.models。
 *
 * 字段可空表示"远端未提供该信息"——合并时保留本地已有值，不覆盖。
 *
 * @param providerModelId 供应商侧模型标识（如 "gpt-4o"、"gemini-2.5-flash"）。
 * @param name 模型显示名称（远端 displayName），无则与 providerModelId 相同。
 * @param contextWindow 上下文窗口（Gemini inputTokenLimit / vLLM max_model_len）。
 * @param maxTokens 最大输出 token（Gemini outputTokenLimit）。
 * @param supportsReasoning 是否支持推理（Gemini thinking 字段）。null = 远端未提供。
 * @param inputPricePerMillion 输入参考价（美元/百万 token）。null = 未提供。
 * @param outputPricePerMillion 输出参考价（美元/百万 token）。null = 未提供。
 * @param supportsImages 是否支持图片输入。null = 未提供（只有目录提供）。
 * @param reasoningLevels 推理档位（目录 reasoning_options 映射）。空 = 未提供。
 */
data class RemoteModelInfo(
    val providerModelId: String,
    val name: String,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val supportsReasoning: Boolean? = null,
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    val supportsImages: Boolean? = null,
    val reasoningLevels: List<ReasoningLevel> = emptyList()
)
