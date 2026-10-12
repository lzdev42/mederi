package xyz.mederi.core.contract.dto

import kotlinx.serialization.Serializable
import xyz.mederi.core.contract.models.AgentOption
import xyz.mederi.core.contract.models.ModelOption

/**
 * 更新会话输入框设置（不依赖发送即落库会话级配置）。
 *
 * 用于把输入框设置（模型 / Agent / API Key / 推理档位 / 草稿）改为会话级：
 * 选择器改动时若已绑定会话，立即把改动写到该会话，切换会话后仍保留。
 *
 * 全量覆盖写：调用方每次传入所有字段的当前值，null/空值直接写入（清空语义），
 * 不再 COALESCE 保留旧值（core 侧 [SessionStore.updateComposeState] 做 SET）。
 *
 * @param model 选中的模型；null = 清空（写 null）。
 * @param agent 选中的 Agent；null = 清空（写 null）。
 * @param apiKeyId 选中的供应商 API Key ID；null = 清空（写 null）。
 * @param thinkingLevel 推理档位；null = 清空（写 null）。
 * @param draftText 用户已输入但未发送的草稿文本，默认空字符串。
 */
@Serializable
data class UpdateConversationSettingsInput(
    val model: ModelOption? = null,
    val agent: AgentOption? = null,
    val apiKeyId: String? = null,
    val thinkingLevel: String? = null,
    val draftText: String = "",
)
