package xyz.mederi.store.sqlite

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.mederi.db.config.MederiConfigDatabase
import xyz.mederi.domain.model.Project
import xyz.mederi.store.ProjectStore

/**
 * 基于 SQLDelight + SQLite（配置库 config.db）的 ProjectStore 实现。
 *
 * Project 聚合根整行 JSON 存 payload 列，id/name 提升为独立列。
 *
 * @param driver 配置库 driver（由 Mederi 装配层创建并共享）。
 */
class SqliteProjectStore(driver: SqlDriver) : ProjectStore {

    private val queries = MederiConfigDatabase(driver).configDatabaseQueries
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun list(): List<Project> = withContext(Dispatchers.IO) {
        queries.listProjects().executeAsList().map { decode(it.payload) }
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

    private fun decode(payload: String): Project =
        json.decodeFromString(Project.serializer(), payload)
}
