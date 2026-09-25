package xyz.mederi.mcp.servers.domain

import kotlinx.serialization.Serializable

/**
 * MCP server 校验结果。
 *
 * [ok] = 协议层握手成功（对端回了合法的 initialize 响应）。
 * 不校验 API key 的业务有效性——握手不需要有效 key 的服务照样判可用，业务语义就是"协议层活着"。
 */
@Serializable
data class McpVerifyResult(
    val ok: Boolean,
    /** 握手耗时（毫秒）。失败时可能是超时值。 */
    val latencyMs: Long,
    /** 成功时对端 initialize 响应中的 serverInfo（name + version），可能为 null。 */
    val serverInfo: String? = null,
    /** 成功时发现（listTools）的工具数。 */
    val toolCount: Int? = null,
    /** 失败原因（人类可读）。 */
    val error: String? = null
) {
    companion object {
        fun success(latencyMs: Long, serverInfo: String? = null, toolCount: Int? = null): McpVerifyResult =
            McpVerifyResult(ok = true, latencyMs = latencyMs, serverInfo = serverInfo, toolCount = toolCount)

        fun failure(latencyMs: Long, error: String): McpVerifyResult =
            McpVerifyResult(ok = false, latencyMs = latencyMs, error = error)
    }
}
