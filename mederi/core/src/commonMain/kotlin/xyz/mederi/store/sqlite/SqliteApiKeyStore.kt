package xyz.mederi.store.sqlite

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.mederi.db.config.MederiConfigDatabase
import xyz.mederi.provider.domain.model.ProviderApiKey
import xyz.mederi.store.ApiKeyStore
import java.time.Instant
import java.util.UUID

/**
 * 基于 SQLDelight + SQLite 的 ApiKeyStore 实现。
 *
 * API 密钥明文存储在配置库（config.db）中，返回时脱敏。
 * 与 providers 同库：provider 行 + key 行同库事务，消灭跨存储不一致。
 *
 * @param driver 配置库 driver（由 Mederi 装配层创建并共享）。
 */
class SqliteApiKeyStore(driver: SqlDriver) : ApiKeyStore {

    private val queries = MederiConfigDatabase(driver).configDatabaseQueries

    override suspend fun listByProvider(providerId: String): List<ProviderApiKey> = withContext(Dispatchers.IO) {
        queries.listByProvider(providerId).executeAsList().map { row ->
            ProviderApiKey(
                id = row.id,
                name = row.name,
                maskedValue = ProviderApiKey.mask(row.key_value),
                isDefault = row.is_default != 0L
            )
        }
    }

    override suspend fun add(
        providerId: String,
        name: String,
        value: String,
        isDefault: Boolean
    ): ProviderApiKey = withContext(Dispatchers.IO) {
        val id = "key_${UUID.randomUUID().toString().take(8)}"
        val now = Instant.now().toString()

        queries.transaction {
            if (isDefault) {
                queries.clearDefault(providerId)
            }
            queries.insertKey(
                id = id,
                provider_id = providerId,
                name = name,
                key_value = value,
                is_default = if (isDefault) 1L else 0L,
                created_at = now
            )
        }

        ProviderApiKey(
            id = id,
            name = name,
            maskedValue = ProviderApiKey.mask(value),
            isDefault = isDefault
        )
    }

    override suspend fun delete(providerId: String, keyId: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteKey(keyId)
    }

    override suspend fun setDefault(providerId: String, keyId: String): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            queries.clearDefault(providerId)
            queries.setDefault(keyId, providerId)
        }
    }

    override suspend fun getDefaultValue(providerId: String): String? = withContext(Dispatchers.IO) {
        queries.getDefaultKey(providerId).executeAsOneOrNull()?.key_value
    }
}
