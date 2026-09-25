package xyz.mederi.api

import xyz.mederi.domain.model.Project

/**
 * Project API。
 */
interface ProjectApi {

    suspend fun list(): List<Project>

    suspend fun create(request: CreateProjectRequest): Project

    suspend fun get(id: String): Project

    suspend fun delete(id: String)

    suspend fun rename(id: String, request: RenameProjectRequest): Project
}

/**
 * 创建项目请求。
 */
data class CreateProjectRequest(
    val name: String,
    val directory: String
)

/**
 * 重命名项目请求。
 */
data class RenameProjectRequest(
    val name: String
)
