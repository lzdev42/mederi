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
 *
 * [status] 等运行时字段是内存缓存（由 Manager 维护），不是配置——config.db 只存配置，
 * 重启后归零为 [McpServerStatus.UNCHECKED]，下次 session 启动 / 手动 verify 回填。
 */
@Serializable
data class McpServerInfo(
    val name: String,
    val enabled: Boolean,
    /** "stdio" 或 "remote"。 */
    val kind: String,
    /** 单行摘要：命令行或 URL。 */
    val summary: String,
    /** 最近一次可用性检查结果（内存缓存）。 */
    val status: McpServerStatus = McpServerStatus.UNCHECKED,
    /** 最近一次检查发现的工具数（连接成功时）。 */
    val toolCount: Int? = null,
    /** 最近一次检查的失败原因。 */
    val lastError: String? = null,
    /** 最近一次检查时间（ISO-8601）。 */
    val lastCheckedAt: String? = null
)

/** MCP server 可用性状态（运行时缓存，非配置）。 */
@Serializable
enum class McpServerStatus {
    /** 尚未检查（刚安装 / core 重启后）。 */
    UNCHECKED,
    /** 最近一次检查连接成功。 */
    OK,
    /** 最近一次检查连接失败。 */
    FAILED
}

/** 单个 MCP server 提供的工具摘要（发现结果用）。 */
@Serializable
data class McpToolInfo(
    /** 注册给 LLM 的工具名（含 server 名前缀，如 "context7_search"）。 */
    val name: String,
    /** 工具描述（来自 server 的 tool description）。 */
    val description: String? = null
)

/** 发现结果：连接 server 后拿到的工具清单。失败时 [ok]=false 且 [error] 给出原因。 */
@Serializable
data class McpDiscoveryResult(
    val ok: Boolean,
    val tools: List<McpToolInfo> = emptyList(),
    val error: String? = null
)
