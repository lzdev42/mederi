package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

/**
 * MCP Server 状态契约枚举。
 */
@Serializable
enum class McpServerStatus {
    UNCHECKED,
    OK,
    FAILED
}

/**
 * 跨平台 MCP Server 列表展示与管理条目。
 */
@Serializable
data class McpServerItem(
    val name: String,
    val enabled: Boolean,
    val kind: String = "stdio",
    val summary: String = "",
    val status: McpServerStatus = McpServerStatus.UNCHECKED,
    val toolCount: Int? = null,
    val lastError: String? = null
)
