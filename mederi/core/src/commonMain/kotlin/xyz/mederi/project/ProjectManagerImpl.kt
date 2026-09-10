package xyz.mederi.project

import xyz.mederi.domain.model.Project
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.ProjectStore
import xyz.mederi.store.SessionStore
import java.time.Instant
import java.util.UUID

/**
 * ProjectManager 默认实现。
 *
 * 持有 [ProjectStore]，并直接操作 [SessionStore] 和 [HistoryStore] 实现级联删除。
 *
 * @param projectStore 项目持久化存储。
 * @param sessionStore Session 存储（用于级联删除项目下的 Session）。
 * @param historyStore 对话历史存储（用于级联删除 Session 的历史）。
 */
class ProjectManagerImpl(
    private val projectStore: ProjectStore,
    private val sessionStore: SessionStore,
    private val historyStore: HistoryStore
) : ProjectManager {

    override suspend fun list(): List<Project> = projectStore.list()

    override suspend fun get(id: String): Project? = projectStore.get(id)

    override suspend fun require(id: String): Project =
        get(id) ?: throw NoSuchElementException("Project not found: $id")

    override suspend fun create(name: String, directories: List<String>): Project {
        require(directories.isNotEmpty()) {
            "Project must have at least one directory"
        }
        val now = Instant.now().toString()
        val project = Project(
            id = "proj_${UUID.randomUUID().toString().take(8)}",
            name = name,
            directories = directories,
            createdAt = now,
            updatedAt = now
        )
        projectStore.save(project)

        // 在主目录下创建 .mederi/ 工作目录（与写路径自愈共用一套逻辑）
        xyz.mederi.plan.ensureMederiDir(directories)

        return project
    }

    override suspend fun delete(id: String) {
        // 级联删除项目下的所有 Session 及历史
        sessionStore.list().filter { it.projectId == id }.forEach { session ->
            sessionStore.delete(session.id)
            historyStore.delete(session.id)
        }
        projectStore.delete(id)
    }

    override suspend fun rename(id: String, name: String): Project {
        val existing = require(id)
        val updated = existing.copy(name = name, updatedAt = Instant.now().toString())
        projectStore.save(updated)
        return updated
    }

    override suspend fun addDirectory(id: String, path: String): Project {
        val existing = require(id)
        require(path !in existing.directories) {
            "Directory already exists in project: $path"
        }
        val updated = existing.copy(
            directories = existing.directories + path,
            updatedAt = Instant.now().toString()
        )
        projectStore.save(updated)
        return updated
    }

    override suspend fun removeDirectory(id: String, path: String): Project {
        val existing = require(id)
        require(path in existing.directories) {
            "Directory not found in project: $path"
        }
        require(existing.directories.size > 1) {
            "Project must have at least one directory"
        }
        val updated = existing.copy(
            directories = existing.directories - path,
            updatedAt = Instant.now().toString()
        )
        projectStore.save(updated)
        return updated
    }
}
