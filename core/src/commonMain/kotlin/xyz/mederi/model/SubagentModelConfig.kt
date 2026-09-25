package xyz.mederi.domain.model

import kotlinx.serialization.Serializable
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * 单个子代理角色的模型配置。
 *
 * @param modelId 选用的模型 ID（对应 [AIModel.id]），为 null 时表示继承父会话模型。
 * @param reasoningLevel 选用的推理等级，为 null 时表示继承父会话推理等级。
 */
@Serializable
data class SubagentModelConfig(
    val modelId: String? = null,
    val reasoningLevel: ReasoningLevel? = null
) {
    val isInheriting: Boolean
        get() = modelId == null && reasoningLevel == null
}
