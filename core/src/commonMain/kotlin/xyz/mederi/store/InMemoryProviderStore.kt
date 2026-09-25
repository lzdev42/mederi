package xyz.mederi.store

import xyz.mederi.provider.domain.model.Provider

/**
 * 仅内存的 ProviderStore 实现。
 *
 * 用 mutableMapOf 持有数据，save 做 upsert，list 返回快照副本（防御外部修改）。
 * 未指定 configDir 且未显式注入 ProviderStore 时使用。
 *
 * 不持久化：实例销毁后数据丢失。两个独立实例互不可见。
 */
class InMemoryProviderStore : ProviderStore {

    private val providers = mutableMapOf<String, Provider>()

    override suspend fun list(): List<Provider> = providers.values.toList()

    override suspend fun get(id: String): Provider? = providers[id]

    override suspend fun save(provider: Provider) {
        providers[provider.id] = provider
    }

    override suspend fun delete(id: String) {
        providers.remove(id)
    }
}
