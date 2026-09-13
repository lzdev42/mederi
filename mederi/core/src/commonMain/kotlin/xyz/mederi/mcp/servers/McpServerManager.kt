package xyz.mederi.mcp.servers

import xyz.mederi.mcp.servers.domain.McpServerConfig
import xyz.mederi.mcp.servers.domain.McpServerInfo
import xyz.mederi.mcp.servers.domain.McpInstallResult
import xyz.mederi.mcp.servers.domain.McpVerifyResult
import xyz.mederi.mcp.servers.domain.McpDiscoveryResult

/**
 * MCP server 配置管理（唯一真理源）。
 *
 * 对外契约：标准 mcpServers JSON 进，配置查询/管理出。
 * 只验格式不验值；内核 MCP 引擎（[xyz.mederi.mcp.engine.McpConnector]）直接消费
 * [McpServersStore] 里的结构化配置。
 *
 * [McpServerInfo.status] 等运行时状态由本 Manager 内存缓存（每次 install/update/启用/
 * verify 自动更新），不进 config.db。
 */
interface McpServerManager {

    /**
     * 安装：解析标准 mcpServers JSON（可一次多条），格式校验通过后全部入库（新装或覆盖同名）。
     * 成功后对每个新装/覆盖的 server 自动做一次可用性检查并缓存结果。
     * 格式错误 → 返回含逐条错误清单的结果（all-or-nothing，不入库任何条目）。
     */
    suspend fun install(mcpServersJson: String): McpInstallResult

    /** 已安装 MCP server 列表（摘要 + 最近一次可用性检查状态）。 */
    suspend fun list(): List<McpServerInfo>

    /** 取单个配置；不存在抛 [NoSuchElementException]。 */
    suspend fun requireConfig(name: String): McpServerConfig

    /** 取单个 server 的标准 mcpServers JSON 文档（编辑场景的数据源）。不存在抛 [NoSuchElementException]。 */
    suspend fun getJson(name: String): String

    /**
     * 编辑回写：用新的单条 mcpServers JSON 替换 [name] 对应条目。
     * JSON 的条目键必须与 [name] 一致（改名 = 删除 + 重装）；不存在抛 [NoSuchElementException]。
     * enabled 状态保留不变；成功后自动重新检查可用性。
     */
    suspend fun update(name: String, mcpServersJson: String): McpInstallResult

    /** 启用/禁用。禁用后引擎跳过该 server；启用时自动检查可用性。不存在抛 [NoSuchElementException]。 */
    suspend fun setEnabled(name: String, enabled: Boolean)

    /** 删除。不存在抛 [NoSuchElementException]。 */
    suspend fun delete(name: String)

    /**
     * 发现：连接单个 server 并返回它提供的全部工具（带 server 名前缀）。
     * 失败返回 [McpDiscoveryResult.ok]=false 且 [McpDiscoveryResult.error] 给出原因。
     * 不存在抛 [NoSuchElementException]。
     */
    suspend fun discover(name: String): McpDiscoveryResult

    /**
     * 验证已安装 server 是否可用（真连接 initialize + listTools）。不存在抛 [NoSuchElementException]。
     * 结果写入状态缓存。
     */
    suspend fun verify(name: String): McpVerifyResult

    /** 验证全部已启用 server（并行），写入状态缓存，返回 name → 结果。 */
    suspend fun verifyAll(): Map<String, McpVerifyResult>

    /**
     * 验证尚未安装的 mcpServers JSON（安装前探测）。
     * 格式错误 → ok=false 并在 error 中给出原因；多条目时逐条验证，全部成功才 ok。
     */
    suspend fun verifyConfig(mcpServersJson: String): McpVerifyResult
}
