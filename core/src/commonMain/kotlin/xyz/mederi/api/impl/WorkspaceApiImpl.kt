package xyz.mederi.api.impl

import xyz.mederi.api.WorkspaceApi
import xyz.mederi.api.exception.mederiCall
import xyz.mederi.domain.model.Session
import xyz.mederi.project.ProjectManager
import xyz.mederi.workspace.ArchiveWorkspaceInput
import xyz.mederi.workspace.ConversationWorkspaceInfo
import xyz.mederi.workspace.CreateWorkspaceBranchInput
import xyz.mederi.workspace.HandoffInput
import xyz.mederi.workspace.HandoffPreview
import xyz.mederi.workspace.HandoffPreviewInput
import xyz.mederi.workspace.LogChunk
import xyz.mederi.workspace.ProjectEnvironment
import xyz.mederi.workspace.RecoveryPreview
import xyz.mederi.workspace.RecoveryStrategy
import xyz.mederi.workspace.RepositoryInfo
import xyz.mederi.workspace.RestoreWorkspaceInput
import xyz.mederi.workspace.WorkspaceActionInput
import xyz.mederi.workspace.WorkspaceFileArea
import xyz.mederi.workspace.WorkspaceFileContent
import xyz.mederi.workspace.WorkspaceFileEntry
import xyz.mederi.workspace.WorkspaceInfo
import xyz.mederi.workspace.WorkspaceManager
import xyz.mederi.workspace.WorkspaceOperation
import xyz.mederi.workspace.WorkspaceOperationState
import xyz.mederi.workspace.WorkspaceRecoveryInput
import xyz.mederi.workspace.WorktreeSettings

/**
 * WorkspaceApi 实现。
 *
 * 遵循分层架构原则：
 * API 层负责 DTO 包装和异常转换（通过 [mederiCall]），
 * 业务状态和逻辑完全保持在 [WorkspaceManager] 中。
 */
