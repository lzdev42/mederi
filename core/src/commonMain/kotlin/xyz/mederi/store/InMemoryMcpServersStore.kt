package xyz.mederi.store

import xyz.mederi.mcp.servers.domain.McpServerConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存版 [McpServersStore]（不持久化）。
 *
 * 用于无 configDir 的纯内存模式与测试。
 */
class InMemoryMcpServersStore : McpServersStore {

    private val cache = ConcurrentHashMap<String, McpServerConfig>()

    override suspend fun list(): List<McpServerConfig> = cache.values.toList()

    override suspend fun get(name: String): McpServerConfig? = cache[name]

    override suspend fun save(config: McpServerConfig) {
        cache[config.name] = config
    }

    override suspend fun delete(name: String) {
        cache.remove(name)
    }
}
