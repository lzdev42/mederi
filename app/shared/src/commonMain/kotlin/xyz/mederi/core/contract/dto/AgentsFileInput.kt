package xyz.mederi.core.contract.dto

import kotlinx.serialization.Serializable

// ==========================================
// AGENTS.md 生成请求/响应 DTO（遥控 REST 层）
// ==========================================

/** 生成 AGENTS.md：modelId 可选，缺省用项目最近会话的模型。 */
@Serializable
data class GenerateAgentsFileInput(val modelId: String? = null)

/** generateAgentsFile 的响应包装（String 直出 JSON 序列化会带引号，包一层类型安全）。 */
@Serializable
data class GenerateAgentsFileResponse(val content: String)
