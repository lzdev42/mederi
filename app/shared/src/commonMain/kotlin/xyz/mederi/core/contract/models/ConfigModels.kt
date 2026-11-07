package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

/**
 * 模型来源（与 core 的 ModelOrigin 枚举一一对应，不限制设置页编辑）：
 * - FETCHED：/models 端点拉取，初始元数据可由模型目录补齐。
 * - MANUAL：用户手动添加；两种来源均可编辑现有元数据。
 */
enum class ModelOrigin {
    FETCHED,
    MANUAL
}

/**
 * UI 层看到的模型选项。
 *
 * @param id core 内部生成的模型配置唯一 ID（查询配置用）。
 * @param providerModelId 发送网络请求时使用的供应商模型 ID。
 * @param origin 模型来源（见 [ModelOrigin]）；两种来源均可在设置页编辑。
 * @param contextWindow 模型上下文窗口大小（token 数），null 表示未知。
 * @param maxTokens 最大输出 token 数，null 表示未设置（用模型默认）。
 * @param reasoningLevels 该模型支持的推理级别列表（从供应商 ReasoningParameter 算），
 *   空列表表示不支持推理。始终包含 "NONE"（即不启用）。
 */
@Serializable
data class ModelOption(
    val id: String,
    val name: String,
    val provider: String,
    val supportsThinking: Boolean,
    val supportsImages: Boolean = false,
    /** 图片能力用户覆盖（仅 FETCHED 模型，null = 未覆盖随目录同步；非 null = 用户显式设置，同步永不覆盖）。 */
    val supportsImagesOverride: Boolean? = null,
    val reasoningLevels: List<String> = emptyList(),
    val providerModelId: String = id,
    val origin: ModelOrigin = ModelOrigin.FETCHED,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    /** 输入参考价（美元/百万 token）。null = 目录未提供。参考价不是真实账单价。 */
    val inputPricePerMillion: Double? = null,
    /** 输出参考价（美元/百万 token）。null = 目录未提供。 */
    val outputPricePerMillion: Double? = null,
    /** 是否在聊天模型选择器中显示（用户可开关） */
    val isEnabled: Boolean = true,
    val optimizeForRequestCount: Boolean = false,
)

/**
 * Agent 执行策略，与 core 的 AgentMode 枚举名称一一对应。
 * APPROVAL：人工审批，每次需求先列计划等用户批准。
 * AUTONOMOUS：自动审批，AI 自动批准计划直接执行。
 */
enum class AgentMode {
    APPROVAL,
    AUTONOMOUS
}

/**
 * UI 层的 Agent 选项，由 AgentMode 维度配置行为。
 *
 * 不存在自定义 Agent。Agent = 运行时实例，由 AgentMode 配置行为。
 */
@Serializable
data class AgentOption(
    val id: String,
    val name: String,
    val description: String?,
    val mode: AgentMode,
    val model: ModelOption? = null,
    val reasoningLevel: String? = null,
    val systemPrompt: String? = null,
    val tools: List<String> = emptyList(),
)
