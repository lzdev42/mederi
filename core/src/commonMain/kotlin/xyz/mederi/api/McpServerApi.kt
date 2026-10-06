package xyz.mederi.api

import xyz.mederi.mcp.servers.domain.McpDiscoveryResult
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
 *
 * @PendingContractSurface 契约上拋状态（2026-10）：install/list/getJson/update/setEnabled/delete 已上拋到 AiCore 契约；
 * [discover] / [verify] / [verifyAll] / [verifyConfig] 暂未上拋（与 [McpMarketApi] 同因：依赖 bug 未修完）。
 * 待依赖修复后由后续功能计划补齐契约四处同步，非被遗弃能力。
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

    /**
     * 发现：连接该 server 并返回它提供的全部工具（工具名带 server 名前缀，即注册给 LLM 的名字）。
     * 连接失败返回 [McpDiscoveryResult.ok]=false 且 error 给出原因。
     */
    suspend fun discover(name: String): McpDiscoveryResult

    /** 验证已安装 server 是否可用（真连接 initialize + 工具列表）。结果写入状态缓存。 */
    suspend fun verify(name: String): McpVerifyResult

    /** 验证全部已启用 server（并行），返回 name → 结果，并写入状态缓存。 */
    suspend fun verifyAll(): Map<String, McpVerifyResult>

    /** 安装前验证：验证尚未安装的 mcpServers JSON 是否可连接。 */
    suspend fun verifyConfig(mcpServersJson: String): McpVerifyResult
}
