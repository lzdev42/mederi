package xyz.mederi.domain.model

import kotlinx.serialization.Serializable
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * AI 模型统一描述。
 *
 * 在 Mederi 中所有涉及模型信息的地方都使用 AIModel：
 * - Provider.models：供应商提供的可用模型列表（目录）
 * - Agent.aiModel：Agent 预设的默认模型配置
 * - Session.aiModel：用户最后一次发消息所使用的模型配置（快照）
 *
 * 当作为 Provider 目录条目时，[reasoningLevel] 通常是默认值 NONE。
 * 当作为 Session 快照时，[reasoningLevel] 记录用户最后一次选择的推理级别，
 * 调用方可据此在 UI 上自动选中上一次的模型和参数。
 *
 * @param id 系统生成的模型唯一 ID（如 "mdl_xxx"）。
 * @param providerModelId 供应商侧的模型标识符（如 "gpt-4o"）。
 * @param name 模型显示名称。
 * @param supportsReasoning 是否支持 reasoning。
 * @param reasoningLevel 推理级别，默认 NONE。
 * @param isEnabled 是否在聊天模型选择器中显示（用户可开关，默认显示）。
 * @param inputPricePerMillion 输入参考价（美元/百万 token，来自 models.dev 目录）。
 *        是参考价不是真实账单价（不知道用户 plan），仅供预估成本参考。
 * @param outputPricePerMillion 输出参考价（美元/百万 token）。
 */
@Serializable
data class AIModel(
    val id: String,
    val providerModelId: String,
    val name: String,
    val supportsReasoning: Boolean = false,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.NONE,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val supportsImages: Boolean = false,
    val reasoningLevels: List<ReasoningLevel> = emptyList(),
    val isEnabled: Boolean = true,
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null
)
