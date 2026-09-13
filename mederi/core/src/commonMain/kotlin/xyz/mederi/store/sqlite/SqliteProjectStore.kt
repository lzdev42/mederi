package xyz.mederi.store.sqlite

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.db.config.MederiConfigDatabase
import xyz.mederi.domain.model.Project
import xyz.mederi.store.ProjectStore

/**
 * 基于 SQLDelight + SQLite（配置库 config.db）的 ProjectStore 实现。
 *
 * Project 聚合根整行 JSON 存 payload 列，id/name 提升为独立列。
 *
 * 单目录模型迁移：旧版 payload 是 `directories: List<String>`，新版是 `directory: String`。
 * 读取时对旧 payload 走 [migrateLegacy]（取第一个目录作为项目目录）——旧项目不会丢、也不会崩；
 * 迁移结果只在本进程内存生效，下一次正常保存（重命名等）会落回新形状。
 *
 * @param driver 配置库 driver（由 Mederi 装配层创建并共享）。
 */
class SqliteProjectStore(driver: SqlDriver) : ProjectStore {

    private val queries = MederiConfigDatabase(driver).configDatabaseQueries
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 旧版多目录 payload 的只读解码形状（directories 列表）。 */
    @Serializable
    private data class LegacyProject(
        val id: String,
        val name: String,
        val directories: List<String> = emptyList(),
        val createdAt: String = "",
        val updatedAt: String = "",
    )

    override suspend fun list(): List<Project> = withContext(Dispatchers.IO) {
        queries.listProjects().executeAsList().mapNotNull { decode(it.payload) }
    }

    override suspend fun get(id: String): Project? = withContext(Dispatchers.IO) {
        queries.getProject(id).executeAsOneOrNull()?.let { decode(it.payload) }
    }

    override suspend fun save(project: Project): Unit = withContext(Dispatchers.IO) {
        queries.upsertProject(
            id = project.id,
            name = project.name,
            payload = json.encodeToString(Project.serializer(), project),
            created_at = project.createdAt
        )
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteProject(id)
    }

    /** 新版 payload 直接解码；旧版（多目录）迁移取第一个目录；两者都失败返回 null（安全兜底，不崩）。 */
    private fun decode(payload: String): Project? =
        runCatching { json.decodeFromString(Project.serializer(), payload) }
            .getOrElse { migrateLegacy(payload) }

    private fun migrateLegacy(payload: String): Project? {
        val legacy = runCatching { json.decodeFromString(LegacyProject.serializer(), payload) }
            .getOrNull() ?: return null
        val directory = legacy.directories.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return Project(
            id = legacy.id,
            name = legacy.name,
            directory = directory,
            createdAt = legacy.createdAt,
            updatedAt = legacy.updatedAt
        )
    }
}
