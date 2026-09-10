package xyz.mederi.store

import xyz.mederi.mcp.servers.domain.McpServerConfig

/**
 * MCP server 配置存储（纯持久化，无业务逻辑）。
 *
 * 实现可注入（[xyz.mederi.config.MederiConfig.mcpServerStore]）。
 * 内置实现：[xyz.mederi.store.sqlite.SqliteMcpServersStore]（config.db）、
 * [InMemoryMcpServersStore]。
 *
 * 将来的内核 MCP 引擎直接读本接口获取要连接的服务，与市场模块零耦合。
 */
interface McpServersStore {

    /** 全部已安装的 MCP server 配置。 */
    suspend fun list(): List<McpServerConfig>

    /** 按名称取单个配置；不存在返回 null。 */
    suspend fun get(name: String): McpServerConfig?

    /** 保存（新增或整体替换）。以 [McpServerConfig.name] 为键。 */
    suspend fun save(config: McpServerConfig)

    /**
     * 批量保存（install 多条目场景）。
     * 默认逐条调用 [save]；持久化实现应覆盖为单次写盘（避免 N 条 = N 次 flush）。
     */
    suspend fun saveAll(configs: List<McpServerConfig>) {
        configs.forEach { save(it) }
    }

    /** 删除指定配置；不存在时静默返回。 */
    suspend fun delete(name: String)
}
