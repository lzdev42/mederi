package xyz.mederi.store

import xyz.mederi.workspace.Workspace
import xyz.mederi.workspace.WorkspaceOperation

/**
 * 工作区与操作存储接口。
 */
interface WorkspaceStore {
    suspend fun getWorkspace(id: String): Workspace?
    suspend fun listWorkspaces(projectId: String): List<Workspace>
    suspend fun saveWorkspace(workspace: Workspace)
    suspend fun deleteWorkspace(id: String)

    suspend fun getOperation(id: String): WorkspaceOperation?
    suspend fun getOperationByIdempotencyKey(key: String): WorkspaceOperation?
    suspend fun listOperations(projectId: String): List<WorkspaceOperation>
    suspend fun saveOperation(operation: WorkspaceOperation)
    suspend fun deleteOperation(id: String)

    /**
     * 原子提交工作区绑定、会话与操作。
     *
     * 满足 CAS 约束：
     * 1. 目标会话存在
     * 2. expectedWorkspaceId != null 时校验 session.workspaceId == expectedWorkspaceId
     * 3. expectedGeneration != null 时校验已存在工作区的 generation == expectedGeneration
     * 校验失败抛出 [xyz.mederi.api.exception.MederiWorkspaceException] (code: STALE_GENERATION)，
     * 且不得发生任何半写入（会话、工作区、操作全部未被更新）。
     */
    suspend fun commitBinding(
        sessionId: String,
        expectedWorkspaceId: String?,
        expectedGeneration: Long?,
        newWorkspace: Workspace,
        associatedWorktreeId: String?,
        operation: WorkspaceOperation
    ): Boolean
}
