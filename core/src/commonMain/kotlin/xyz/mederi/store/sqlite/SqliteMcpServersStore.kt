package xyz.mederi.store.sqlite

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.mederi.db.config.MederiConfigDatabase
import xyz.mederi.mcp.servers.domain.McpServerConfig
import xyz.mederi.store.McpServersStore

/**
 * 基于 SQLDelight + SQLite（配置库 config.db）的 McpServersStore 实现。
 *
 * McpServerConfig 整行 JSON 存 payload 列（raw 是任意 JsonElement，天然整行存），
 * name 为主键（保存语义：以 name 为键 upsert）。
 *
 * @param driver 配置库 driver（由 Mederi 装配层创建并共享）。
 */
class SqliteMcpServersStore(driver: SqlDriver) : McpServersStore {

    private val queries = MederiConfigDatabase(driver).configDatabaseQueries
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun list(): List<McpServerConfig> = withContext(Dispatchers.IO) {
        queries.listMcpServers().executeAsList().map { decode(it.payload) }
    }

    override suspend fun get(name: String): McpServerConfig? = withContext(Dispatchers.IO) {
        queries.getMcpServer(name).executeAsOneOrNull()?.let { decode(it.payload) }
    }

    override suspend fun save(config: McpServerConfig): Unit = withContext(Dispatchers.IO) {
        queries.upsertMcpServer(
            name = config.name,
            enabled = if (config.enabled) 1L else 0L,
            payload = json.encodeToString(McpServerConfig.serializer(), config)
        )
    }

    override suspend fun saveAll(configs: List<McpServerConfig>): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            configs.forEach { config ->
                queries.upsertMcpServer(
                    name = config.name,
                    enabled = if (config.enabled) 1L else 0L,
                    payload = json.encodeToString(McpServerConfig.serializer(), config)
                )
            }
        }
    }

    override suspend fun delete(name: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteMcpServer(name)
    }

    private fun decode(payload: String): McpServerConfig =
        json.decodeFromString(McpServerConfig.serializer(), payload)
}
