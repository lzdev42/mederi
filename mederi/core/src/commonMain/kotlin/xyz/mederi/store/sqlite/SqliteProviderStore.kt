package xyz.mederi.store.sqlite

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.mederi.db.config.MederiConfigDatabase
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.store.ProviderStore

/**
 * 基于 SQLDelight + SQLite（配置库 config.db）的 ProviderStore 实现。
 *
 * Provider 聚合根（含内嵌 models 列表）整行 JSON 存 payload 列（真理源），
 * id/name/type 提升为独立列供查询排序。apiKeys 为 @Transient 不入库，
 * 由 [SqliteApiKeyStore] 管理——同库（config.db），provider+keys 可同事务写。
 *
 * @param driver 配置库 driver（由 Mederi 装配层创建并共享）。
 */
class SqliteProviderStore(driver: SqlDriver) : ProviderStore {

    private val queries = MederiConfigDatabase(driver).configDatabaseQueries
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun list(): List<Provider> = withContext(Dispatchers.IO) {
        queries.listProviders().executeAsList().map { decode(it.payload) }
    }

    override suspend fun get(id: String): Provider? = withContext(Dispatchers.IO) {
        queries.getProvider(id).executeAsOneOrNull()?.let { decode(it.payload) }
    }

    override suspend fun save(provider: Provider): Unit = withContext(Dispatchers.IO) {
        queries.upsertProvider(
            id = provider.id,
            name = provider.name,
            type = provider.type.name,
            payload = json.encodeToString(Provider.serializer(), provider)
        )
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteProvider(id)
    }

    /** 解析失败不兜底：直接抛出，由上层 API 层转换为用户可见错误（写坏即坏，如实暴露）。 */
    private fun decode(payload: String): Provider =
        json.decodeFromString(Provider.serializer(), payload)
}
