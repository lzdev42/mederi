package xyz.mederi.mcp.servers.domain

import kotlinx.serialization.Serializable

/**
 * install / update 的结果。
 *
 * [errors] 非空 = 全部失败（all-or-nothing，一条格式错就不入库任何条目）；
 * [success] = errors 为空且 installed 非空（或 update 场景下替换完成）。
 */
@Serializable
data class McpInstallResult(
    /** 成功入库（或替换）的 server 名称列表。 */
    val installed: List<String>,
    /** 格式错误清单（逐条，人类可读，带条目名前缀）。 */
    val errors: List<String>
) {
    val success: Boolean get() = errors.isEmpty()
}

/**
 * 已安装 MCP server 的列表摘要（UI 列表展示用）。
 */
@Serializable
data class McpServerInfo(
    val name: String,
    val enabled: Boolean,
    /** "stdio" 或 "remote"。 */
    val kind: String,
    /** 单行摘要：命令行或 URL。 */
    val summary: String
)
