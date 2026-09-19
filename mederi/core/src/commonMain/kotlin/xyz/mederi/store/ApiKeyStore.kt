package xyz.mederi.store

import xyz.mederi.provider.domain.model.ProviderApiKey

/**
 * API 密钥存储接口。
 *
 * 管理 API 密钥的持久化存储。
 * 明文存储在 SQLite 中，返回给调用方时脱敏处理。
 *
 * 内置实现：SqliteApiKeyStore
 * 企业实现：自行实现此接口。
 *
 * 不传此接口 = 不持久化 API 密钥。
 */
interface ApiKeyStore {

    /**
     * 查询供应商的所有密钥（脱敏）。
     *
     * @param providerId 供应商 ID。
     * @return 密钥列表（value 已脱敏）。
     */
    suspend fun listByProvider(providerId: String): List<ProviderApiKey>

    /**
     * 添加密钥。
     *
     * @param providerId 供应商 ID。
     * @param name 密钥名称（如 "Primary"）。
     * @param value 明文 API 密钥。
     * @param isDefault 是否设为默认密钥。
     * @return 创建后的密钥信息（value 已脱敏）。
     */
    suspend fun add(providerId: String, name: String, value: String, isDefault: Boolean): ProviderApiKey

    /**
     * 删除密钥。
     *
     * @param providerId 供应商 ID。
     * @param keyId 密钥 ID。
     */
    suspend fun delete(providerId: String, keyId: String)

    /**
     * 设置默认密钥。
     *
     * @param providerId 供应商 ID。
     * @param keyId 要设为默认的密钥 ID。
     */
    suspend fun setDefault(providerId: String, keyId: String)

    /**
     * 获取供应商的默认密钥明文。
     *
     * 用于 agent 运行时创建 LLM 客户端。
     *
     * @param providerId 供应商 ID。
     * @return 明文 API 密钥，如果没有默认密钥返回 null。
     */
    suspend fun getDefaultValue(providerId: String): String?

    /**
     * 获取供应商指定密钥的明文。
     *
     * 用于 agent 运行时按用户选定的 key 创建 LLM 客户端。
     *
     * @param providerId 供应商 ID。
     * @param keyId 目标密钥 ID；密钥不属于该供应商时返回 null。
     * @return 明文 API 密钥，找不到返回 null。
     */
    suspend fun getValue(providerId: String, keyId: String): String?
}
