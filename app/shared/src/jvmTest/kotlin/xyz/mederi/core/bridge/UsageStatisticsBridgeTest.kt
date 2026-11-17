package xyz.mederi.core.bridge

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import xyz.mederi.core.contract.models.ApiKeyOption
import xyz.mederi.core.contract.models.ProtocolType
import xyz.mederi.core.contract.models.ProviderConfig
import xyz.mederi.core.contract.models.ProviderType
import xyz.mederi.core.contract.models.UsageStatGroup
import xyz.mederi.core.contract.models.UsageStatistics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 跨会话累计统计的桥层拼装：账本只存 apiKeyId，展示名由当前供应商配置 join；
 * 真 core 冒烟验证 [MederiAiCore.getUsageStatistics] 走的是账本而不是契约默认 failure。
 */
class UsageStatisticsBridgeTest {

    private fun provider(id: String, keys: List<ApiKeyOption>) = ProviderConfig(
        id = id,
        name = id,
        type = ProviderType.Custom,
        baseUrl = null,
        isConnected = true,
        models = emptyList(),
        supportsApiKey = true,
        supportsBaseUrl = true,
        protocolType = ProtocolType.DEFAULT,
        apiKeys = keys,
    )

    private fun group(providerId: String?, apiKeyId: String?) = UsageStatGroup(
        providerId = providerId,
        providerName = null,
        apiKeyId = apiKeyId,
        apiKeyName = null,
        modelId = "m",
        modelName = null,
        inputTokens = 1,
        outputTokens = 1,
        cacheReadTokens = null,
        cacheWriteTokens = null,
        successfulRequests = 1,
        totalRequests = 1,
        pricedRequests = 0,
        estimate = null,
    )

    @Test
    fun joinsApiKeyNamesFromCurrentProviderConfigAndLeavesUnknownKeysNull() {
        val providers = listOf(
            provider("gateway", listOf(ApiKeyOption("key_a", "Main Key", "sk-***a", true))),
        )
        val stats = UsageStatistics(
            groups = listOf(
                group("gateway", "key_a"),      // 存在 → 回填展示名
                group("gateway", "__rotate__"), // 轮询哨兵值不是真 key → null（UI 显示「轮询」）
                group("gateway", "key_deleted"),// 已删 key → null（UI 回落 keyId）
                group("other", "key_a"),        // 供应商不匹配 → null
                group(null, "key_a"),           // 旧记录无供应商维度 → null
                group("gateway", null),         // 默认/未记录 → null
            ),
            totalRequests = 6,
        )

        val joined = joinApiKeyNames(stats, providers)

        assertEquals("Main Key", joined.groups[0].apiKeyName)
        assertNull(joined.groups[1].apiKeyName)
        assertNull(joined.groups[2].apiKeyName)
        assertNull(joined.groups[3].apiKeyName)
        assertNull(joined.groups[4].apiKeyName)
        assertNull(joined.groups[5].apiKeyName)
        assertEquals(listOf("key_a", "__rotate__", "key_deleted", "key_a", "key_a", null), joined.groups.map { it.apiKeyId })
    }

    @Test
    fun realCoreReturnsLedgerBackedStatisticsInsteadOfContractFallback() = runBlocking {
        val scratch = Path.of("../../temp").createDirectories()
        val configDir = createTempDirectory(scratch, "usage-statistics-").toFile()
        val core = MederiAiCore(configDir.absolutePath)
        try {
            core.initialize().getOrThrow()
            val stats = core.getUsageStatistics().getOrThrow()
            // 空库 = 无分组、无估价（不造 0.0），而不是契约默认的 UnsupportedOperationException
            assertTrue(stats.groups.isEmpty())
            assertNull(stats.totalEstimatedCost)
            assertEquals(0, stats.pricedRequests)
            assertEquals(0, stats.totalRequests)
            assertEquals("USD", stats.currency)
        } finally {
            core.shutdown()
            configDir.deleteRecursively()
        }
    }
}
