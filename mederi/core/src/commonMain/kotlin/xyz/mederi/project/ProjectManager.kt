package xyz.mederi.project

import xyz.mederi.domain.model.Project

/**
 * 项目管理器。
 *
 * 项目（Project）的唯一真理源。所有项目的读写、目录管理都必须通过 ProjectManager。
 *
 * 核心不变式：
 * - 每个项目必须至少有一个目录。
 * - 新建项目时必须给定至少一个绝对路径。
 */
interface ProjectManager {

    /**
     * 列出所有项目。
     */
    suspend fun list(): List<Project>

    /**
     * 按 ID 获取项目。找不到返回 null，不兜底。
     */
    suspend fun get(id: String): Project?

    /**
     * 按 ID 获取项目。找不到直接抛异常。
     */
    suspend fun require(id: String): Project

    /**
     * 创建项目。
     *
     * @param name 项目名称。
     * @param directories 绝对路径目录列表，至少一个。
     * @return 创建后的项目。
     * @throws IllegalArgumentException 如果 directories 为空。
     */
    suspend fun create(name: String, directories: List<String>): Project

    /**
     * 删除项目。
     *
     * 删除项目时会级联删除其下所有 Session（及对应 History）。
     *
     * @param id 项目 ID。
     */
    suspend fun delete(id: String)

    /**
     * 重命名项目。
     */
    suspend fun rename(id: String, name: String): Project

    /**
     * 为项目添加一个目录。
     */
    suspend fun addDirectory(id: String, path: String): Project

    /**
     * 移除项目中的一个目录。
     *
     * 如果移除后项目将没有目录，则拒绝操作（每个项目至少保留一个目录）。
     */
    suspend fun removeDirectory(id: String, path: String): Project
}
