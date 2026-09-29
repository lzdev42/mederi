package xyz.mederi.provider

import java.util.concurrent.ConcurrentHashMap

/**
 * 已解析出的 API Key 实体。
 *
 * 与 [xyz.mederi.provider.domain.model.ProviderApiKey] 不同：后者只携带脱敏值、供 UI 展示；
 * 本类携带**明文密钥**，仅供 LLM 客户端构造使用，不得回传 UI。
 *
 * @param providerId 该 key 所属的供应商 id。
 * @param keyId 命中的 key 记录 id；未显式选择、走默认 key 时为 null。
 * @param name key 的展示名（用于诊断/日志）；默认 key 查不到名称时回落 "default"。
 * @param value 明文密钥，可直接用于 LLM 客户端。
 */
data class ResolvedApiKey(
    val providerId: String,
    val keyId: String?,
    val name: String,
    val value: String
)

/**
 * API Key 统一解析器（唯一真理源）。
 *
 * 职责：把「调用方请求的 keyId（可为 null）」解析成一个确定的 [ResolvedApiKey] 实体，
 * 并维护 per-provider 的「当前选定 key」进程级记忆。
 *
 * 解析兜底链（严格按此顺序）：
 * 1. requestedKeyId 非空且归属该校验通过 → 用它，并写入记忆；
 * 2. 否则回落进程级记忆（记忆中的 key 仍存在才可用；被删则清除记忆继续往下）；
 * 3. 否则回落到供应商默认 key；
 * 4. 都没有 → 抛 [IllegalStateException]（不静默伪装成功）。
 *
 * 记忆更新语义：**只有**归属校验通过（步骤 1）才写记忆；步骤 2/3 只读不写。
 */
class ApiKeyResolver(
    private val providerManager: ProviderManager
) {

    /**
     * 进程级记忆：providerId -> 当前选定 key 实体。
     *
     * 关键：记忆必须是**进程级静态共享**——生产环境里 TurnExecutor 主实例、子代理
     * TurnExecutor（SubagentRunnerImpl）、BrowserLLMHelper、AgentsFileGenerator 各自
     * new 了独立的 ApiKeyResolver 实例；若记忆是实例字段则互不共享，唯一真理源会被撕裂。
     *
     * 实现：companion object 内按 [ProviderManager] 实例分组的静态 map——生产只有一个
     * manager 实例 → 全进程共享同一份记忆；测试各用各的 InMemory manager → 天然隔离。
     */
    private val currentKeyByProvider: ConcurrentHashMap<String, ResolvedApiKey> =
        sharedCurrentKeyByProvider.computeIfAbsent(providerManager) { ConcurrentHashMap() }

    /**
     * 只读方法：返回指定供应商当前记忆的 keyId（无记忆返回 null）。
     *
     * 供内部消息轮（计划批准执行 / 事件唤醒续轮）构造
     * [xyz.mederi.api.SendMessageRequest] 时填 apiKeyId——让这些程序化发起的 turn
     * 沿用用户当前选定的 key，而非回落默认 key。
     */
    fun currentKeyId(providerId: String): String? = currentKeyByProvider[providerId]?.keyId

    /**
     * 解析指定供应商的可用 API Key。
     *
     * @param providerId 供应商 id。
     * @param requestedKeyId 调用方请求的 keyId；null 表示未显式指定。
     * @return 解析出的明文 key 实体。
     * @throws IllegalStateException 供应商无任何可用 key。
     */
    suspend fun resolve(providerId: String, requestedKeyId: String? = null): ResolvedApiKey {
        // ① 显式请求的 keyId：归属校验通过则用它，并写入记忆
        if (requestedKeyId != null) {
            val value = providerManager.getKeyValue(providerId, requestedKeyId)
            if (value != null) {
                val name = providerManager.listKeys(providerId)
                    .find { it.id == requestedKeyId }?.name ?: ""
                val resolved = ResolvedApiKey(providerId, requestedKeyId, name, value)
                currentKeyByProvider[providerId] = resolved
                return resolved
            }
        }

        // ② 回落进程级记忆：记忆中的 key 仍存在才可用；否则清除记忆继续往下
        val memorized = currentKeyByProvider[providerId]
        if (memorized != null) {
            val memorizedKeyId = memorized.keyId
            if (memorizedKeyId != null) {
                val value = providerManager.getKeyValue(providerId, memorizedKeyId)
                if (value != null) {
                    // 记忆仍有效，返回最新实体（明文以当前取值为准）
                    return memorized.copy(value = value)
                }
            }
            // 记忆的 keyId 为 null，或对应的 key 已被删 —— 清除失效记忆
            currentKeyByProvider.remove(providerId)
        }

        // ③ 回落供应商默认 key
        val defaultValue = providerManager.getDefaultKeyValue(providerId)
        if (defaultValue != null) {
            val name = providerManager.listKeys(providerId)
                .find { it.isDefault }?.name ?: "default"
            return ResolvedApiKey(providerId, keyId = null, name = name, value = defaultValue)
        }

        // ④ 无任何可用 key：不静默伪装成功
        throw IllegalStateException("No API key available for provider: $providerId")
    }

    companion object {
        /**
         * 进程级静态共享记忆：按 [ProviderManager] 实例分组（providerId -> 当前选定 key）。
         *
         * 生产环境只有一个 manager 实例 → 所有 ApiKeyResolver 实例共享同一份记忆；
         * 测试各自的 InMemory manager → 隔离。ConcurrentHashMap 保证线程安全，
         * computeIfAbsent 保证同 manager 只建一个内层 map。
         */
        private val sharedCurrentKeyByProvider =
            ConcurrentHashMap<ProviderManager, ConcurrentHashMap<String, ResolvedApiKey>>()
    }
}
