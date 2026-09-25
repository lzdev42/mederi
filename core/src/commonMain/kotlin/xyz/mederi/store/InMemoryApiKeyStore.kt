package xyz.mederi.store

import xyz.mederi.provider.domain.model.ProviderApiKey

/**
 * 仅内存的 ApiKeyStore 实现。
 *
 * 未指定 configDir 时使用。
 */
class InMemoryApiKeyStore : ApiKeyStore {

    private data class Entry(
        val id: String,
        val providerId: String,
        val name: String,
        val value: String,
        var isDefault: Boolean
    )

    private val keys = mutableListOf<Entry>()

    override suspend fun listByProvider(providerId: String): List<ProviderApiKey> =
        keys.filter { it.providerId == providerId }
            .sortedWith(compareByDescending<Entry> { it.isDefault }.thenBy { it.id })
            .map { entry ->
                ProviderApiKey(
                    id = entry.id,
                    name = entry.name,
                    maskedValue = ProviderApiKey.mask(entry.value),
                    isDefault = entry.isDefault
                )
        }

    override suspend fun add(
        providerId: String,
        name: String,
        value: String,
        isDefault: Boolean
    ): ProviderApiKey {
        if (isDefault) {
            keys.filter { it.providerId == providerId }.forEach { it.isDefault = false }
        }
        val id = "key_${java.util.UUID.randomUUID().toString().take(8)}"
        keys.add(Entry(id, providerId, name, value, isDefault))
        return ProviderApiKey(
            id = id,
            name = name,
            maskedValue = ProviderApiKey.mask(value),
            isDefault = isDefault
        )
    }

    override suspend fun delete(providerId: String, keyId: String) {
        keys.removeAll { it.id == keyId && it.providerId == providerId }
    }

    override suspend fun setDefault(providerId: String, keyId: String) {
        keys.filter { it.providerId == providerId }.forEach { it.isDefault = (it.id == keyId) }
    }

    override suspend fun getDefaultValue(providerId: String): String? =
        keys.find { it.providerId == providerId && it.isDefault }?.value
            ?: keys.find { it.providerId == providerId }?.value

    override suspend fun getValue(providerId: String, keyId: String): String? =
        keys.find { it.id == keyId && it.providerId == providerId }?.value
}
