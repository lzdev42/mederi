package xyz.mederi.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mederi.api.exception.MederiNotFoundException
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.workspace.Workspace
import xyz.mederi.workspace.WorkspaceErrorCode
import xyz.mederi.workspace.WorkspaceOperation

/**
 * 内存工作区存储实现。
 */
class InMemoryWorkspaceStore(
    private val sessionStore: SessionStore? = null
) : WorkspaceStore {
    private val mutex = Mutex()
    private val workspaces = mutableMapOf<String, Workspace>()
    private val operations = mutableMapOf<String, WorkspaceOperation>()

    override suspend fun getWorkspace(id: String): Workspace? = mutex.withLock {
        workspaces[id]
    }

    override suspend fun listWorkspaces(projectId: String): List<Workspace> = mutex.withLock {
        workspaces.values.filter { it.projectId == projectId }.sortedByDescending { it.lastUsedAt }
    }

    override suspend fun saveWorkspace(workspace: Workspace): Unit = mutex.withLock {
        workspaces[workspace.id] = workspace
    }

    override suspend fun deleteWorkspace(id: String): Unit = mutex.withLock {
        workspaces.remove(id)
    }

    override suspend fun getOperation(id: String): WorkspaceOperation? = mutex.withLock {
        operations[id]
    }

    override suspend fun getOperationByIdempotencyKey(key: String): WorkspaceOperation? = mutex.withLock {
        operations.values.find { it.idempotencyKey == key }
    }

    override suspend fun listOperations(projectId: String): List<WorkspaceOperation> = mutex.withLock {
        operations.values.filter { it.projectId == projectId }.sortedByDescending { it.updatedAt }
    }

    override suspend fun saveOperation(operation: WorkspaceOperation): Unit = mutex.withLock {
        operations[operation.id] = operation
    }

    override suspend fun deleteOperation(id: String): Unit = mutex.withLock {
        operations.remove(id)
    }

    override suspend fun commitBinding(
        sessionId: String,
        expectedWorkspaceId: String?,
        expectedGeneration: Long?,
        newWorkspace: Workspace,
        associatedWorktreeId: String?,
        operation: WorkspaceOperation
    ): Boolean = mutex.withLock {
        val session = sessionStore?.get(sessionId)
            ?: throw MederiNotFoundException("Session not found: $sessionId")

        if (expectedWorkspaceId != null && session.workspaceId != expectedWorkspaceId) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.STALE_GENERATION,
                message = "Session workspace mismatch: expected $expectedWorkspaceId, actual ${session.workspaceId}"
            )
        }

        if (expectedGeneration != null) {
            val checkTargetId = expectedWorkspaceId ?: newWorkspace.id
            val currentTarget = workspaces[checkTargetId]
            if (currentTarget != null && currentTarget.generation != expectedGeneration) {
                throw MederiWorkspaceException(
                    code = WorkspaceErrorCode.STALE_GENERATION,
                    message = "Workspace generation mismatch: expected $expectedGeneration, actual ${currentTarget.generation}"
                )
            }
        }

        sessionStore.updateWorkspaceBinding(sessionId, newWorkspace.id, associatedWorktreeId)
        workspaces[newWorkspace.id] = newWorkspace
        operations[operation.id] = operation
        true
    }
}
