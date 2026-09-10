package xyz.mederi.api.impl

import xyz.mederi.api.ProjectApi
import xyz.mederi.api.AddProjectDirectoryRequest
import xyz.mederi.api.CreateProjectRequest
import xyz.mederi.api.RemoveProjectDirectoryRequest
import xyz.mederi.api.RenameProjectRequest
import xyz.mederi.api.exception.mederiCall
import xyz.mederi.domain.model.Project
import xyz.mederi.project.ProjectManager

/**
 * ProjectApi 实现。
 */
class ProjectApiImpl(private val projectManager: ProjectManager) : ProjectApi {

    override suspend fun list(): List<Project> = mederiCall { projectManager.list() }

    override suspend fun create(request: CreateProjectRequest): Project = mederiCall {
        projectManager.create(request.name, request.directories)
    }

    override suspend fun get(id: String): Project = mederiCall { projectManager.require(id) }

    override suspend fun delete(id: String) {
        mederiCall { projectManager.delete(id) }
    }

    override suspend fun rename(id: String, request: RenameProjectRequest): Project = mederiCall {
        projectManager.rename(id, request.name)
    }

    override suspend fun addDirectory(id: String, request: AddProjectDirectoryRequest): Project = mederiCall {
        projectManager.addDirectory(id, request.path)
    }

    override suspend fun removeDirectory(id: String, request: RemoveProjectDirectoryRequest): Project = mederiCall {
        projectManager.removeDirectory(id, request.path)
    }
}
