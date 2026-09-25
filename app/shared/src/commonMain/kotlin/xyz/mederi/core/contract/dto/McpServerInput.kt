package xyz.mederi.core.contract.dto

import kotlinx.serialization.Serializable

// ==========================================
// MCP Server 管理请求/响应 DTO（遥控 REST 层）
// ==========================================

/** 安装 MCP server：用户粘贴的标准 mcpServers JSON 原样传入（core 解析，UI 不解析）。 */
@Serializable
data class InstallMcpServerInput(val json: String)

/** 编辑回写：替换某条目的新 mcpServers JSON（条目键须与 path 中的 name 一致）。 */
@Serializable
data class UpdateMcpServerInput(val json: String)

/** 启用/禁用。 */
@Serializable
data class SetMcpServerEnabledInput(val enabled: Boolean)

/** getMcpServerJson 的响应包装（String 直出 JSON 序列化会带引号，包一层类型安全）。 */
@Serializable
data class McpServerJsonResponse(val json: String)
