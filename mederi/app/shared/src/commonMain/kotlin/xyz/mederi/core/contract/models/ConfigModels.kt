package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

/**
 * 模型来源（元数据所有权标记，与 core 的 ModelOrigin 枚举一一对应）：
 * - FETCHED：/models 端点拉取 + 模型目录合并产生。元数据权威 = 端点/目录，UI 元数据只读。
 * - MANUAL：用户手动添加。元数据权威 = 用户，UI 元数据可编辑。
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
 * @param origin 元数据所有权（见 [ModelOrigin]），决定设置页编辑对话框元数据是否只读。
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
)

/**
 * Agent 执行策略，与 core 的 AgentMode 枚举名称一一对应。
 * APPROVAL：审批模式，每次需求先列计划等用户批准。
 * AUTONOMOUS：自主模式，AI 自主判断直接执行。
 */
enum class AgentMode {
    APPROVAL,
    AUTONOMOUS
}

/**
 * Agent 工作用途，与 core 的 WorkType 枚举名称一一对应。
 * WORK：非程序员（文档处理、总结资料、协助创作）。
 * CODE：程序员（写代码、调试、工程实现）。
 */
enum class WorkType {
    WORK,
    CODE
}

/**
 * UI 层的 Agent 选项，捆绑 AgentMode + WorkType 两个正交维度。
 *
 * 不存在自定义 Agent。Agent = 运行时实例，由 AgentMode + WorkType 配置行为。
 */
@Serializable
data class AgentOption(
    val id: String,
    val name: String,
    val description: String?,
    val mode: AgentMode,
    val workType: WorkType = WorkType.CODE,
    val model: ModelOption? = null,
    val reasoningLevel: String? = null,
    val systemPrompt: String? = null,
    val tools: List<String> = emptyList(),
)
