package xyz.mederi.domain.model

import kotlinx.serialization.Serializable
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * 模型来源（元数据所有权标记，硬性规则）：
 * - [FETCHED]：由 /models 端点拉取 + 模型目录合并产生。元数据权威 = 端点/目录，
 *   唯一写入路径是 [xyz.mederi.provider.ModelMerge]（同步合并）；UI 元数据只读。
 * - [MANUAL]：用户手动添加。元数据权威 = 用户，同步（refresh/回填）永不触碰。
 */
@Serializable
enum class ModelOrigin {
    FETCHED,
    MANUAL
}

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
 * @param origin 元数据所有权（见 [ModelOrigin]）。默认 FETCHED 兼容存量数据。
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
    /**
     * 图片能力的用户覆盖（仅 FETCHED 模型有意义，用户权威，同步永不触碰）：
     * - null = 未覆盖，随目录/端点同步
     * - true/false = 用户显式设置，**永远压过目录标注**（目录对长尾/私有模型经常缺数据或标错）
     * 写入唯一入口 = ProviderManager.updateUserModel（UI 图片开关）；MANUAL 模型恒为 null（用户本就是全字段权威）。
     */
    val supportsImagesOverride: Boolean? = null,
    val reasoningLevels: List<ReasoningLevel> = emptyList(),
    val origin: ModelOrigin = ModelOrigin.FETCHED,
    val isEnabled: Boolean = true,
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null
)
