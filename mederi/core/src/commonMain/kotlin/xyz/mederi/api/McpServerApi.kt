package xyz.mederi.api

import xyz.mederi.mcp.servers.domain.McpInstallResult
import xyz.mederi.mcp.servers.domain.McpServerInfo
import xyz.mederi.mcp.servers.domain.McpVerifyResult

/**
 * MCP server 配置 API（内核侧）。
 *
 * 对外契约：标准 mcpServers JSON 进，配置查询/管理出。
 * 格式错误通过返回值（[McpInstallResult.errors]）反馈，不抛异常；
 * 资源不存在抛 [xyz.mederi.api.exception.MederiNotFoundException]。
 *
 * 与市场模块（[McpMarketApi]）零耦合：市场产出 JSON，这里只消费 JSON。
 */
interface McpServerApi {

    /** 安装（可一次多条）。格式校验失败返回逐条错误清单，all-or-nothing。 */
    suspend fun install(mcpServersJson: String): McpInstallResult

    /** 已安装 MCP server 列表。 */
    suspend fun list(): List<McpServerInfo>

    /** 取单个 server 的标准 mcpServers JSON 文档（编辑场景的数据源）。 */
    suspend fun getJson(name: String): String

    /** 编辑回写：替换指定条目（条目键须与 name 一致，enabled 保留）。 */
    suspend fun update(name: String, mcpServersJson: String): McpInstallResult

    /** 启用/禁用。 */
    suspend fun setEnabled(name: String, enabled: Boolean)

    /** 删除。 */
    suspend fun delete(name: String)

    /** 验证已安装 server 是否可用（initialize 握手）。 */
    suspend fun verify(name: String): McpVerifyResult

    /** 安装前验证：验证尚未安装的 mcpServers JSON 是否可连接。 */
    suspend fun verifyConfig(mcpServersJson: String): McpVerifyResult
}
