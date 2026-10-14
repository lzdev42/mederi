package xyz.mederi.domain.model

import kotlinx.serialization.Serializable
import xyz.mederi.provider.API_KEY_ROTATION_ID
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * 单个子代理角色的模型配置。
 *
 * @param modelId 选用的模型 ID（对应 [AIModel.id]），为 null 时表示继承父会话模型。
 * @param reasoningLevel 选用的推理等级，为 null 时表示继承父会话推理等级。
 * @param apiKeyId 子代理的 API Key 策略：null=继承父会话选定 key；具体 keyId=指定 key；
 *    [API_KEY_ROTATION_ID]=轮询（每次请求按顺序换用该供应商的全部 key）。
 */
@Serializable
data class SubagentModelConfig(
    val modelId: String? = null,
    val reasoningLevel: ReasoningLevel? = null,
    /** 子代理的 API Key 策略：null=继承父会话选定 key；具体 keyId=指定 key；API_KEY_ROTATION_ID=轮询。 */
    val apiKeyId: String? = null
) {
    val isInheriting: Boolean
        get() = modelId == null && reasoningLevel == null && apiKeyId == null
}
