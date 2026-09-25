package xyz.mederi.store.sqlite

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.mederi.db.config.MederiConfigDatabase
import xyz.mederi.store.SettingsStore

/**
 * 基于 SQLDelight + SQLite（配置库 config.db）的 [SettingsStore] 实现。
 *
 * @param driver 配置库 driver（由 Mederi 装配层创建并共享）。
 */
class SqliteSettingsStore(driver: SqlDriver) : SettingsStore {

    private val queries = MederiConfigDatabase(driver).configDatabaseQueries

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        queries.getSetting(key).executeAsOneOrNull()
    }

    override suspend fun set(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        queries.upsertSetting(key, value)
    }

    override suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteSetting(key)
    }
}
