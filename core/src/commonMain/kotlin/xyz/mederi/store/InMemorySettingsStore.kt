package xyz.mederi.store

import java.util.concurrent.ConcurrentHashMap

/**
 * 内存版 [SettingsStore]（不持久化）。
 *
 * 用于无 configDir 的纯内存模式与测试。
 */
class InMemorySettingsStore : SettingsStore {

    private val cache = ConcurrentHashMap<String, String>()

    override suspend fun get(key: String): String? = cache[key]

    override suspend fun set(key: String, value: String) {
        cache[key] = value
    }

    override suspend fun delete(key: String) {
        cache.remove(key)
    }
}
