package xyz.mederi.store.sqlite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.mederi.api.exception.MederiNotFoundException
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.store.WorkspaceStore
import xyz.mederi.store.db.DatabaseManager
import xyz.mederi.workspace.Workspace
import xyz.mederi.workspace.WorkspaceErrorCode
import xyz.mederi.workspace.WorkspaceOperation
import java.time.Instant

/**
 * 基于 SQLDelight + SQLite 的 WorkspaceStore 实现（data.db）。
 *
 * 写操作经 [DatabaseManager] 单写队列串行执行；读操作保持并发（WAL 读写不互斥）。
 */
class SqliteWorkspaceStore(
    private val db: DatabaseManager,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) : WorkspaceStore {
    private val database = db.database
    private val queries = database.dataDatabaseQueries

    override suspend fun getWorkspace(id: String): Workspace? = withContext(Dispatchers.IO) {
        queries.getWorkspace(id).executeAsOneOrNull()?.let { row ->
            json.decodeFromString(Workspace.serializer(), row.payload)
        }
    }

    override suspend fun listWorkspaces(projectId: String): List<Workspace> = withContext(Dispatchers.IO) {
        queries.listWorkspacesByProject(projectId).executeAsList().map { row ->
            json.decodeFromString(Workspace.serializer(), row.payload)
        }
    }

    override suspend fun saveWorkspace(workspace: Workspace):Unit = db.write {
        queries.insertWorkspace(
            id = workspace.id,
            project_id = workspace.projectId,
            kind = workspace.kind.name,
            state = workspace.state.name,
            associated_session_id = workspace.associatedSessionId,
            updated_at = workspace.lastUsedAt,
            payload = json.encodeToString(Workspace.serializer(), workspace)
        )
    }

    override suspend fun deleteWorkspace(id: String):Unit = db.write {
        queries.deleteWorkspace(id)
    }

    override suspend fun getOperation(id: String): WorkspaceOperation? = withContext(Dispatchers.IO) {
        queries.getWorkspaceOperation(id).executeAsOneOrNull()?.let { row ->
            json.decodeFromString(WorkspaceOperation.serializer(), row.payload)
        }
    }

    override suspend fun getOperationByIdempotencyKey(key: String): WorkspaceOperation? = withContext(Dispatchers.IO) {
        queries.getWorkspaceOperationByIdempotencyKey(key).executeAsOneOrNull()?.let { row ->
            json.decodeFromString(WorkspaceOperation.serializer(), row.payload)
        }
    }

    override suspend fun listOperations(projectId: String): List<WorkspaceOperation> = withContext(Dispatchers.IO) {
        queries.listWorkspaceOperationsByProject(projectId).executeAsList().map { row ->
            json.decodeFromString(WorkspaceOperation.serializer(), row.payload)
        }
    }

    override suspend fun saveOperation(operation: WorkspaceOperation):Unit = db.write {
        queries.insertWorkspaceOperation(
            id = operation.id,
            idempotency_key = operation.idempotencyKey,
            project_id = operation.projectId,
            workspace_id = operation.workspaceId,
            state = operation.state.name,
            updated_at = operation.updatedAt,
            payload = json.encodeToString(WorkspaceOperation.serializer(), operation)
        )
    }

    override suspend fun deleteOperation(id: String):Unit = db.write {
        queries.deleteWorkspaceOperation(id)
    }

    override suspend fun commitBinding(
        sessionId: String,
        expectedWorkspaceId: String?,
        expectedGeneration: Long?,
        newWorkspace: Workspace,
        associatedWorktreeId: String?,
        operation: WorkspaceOperation
    ): Boolean = db.write {
        database.transactionWithResult {
            // 1. Check session
            val sessionRow = queries.getSession(sessionId).executeAsOneOrNull()
                ?: throw MederiNotFoundException("Session not found: $sessionId")

            if (expectedWorkspaceId != null && sessionRow.workspace_id != expectedWorkspaceId) {
                throw MederiWorkspaceException(
                    code = WorkspaceErrorCode.STALE_GENERATION,
                    message = "Session workspace mismatch: expected $expectedWorkspaceId, actual ${sessionRow.workspace_id}"
                )
            }

            // 2. Check workspace generation
            if (expectedGeneration != null) {
                val checkTargetId = expectedWorkspaceId ?: newWorkspace.id
                val currentTargetRow = queries.getWorkspace(checkTargetId).executeAsOneOrNull()
                if (currentTargetRow != null) {
                    val currentTarget = json.decodeFromString(Workspace.serializer(), currentTargetRow.payload)
                    if (currentTarget.generation != expectedGeneration) {
                        throw MederiWorkspaceException(
                            code = WorkspaceErrorCode.STALE_GENERATION,
                            message = "Workspace generation mismatch: expected $expectedGeneration, actual ${currentTarget.generation}"
                        )
                    }
                }
            }

            // 3. Update session
            val now = Instant.now().toString()
            queries.updateSessionWorkspaceBinding(newWorkspace.id, associatedWorktreeId, now, sessionId)

            // 4. Save workspace
            queries.insertWorkspace(
                id = newWorkspace.id,
                project_id = newWorkspace.projectId,
                kind = newWorkspace.kind.name,
                state = newWorkspace.state.name,
                associated_session_id = newWorkspace.associatedSessionId,
                updated_at = newWorkspace.lastUsedAt,
                payload = json.encodeToString(Workspace.serializer(), newWorkspace)
            )

            // 5. Save operation
            queries.insertWorkspaceOperation(
                id = operation.id,
                idempotency_key = operation.idempotencyKey,
                project_id = operation.projectId,
                workspace_id = operation.workspaceId,
                state = operation.state.name,
                updated_at = operation.updatedAt,
                payload = json.encodeToString(WorkspaceOperation.serializer(), operation)
            )

            true
        }
    }
}
