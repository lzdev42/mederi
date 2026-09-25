package xyz.mederi.store

import xyz.mederi.provider.domain.model.Provider

/**
 * 供应商配置存储接口。
 *
 * 管理 Provider 聚合根（含内嵌的 models 列表）的持久化。
 * API 密钥不在此存储，由 [ApiKeyStore] 独立管理，读取时由上层合并。
 *
 * 接口只传递 [Provider] 领域对象，不假设任何存储格式。
 * 内置实现：SqliteProviderStore（config.db）、InMemoryProviderStore（内存）。
 * 企业实现：自行实现此接口，用 PostgreSQL / MySQL / MongoDB 等。
 *
 * 注入此接口 = 注入方接管 Provider/Model 的存储；存成什么格式是注入方的私事。
 */
interface ProviderStore {

    /**
     * 查询所有供应商（不含 API 密钥）。
     *
     * @return 供应商列表。顺序由实现决定（JSON 实现按文件顺序，数据库实现可自行排序）。
     */
    suspend fun list(): List<Provider>

    /**
     * 按 ID 查询供应商（不含 API 密钥）。
     *
     * @param id 供应商 ID。
     * @return 供应商；不存在返回 null。
     */
    suspend fun get(id: String): Provider?

    /**
     * 保存供应商（upsert 语义）。
     *
     * 按 [Provider.id] 插入或整条覆盖。API 密钥（[Provider.apiKeys]）不会持久化，
     * 实现应忽略该字段（[Provider.apiKeys] 为 @Transient，序列化时本就不写入）。
     *
     * @param provider 要保存的供应商。
     */
    suspend fun save(provider: Provider)

    /**
     * 删除供应商。
     *
     * @param id 供应商 ID。不存在时静默成功（幂等）。
     */
    suspend fun delete(id: String)
}