class WorkspaceApiImpl(
    private val workspaceManager: WorkspaceManager,
    private val projectManager: ProjectManager,
    private val resumeWorkspaceInput: suspend (sessionId: String, workspaceId: String) -> Unit,
) : WorkspaceApi {

    override suspend fun getRepositoryInfo(projectId: String): RepositoryInfo = mederiCall {
        val project = projectManager.require(projectId)
        workspaceManager.getRepositoryInfo(project.directory)
    }

    override suspend fun listWorkspaces(projectId: String): List<WorkspaceInfo> = mederiCall {
        workspaceManager.listWorkspaces(projectId)
    }

    override suspend fun getConversationWorkspace(sessionId: String): ConversationWorkspaceInfo = mederiCall {
        workspaceManager.getConversationWorkspace(sessionId)
    }

    override suspend fun previewWorkspaceHandoff(
        sessionId: String,
        input: HandoffPreviewInput
    ): HandoffPreview = mederiCall {
        workspaceManager.previewHandoff(sessionId, input)
    }

    override suspend fun handoffWorkspace(
        sessionId: String,
        input: HandoffInput
    ): WorkspaceOperation = mederiCall {
        workspaceManager.executeHandoff(sessionId, input)
    }

    override suspend fun restoreWorkspace(
        workspaceId: String,
        input: RestoreWorkspaceInput
    ): WorkspaceOperation = mederiCall {
        workspaceManager.restoreWorkspace(workspaceId, input)
    }

    override suspend fun archiveWorkspace(
        workspaceId: String,
        input: ArchiveWorkspaceInput
    ): WorkspaceOperation = mederiCall {
        workspaceManager.archiveWorkspace(workspaceId, input)
    }

    override suspend fun createWorkspaceBranch(
        workspaceId: String,
        input: CreateWorkspaceBranchInput
    ): WorkspaceInfo = mederiCall {
        workspaceManager.createWorkspaceBranch(workspaceId, input)
    }

    override suspend fun getWorkspaceOperation(id: String): WorkspaceOperation = mederiCall {
        workspaceManager.getOperation(id)
    }

    override suspend fun cancelWorkspaceOperation(id: String): WorkspaceOperation = mederiCall {
        workspaceManager.cancelOperation(id)
    }

    override suspend fun listWorkspaceOperations(projectId: String): List<WorkspaceOperation> = mederiCall {
        workspaceManager.listWorkspaceOperations(projectId)
    }

    override suspend fun setConversationArchived(
        sessionId: String,
        archived: Boolean,
        requestId: String
    ): WorkspaceOperation = mederiCall {
        workspaceManager.setConversationArchived(sessionId, archived, requestId)
    }

    override suspend fun setConversationPinned(
        sessionId: String,
        pinned: Boolean
    ): Session = mederiCall {
        workspaceManager.setConversationPinned(sessionId, pinned)
    }

    override suspend fun listArchivedConversations(projectId: String): List<Session> = mederiCall {
        workspaceManager.sessionStore.list()
            .filter { it.projectId == projectId && it.archivedAt != null }
    }

    override suspend fun getWorktreeSettings(): WorktreeSettings = mederiCall {
        workspaceManager.getWorktreeSettings()
    }

    override suspend fun updateWorktreeSettings(settings: WorktreeSettings): WorktreeSettings = mederiCall {
        workspaceManager.updateWorktreeSettings(settings)
    }

    override suspend fun listProjectEnvironments(
        projectId: String,
        workspaceId: String?
    ): List<ProjectEnvironment> = mederiCall {
        workspaceManager.listProjectEnvironments(projectId, workspaceId)
    }

    override suspend fun saveProjectEnvironment(
        projectId: String,
        environment: ProjectEnvironment,
        expectedVersion: Long,
        workspaceId: String?
    ): ProjectEnvironment = mederiCall {
        workspaceManager.saveProjectEnvironment(projectId, environment, expectedVersion, workspaceId)
    }

    override suspend fun runWorkspaceAction(
        workspaceId: String,
        input: WorkspaceActionInput
    ): WorkspaceOperation = mederiCall {
        workspaceManager.runWorkspaceAction(workspaceId, input)
    }

    override suspend fun getWorkspaceOperationLog(
        id: String,
        afterOffset: Long,
        maxBytes: Int
    ): LogChunk = mederiCall {
        workspaceManager.getWorkspaceOperationLog(id, afterOffset, maxBytes)
    }

    override suspend fun listWorkspaceFiles(
        workspaceId: String,
        path: String,
        area: WorkspaceFileArea
    ): List<WorkspaceFileEntry> = mederiCall {
        workspaceManager.listWorkspaceFiles(workspaceId, path, area)
    }

    override suspend fun readWorkspaceFile(
        workspaceId: String,
        path: String,
        offset: Long,
        maxBytes: Int,
        area: WorkspaceFileArea
    ): WorkspaceFileContent = mederiCall {
        workspaceManager.readWorkspaceFile(workspaceId, path, offset, maxBytes, area)
    }

    override suspend fun retryWorkspacePreparation(
        sessionId: String,
        failedOperationId: String,
        requestId: String
    ): WorkspaceOperation = mederiCall {
        val operation = workspaceManager.retryPreparation(sessionId, failedOperationId, requestId)
        if (operation.state == WorkspaceOperationState.SUCCEEDED) {
            resumeWorkspaceInput(sessionId, checkNotNull(operation.workspaceId))
        }
        operation
    }

    override suspend fun retryWorkspaceSetup(
        workspaceId: String,
        failedOperationId: String,
        expectedEnvironmentHash: String,
        requestId: String
    ): WorkspaceOperation = mederiCall {
        workspaceManager.retrySetup(workspaceId, failedOperationId, expectedEnvironmentHash, requestId)
    }

    override suspend fun previewWorkspaceRecovery(
        operationId: String,
        strategy: RecoveryStrategy,
        snapshotId: String?,
        keepWorkspaceId: String?
    ): RecoveryPreview = mederiCall {
        workspaceManager.previewRecovery(operationId, strategy, snapshotId, keepWorkspaceId)
    }

    override suspend fun recoverWorkspaceOperation(
        operationId: String,
        input: WorkspaceRecoveryInput
    ): WorkspaceOperation = mederiCall {
        workspaceManager.executeRecovery(input)
    }
}
