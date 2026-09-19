package xyz.mederi.provider

import kotlinx.coroutines.runBlocking
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.store.InMemoryApiKeyStore
import xyz.mederi.store.InMemoryProviderStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 供应商按指定 keyId 取明文 key（key 选择器的核心取值逻辑）。
 * 保证：选定 key 优先、缺省回退默认、跨供应商/不存在的 key 返回 null。
 */
class ApiKeySelectionTest {

    @Test
    fun selectedKeyResolvesAndDefaultsFallback() = runBlocking {
        val manager = ProviderManagerImpl(InMemoryProviderStore(), InMemoryApiKeyStore())
        val provider = manager.create(
            name = "P", type = ProviderType.OPENAI_CHAT, baseUrl = "https://example.com",
            reasoningParameter = null, responseSanitization = false
        )

        val primary = manager.addKey(provider.id, "Primary", "sk-primary-123", isDefault = true)
        val backup = manager.addKey(provider.id, "Backup", "sk-backup-456", isDefault = false)

        // 选定 backup → 用它
        assertEquals("sk-backup-456", manager.getKeyValue(provider.id, backup.id))
        // 未选定（null）→ 回退默认 key
        assertEquals("sk-primary-123", manager.getDefaultKeyValue(provider.id))
        // 选定 primary 同样可解析
        assertEquals("sk-primary-123", manager.getKeyValue(provider.id, primary.id))
    }

    @Test
    fun wrongProviderOrMissingKeyReturnsNull() = runBlocking {
        val manager = ProviderManagerImpl(InMemoryProviderStore(), InMemoryApiKeyStore())
        val a = manager.create(
            name = "A", type = ProviderType.OPENAI_CHAT, baseUrl = "https://a.example",
            reasoningParameter = null, responseSanitization = false
        )
        val b = manager.create(
            name = "B", type = ProviderType.OPENAI_CHAT, baseUrl = "https://b.example",
            reasoningParameter = null, responseSanitization = false
        )
        val key = manager.addKey(a.id, "KeyA", "sk-aaa", isDefault = true)

        // 不存在的 keyId
        assertNull(manager.getKeyValue(a.id, "key_missing"))
        // keyId 属于别的供应商（归属校验）
        assertNull(manager.getKeyValue(b.id, key.id))
    }
}