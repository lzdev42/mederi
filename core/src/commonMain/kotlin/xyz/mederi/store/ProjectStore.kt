package xyz.mederi.store

import xyz.mederi.domain.model.Project

/**
 * 项目存储接口。
 *
 * 管理 Project 聚合根的持久化。
 * 内置实现：SqliteProjectStore（config.db）、InMemoryProjectStore（内存）。
 */
interface ProjectStore {

    /**
     * 查询所有项目。
     */
    suspend fun list(): List<Project>

    /**
     * 按 ID 查询项目。
     *
     * @param id 项目 ID。
     * @return 项目；不存在返回 null。
     */
    suspend fun get(id: String): Project?

    /**
     * 保存项目（upsert 语义）。
     *
     * @param project 要保存的项目。
     */
    suspend fun save(project: Project)

    /**
     * 删除项目。
     *
     * @param id 项目 ID。不存在时静默成功（幂等）。
     */
    suspend fun delete(id: String)
}
