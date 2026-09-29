package xyz.mederi.provider

import kotlinx.coroutines.runBlocking
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.store.InMemoryApiKeyStore
import xyz.mederi.store.InMemoryProviderStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [ApiKeyResolver] 的解析兜底链与 per-provider 记忆语义。
 *
 * 覆盖：显式 keyId 归属通过并记忆、归属失败回落记忆、无记忆回落默认、无 key 抛错、
 * 记忆中的 key 被删后回落默认。
 */
class ApiKeyResolverTest {

    private fun newManager() = ProviderManagerImpl(InMemoryProviderStore(), InMemoryApiKeyStore())

    private suspend fun createProvider(manager: ProviderManager, name: String = "P") = manager.create(
        name = name, type = ProviderType.OPENAI_CHAT, baseUrl = "https://$name.example",
        reasoningParameter = null, responseSanitization = false
    )

    @Test
    fun resolveExplicitKeyWinsAndMemorized() = runBlocking {
        val manager = newManager()
        val provider = createProvider(manager)

        val primary = manager.addKey(provider.id, "Primary", "sk-primary-123", isDefault = true)
        val backup = manager.addKey(provider.id, "Backup", "sk-backup-456", isDefault = false)

        val resolver = ApiKeyResolver(manager)

        // 显式请求 backup：归属通过，返回 backup 实体
        val explicit = resolver.resolve(provider.id, backup.id)
        assertEquals(provider.id, explicit.providerId)
        assertEquals(backup.id, explicit.keyId)
        assertEquals("Backup", explicit.name)
        assertEquals("sk-backup-456", explicit.value)

        // 随后不带 keyId：记忆生效，仍返回 backup（而非默认 primary）
        val memorized = resolver.resolve(provider.id, null)
        assertEquals(backup.id, memorized.keyId)
        assertEquals("Backup", memorized.name)
        assertEquals("sk-backup-456", memorized.value)
    }

    @Test
    fun wrongProviderKeyFallsBackToMemory() = runBlocking {
        val manager = newManager()
        val a = createProvider(manager, name = "A")
        val b = createProvider(manager, name = "B")

        val aPrimary = manager.addKey(a.id, "Primary", "sk-a-primary", isDefault = true)
        val aBackup = manager.addKey(a.id, "Backup", "sk-a-backup", isDefault = false)
        val bKey = manager.addKey(b.id, "KeyB", "sk-b", isDefault = true)

        val resolver = ApiKeyResolver(manager)

        // 先显式选 A 的 backup，记入记忆
        assertEquals(aBackup.id, resolver.resolve(a.id, aBackup.id).keyId)

        // 传入属于别的供应商的 keyId → 归属失败 → 回落记忆中的 A.backup（不落默认 aPrimary）
        val fellBack = resolver.resolve(a.id, bKey.id)
        assertEquals(aBackup.id, fellBack.keyId)
        assertEquals("sk-a-backup", fellBack.value)

        // 传入不存在的 keyId 同样回落记忆
        val fellBackMissing = resolver.resolve(a.id, "key_missing")
        assertEquals(aBackup.id, fellBackMissing.keyId)
        assertEquals("sk-a-backup", fellBackMissing.value)

        // 默认 key 未被误选
        assertEquals("sk-a-primary", manager.getDefaultKeyValue(a.id))
        assertEquals("Primary", aPrimary.name)
    }

    @Test
    fun noMemoryFallsBackToDefault() = runBlocking {
        val manager = newManager()
        val provider = createProvider(manager)

        manager.addKey(provider.id, "Primary", "sk-primary-123", isDefault = true)
        manager.addKey(provider.id, "Backup", "sk-backup-456", isDefault = false)

        val resolver = ApiKeyResolver(manager)

        // 从未显式选过：回落默认 key
        val resolved = resolver.resolve(provider.id, null)
        assertEquals(provider.id, resolved.providerId)
        assertNull(resolved.keyId)
        assertEquals("sk-primary-123", resolved.value)
        assertEquals("Primary", resolved.name)
    }

    @Test
    fun noKeyThrows(): Unit = runBlocking {
        val manager = newManager()
        val provider = createProvider(manager)

        val resolver = ApiKeyResolver(manager)

        assertFailsWith<IllegalStateException> {
            resolver.resolve(provider.id, null)
        }
        // 显式请求不存在/无归属的 keyId，且无默认 key，同样抛错
        assertFailsWith<IllegalStateException> {
            resolver.resolve(provider.id, "key_missing")
        }
    }

    @Test
    fun memorizedKeyDeletedFallsBackToDefault() = runBlocking {
        val manager = newManager()
        val provider = createProvider(manager)

        manager.addKey(provider.id, "Primary", "sk-primary-123", isDefault = true)
        val backup = manager.addKey(provider.id, "Backup", "sk-backup-456", isDefault = false)

        val resolver = ApiKeyResolver(manager)

        // 显式选 backup 记入记忆
        assertEquals(backup.id, resolver.resolve(provider.id, backup.id).keyId)

        // 删掉 backup：记忆失效 → 回落默认 key
        manager.deleteKey(provider.id, backup.id)

        val resolved = resolver.resolve(provider.id, null)
        assertNull(resolved.keyId)
        assertEquals("sk-primary-123", resolved.value)
        assertEquals("Primary", resolved.name)
    }
}
