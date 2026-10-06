package xyz.mederi.core.contract.dto

import kotlinx.serialization.Serializable
import xyz.mederi.core.contract.models.AgentOption
import xyz.mederi.core.contract.models.ModelOption

/**
 * 更新会话输入框设置（不依赖发送即落库会话级配置）。
 *
 * 用于把输入框四项设置（模型 / Agent / API Key / 推理档位）改为会话级：
 * 选择器改动时若已绑定会话，立即把改动写到该会话，切换会话后仍保留。
 *
 * 所有字段可空：null = 不改变该项（与 [ResolvePlanApprovalInput] 的 model/thinkingLevel nullable 语义一致，
 * core 侧 [SessionStore.updateAgentConfig] 用 COALESCE 保留原值）。
 *
 * @param model 选中的模型；null = 不改变。
 * @param agent 选中的 Agent；null = 不改变。
 * @param apiKeyId 选中的供应商 API Key ID；null = 不改变。
 * @param thinkingLevel 推理档位；null = 不改变。
 */
@Serializable
data class UpdateConversationSettingsInput(
    val model: ModelOption? = null,
    val agent: AgentOption? = null,
    val apiKeyId: String? = null,
    val thinkingLevel: String? = null,
)
