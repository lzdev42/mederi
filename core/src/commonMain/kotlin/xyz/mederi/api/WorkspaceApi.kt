package xyz.mederi.api

import xyz.mederi.domain.model.Session
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
import xyz.mederi.workspace.WorkspaceOperation
import xyz.mederi.workspace.WorkspaceRecoveryInput
import xyz.mederi.workspace.WorktreeSettings

/**
 * 工作区管理 API。
 */
interface WorkspaceApi {
    suspend fun getRepositoryInfo(projectId: String): RepositoryInfo
    suspend fun listWorkspaces(projectId: String): List<WorkspaceInfo>
    suspend fun getConversationWorkspace(sessionId: String): ConversationWorkspaceInfo
    suspend fun previewWorkspaceHandoff(sessionId: String, input: HandoffPreviewInput): HandoffPreview
    suspend fun handoffWorkspace(sessionId: String, input: HandoffInput): WorkspaceOperation
    suspend fun restoreWorkspace(workspaceId: String, input: RestoreWorkspaceInput): WorkspaceOperation
    suspend fun archiveWorkspace(workspaceId: String, input: ArchiveWorkspaceInput): WorkspaceOperation
    suspend fun createWorkspaceBranch(workspaceId: String, input: CreateWorkspaceBranchInput): WorkspaceInfo
    suspend fun getWorkspaceOperation(id: String): WorkspaceOperation
    suspend fun cancelWorkspaceOperation(id: String): WorkspaceOperation
    suspend fun listWorkspaceOperations(projectId: String): List<WorkspaceOperation>
    suspend fun setConversationArchived(sessionId: String, archived: Boolean, requestId: String): WorkspaceOperation
    suspend fun setConversationPinned(sessionId: String, pinned: Boolean): Session
    suspend fun listArchivedConversations(projectId: String): List<Session>
    suspend fun getWorktreeSettings(): WorktreeSettings
    suspend fun updateWorktreeSettings(settings: WorktreeSettings): WorktreeSettings
    suspend fun listProjectEnvironments(projectId: String, workspaceId: String? = null): List<ProjectEnvironment>
    suspend fun saveProjectEnvironment(projectId: String, environment: ProjectEnvironment, expectedVersion: Long, workspaceId: String? = null): ProjectEnvironment
    suspend fun runWorkspaceAction(workspaceId: String, input: WorkspaceActionInput): WorkspaceOperation
    suspend fun getWorkspaceOperationLog(id: String, afterOffset: Long = 0, maxBytes: Int = 262144): LogChunk
    suspend fun listWorkspaceFiles(workspaceId: String, path: String = "", area: WorkspaceFileArea = WorkspaceFileArea.PROJECT): List<WorkspaceFileEntry>
    suspend fun readWorkspaceFile(workspaceId: String, path: String, offset: Long = 0, maxBytes: Int = 262144, area: WorkspaceFileArea = WorkspaceFileArea.PROJECT): WorkspaceFileContent
    suspend fun retryWorkspacePreparation(sessionId: String, failedOperationId: String, requestId: String): WorkspaceOperation
    suspend fun retryWorkspaceSetup(workspaceId: String, failedOperationId: String, expectedEnvironmentHash: String, requestId: String): WorkspaceOperation
    suspend fun previewWorkspaceRecovery(operationId: String, strategy: RecoveryStrategy, snapshotId: String? = null, keepWorkspaceId: String? = null): RecoveryPreview
    suspend fun recoverWorkspaceOperation(operationId: String, input: WorkspaceRecoveryInput): WorkspaceOperation
}
