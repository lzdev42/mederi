package xyz.mederi.store

import xyz.mederi.domain.model.Project

/**
 * 仅内存的 ProjectStore 实现。
 *
 * 未指定 configDir 时使用。
 */
class InMemoryProjectStore : ProjectStore {

    private val projects = mutableMapOf<String, Project>()

    override suspend fun list(): List<Project> =
        projects.values.toList()

    override suspend fun get(id: String): Project? = projects[id]

    override suspend fun save(project: Project) {
        projects[project.id] = project
    }

    override suspend fun delete(id: String) {
        projects.remove(id)
    }
}
