package xyz.mederi.core.contract.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 工作区种类。
 */
@Serializable
enum class WorkspaceKind {
    LOCAL,
    MANAGED_WORKTREE
}

/**
 * 工作区生命周期状态。
 */
@Serializable
enum class WorkspaceState {
    REQUESTED,
    PREPARING,
    READY,
    TRANSFERRING,
    ARCHIVING,
    ARCHIVED,
    RESTORING,
    RECOVERY_REQUIRED,
    MISSING
}

/**
 * 工作区操作类型。
 */
@Serializable
enum class WorkspaceOperationKind {
    CREATE,
    HANDOFF,
    ARCHIVE,
    RESTORE,
    SETUP,
    ACTION
}

/**
 * 工作区操作状态。
 */
@Serializable
enum class WorkspaceOperationState {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    CANCELLING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    RECOVERY_REQUIRED
}

/**
 * 工作区操作阶段。
 */
@Serializable
enum class WorkspacePhase {
    VALIDATE,
    QUIESCE,
    SNAPSHOT,
    CREATE_CHECKOUT,
    COPY_CONTENT,
    SETUP,
    DETACH_SOURCE,
    SWITCH_TARGET,
    MATERIALIZE_TARGET,
    VERIFY_TARGET,
    CLEAN_SOURCE,
    COMMIT_BINDING,
    REMOVE_CHECKOUT,
    VERIFY_REMOVAL,
    COMPLETE
}

/**
 * 结构化工作区错误码。
 */
@Serializable
enum class WorkspaceErrorCode {
    NOT_GIT_REPOSITORY,
    NO_BASE_COMMIT,
    BASE_CHANGED,
    PROJECT_SUBDIRECTORY_MISSING,
    WORKSPACE_BUSY,
    WORKSPACE_MISSING,
    STALE_GENERATION,
    STALE_PREVIEW,
    STALE_OPERATION,
    BRANCH_IN_USE,
    TARGET_DIRTY,
    PATH_DENIED,
    IDENTITY_CHANGED,
    SNAPSHOT_INCOMPLETE,
    SNAPSHOT_CORRUPT,
    EXTERNAL_CHANGE,
    UNSUPPORTED_REPOSITORY_STATE,
    UNSUPPORTED_FILE_TYPE,
    ENVIRONMENT_CHANGED,
    SETUP_FAILED,
    SETUP_TIMEOUT,
    OPERATION_CANCELLED,
    RECOVERY_REQUIRED,
    INVALID_REQUEST,
    IO_ERROR
}

/**
 * 结构化工作区错误详情。
 */
@Serializable
data class WorkspaceError(
    val code: WorkspaceErrorCode,
    val message: String,
    val retryable: Boolean = false,
    val operationId: String? = null,
    val diagnostic: String? = null
)

/**
 * 工作区用户选择。
 */
@Serializable
data class WorkspaceSelection(
    val kind: WorkspaceKind = WorkspaceKind.LOCAL,
    val baseRef: String? = null,
    val carryLocalChanges: Boolean = false,
    val environmentId: String? = null
)

/**
 * 准备中工作区的已冻结选择与环境上下文。
 */
@Serializable
data class PendingWorkspaceSelection(
    val selection: WorkspaceSelection,
    val resolvedBaseOid: String? = null,
    val sourceCheckoutId: String? = null,
    val environmentId: String? = null,
    val environmentVersion: Long? = null,
    val environmentHash: String? = null
)

/**
 * 工作区持久领域对象。
 */
@Serializable
data class Workspace(
    val id: String,
    val projectId: String,
    val kind: WorkspaceKind,
    val checkoutRoot: String,
    val projectRelativePath: String,
    val gitCommonDirectory: String? = null,
    val baseCommit: String? = null,
    val associatedSessionId: String? = null,
    val state: WorkspaceState,
    val generation: Long,
    val lastSnapshotId: String? = null,
    val createdAt: String,
    val lastUsedAt: String,
    val pendingSelection: PendingWorkspaceSelection? = null,
    val taskBranchName: String? = null
)

/**
 * 恢复策略。
 */
@Serializable
enum class RecoveryStrategy {
    KEEP_VERIFIED_CURRENT,
    RESTORE_TO_NEW_MANAGED
}

/**
 * 恢复决策记录。
 */
@Serializable
data class RecoveryResolution(
    val strategy: RecoveryStrategy,
    val snapshotId: String? = null,
    val keepWorkspaceId: String? = null,
    val resolvedAt: String
)

/**
 * 持久化操作计划（密封接口）。
 */
@Serializable
sealed interface WorkspaceOperationPlan {
    val requestHash: String
    val permissionVersion: Long
    val participatingWorkspaceIds: List<String>
    val participatingPaths: List<String>
    val expectedGenerations: Map<String, Long>

    @Serializable
    @SerialName("create")
    data class CreatePlan(
        override val requestHash: String,
        override val permissionVersion: Long = 0L,
        override val participatingWorkspaceIds: List<String> = emptyList(),
        override val participatingPaths: List<String> = emptyList(),
        override val expectedGenerations: Map<String, Long> = emptyMap(),
        val selection: WorkspaceSelection,
        val baseOid: String? = null,
        val targetPath: String,
        val environmentId: String? = null,
        val environmentVersion: Long? = null,
        val environmentHash: String? = null,
        val frozenScript: String? = null,
        val snapshotId: String? = null
    ) : WorkspaceOperationPlan

    @Serializable
    @SerialName("handoff")
    data class HandoffPlan(
        override val requestHash: String,
        override val permissionVersion: Long = 0L,
        override val participatingWorkspaceIds: List<String> = emptyList(),
        override val participatingPaths: List<String> = emptyList(),
        override val expectedGenerations: Map<String, Long> = emptyMap(),
        val sourceWorkspaceId: String,
        val targetWorkspaceId: String,
        val targetKind: WorkspaceKind,
        val taskBranchName: String,
        val expectedSourceHeadOid: String,
        val expectedTargetHeadOid: String? = null,
        val sourceTransferSnapshotId: String? = null,
        val sourcePreimageSnapshotId: String? = null,
        val targetPreimageSnapshotId: String? = null,
        val acceptedTargetSnapshotId: String? = null,
        val stopAffectedSessions: Boolean = false,
        val previewToken: String? = null
    ) : WorkspaceOperationPlan

    @Serializable
    @SerialName("archive")
    data class ArchivePlan(
        override val requestHash: String,
        override val permissionVersion: Long = 0L,
        override val participatingWorkspaceIds: List<String> = emptyList(),
        override val participatingPaths: List<String> = emptyList(),
        override val expectedGenerations: Map<String, Long> = emptyMap(),
        val workspaceId: String,
        val snapshotId: String? = null,
        val checkoutPath: String,
        val confirmDiscardIgnored: Boolean = false
    ) : WorkspaceOperationPlan

    @Serializable
    @SerialName("restore")
    data class RestorePlan(
        override val requestHash: String,
        override val permissionVersion: Long = 0L,
        override val participatingWorkspaceIds: List<String> = emptyList(),
        override val participatingPaths: List<String> = emptyList(),
        override val expectedGenerations: Map<String, Long> = emptyMap(),
        val workspaceId: String,
        val snapshotId: String,
        val targetCheckoutPath: String
    ) : WorkspaceOperationPlan

    @Serializable
    @SerialName("setup")
    data class SetupPlan(
        override val requestHash: String,
        override val permissionVersion: Long = 0L,
        override val participatingWorkspaceIds: List<String> = emptyList(),
        override val participatingPaths: List<String> = emptyList(),
        override val expectedGenerations: Map<String, Long> = emptyMap(),
        val workspaceId: String,
        val environmentId: String,
        val environmentVersion: Long,
        val environmentHash: String,
        val script: String? = null
    ) : WorkspaceOperationPlan

    @Serializable
    @SerialName("action")
    data class ActionPlan(
        override val requestHash: String,
        override val permissionVersion: Long = 0L,
        override val participatingWorkspaceIds: List<String> = emptyList(),
        override val participatingPaths: List<String> = emptyList(),
        override val expectedGenerations: Map<String, Long> = emptyMap(),
        val workspaceId: String,
        val actionId: String,
        val environmentId: String,
        val environmentVersion: Long
    ) : WorkspaceOperationPlan
}

/**
 * 阶段执行记录。
 */
@Serializable
data class WorkspacePhaseRecord(
    val phase: WorkspacePhase,
    val startedAt: String,
    val completedAt: String? = null,
    val error: WorkspaceError? = null
)

/**
 * 工作区操作记录。
 */
@Serializable
data class WorkspaceOperation(
    val id: String,
    val projectId: String,
    val sessionId: String? = null,
    val workspaceId: String,
    val kind: WorkspaceOperationKind,
    val state: WorkspaceOperationState,
    val phase: WorkspacePhase,
    val idempotencyKey: String,
    val requestHash: String,
    val sourceWorkspaceId: String? = null,
    val targetWorkspaceId: String? = null,
    val sourceSnapshotId: String? = null,
    val targetSnapshotId: String? = null,
    val cancelRequested: Boolean = false,
    val progressCompleted: Long = 0L,
    val progressTotal: Long? = null,
    val error: WorkspaceError? = null,
    val version: Long = 0L,
    val createdAt: String,
    val updatedAt: String,
    val parentOperationId: String? = null,
    val operationPlan: WorkspaceOperationPlan,
    val phaseRecords: List<WorkspacePhaseRecord> = emptyList(),
    val recoveryResolution: RecoveryResolution? = null
)

/**
 * 宿主全局工作区设置。
 */
@Serializable
data class WorktreeSettings(
    val rootPath: String,
    val maxManagedWorktrees: Int = 15,
    val automaticCleanup: Boolean = true,
    val version: Long = 0L
)

/**
 * 工作区操作能力集。
 */
@Serializable
enum class WorkspaceCapability {
    OPEN_FILES,
    OPEN_TERMINAL,
    CREATE_BRANCH,
    HANDOFF,
    ARCHIVE,
    RESTORE,
    RUN_ACTION
}

/**
 * 工作区文件区域。
 */
@Serializable
enum class WorkspaceFileArea {
    PROJECT,
    ARTIFACT
}

/**
 * Git 分支描述。
 */
@Serializable
data class GitBranchInfo(
    val name: String,
    val headOid: String,
    val inUseByCheckout: String? = null
)

/**
 * Git 仓库信息。
 */
@Serializable
data class RepositoryInfo(
    val isGit: Boolean,
    val checkoutRoot: String? = null,
    val projectRelativePath: String? = null,
    val gitCommonDirectory: String? = null,
    val headOid: String? = null,
    val branch: String? = null,
    val branches: List<GitBranchInfo> = emptyList(),
    val dirty: Boolean = false,
    val unsupportedReasons: List<String> = emptyList()
)

/**
 * 工作区信息（含动态状态）。
 */
@Serializable
data class WorkspaceInfo(
    val workspace: Workspace,
    val headOid: String?,
    val branch: String?,
    val dirty: Boolean,
    val capabilities: Set<WorkspaceCapability>,
    val bytesOnDisk: Long? = null,
    val error: WorkspaceError? = null
)

/**
 * 对话绑定的工作区状态。
 */
@Serializable
data class ConversationWorkspaceInfo(
    val active: WorkspaceInfo,
    val associated: WorkspaceInfo? = null,
    val operation: WorkspaceOperation? = null
)

/**
 * 受迁移影响的会话。
 */
@Serializable
data class AffectedSession(
    val id: String,
    val title: String,
    val running: Boolean
)

/**
 * 工作区冲突描述。
 */
@Serializable
data class WorkspaceConflict(
    val path: String,
    val reason: WorkspaceErrorCode
)

/**
 * Handoff 预览结果。
 */
@Serializable
data class HandoffPreview(
    val token: String,
    val expiresAt: String,
    val source: WorkspaceInfo,
    val target: WorkspaceInfo,
    val fingerprints: Map<String, String>,
    val affectedSessions: List<AffectedSession>,
    val taskBranch: String,
    val conflicts: List<WorkspaceConflict>
)

/**
 * 恢复快照信息。
 */
@Serializable
data class RecoverySnapshotInfo(
    val snapshotId: String,
    val createdAt: String,
    val reason: String,
    val sizeBytes: Long
)

/**
 * 恢复操作预览。
 */
@Serializable
data class RecoveryPreview(
    val token: String,
    val operationVersion: Long,
    val sourceWorkspace: WorkspaceInfo?,
    val targetWorkspace: WorkspaceInfo?,
    val eligibleSnapshots: List<RecoverySnapshotInfo>,
    val conflicts: List<WorkspaceConflict>,
    val expiresAt: String
)

/**
 * 平台环境脚本配置。
 */
@Serializable
data class PlatformScript(
    val default: String? = null,
    val macos: String? = null,
    val linux: String? = null,
    val windows: String? = null,
    val sandbox: String = "default",
    val timeoutSeconds: Int = 300
)

/**
 * 环境自定义动作。
 */
@Serializable
data class EnvironmentAction(
    val id: String,
    val name: String,
    val icon: String? = null,
    val script: PlatformScript
)

/**
 * 项目环境定义。
 */
@Serializable
data class ProjectEnvironment(
    val id: String,
    val name: String,
    val version: Long = 1L,
    val setup: PlatformScript? = null,
    val actions: List<EnvironmentAction> = emptyList()
)

/**
 * 工作区文件条目。
 */
@Serializable
data class WorkspaceFileEntry(
    val name: String,
    val relativePath: String,
    val kind: String, // "file" or "directory"
    val size: Long? = null
)

/**
 * 工作区文件读取内容。
 */
@Serializable
data class WorkspaceFileContent(
    val relativePath: String,
    val text: String? = null,
    val mime: String = "text/plain",
    val truncated: Boolean = false,
    val nextOffset: Long? = null
)

/**
 * 滚动日志切片。
 */
@Serializable
data class LogChunk(
    val bytesUtf8: String,
    val nextOffset: Long,
    val eof: Boolean,
    val truncated: Boolean = false
)

/**
 * Handoff 预览请求输入。
 */
@Serializable
data class HandoffPreviewInput(
    val targetKind: WorkspaceKind,
    val taskBranchName: String? = null
)

/**
 * Handoff 执行输入。
 */
@Serializable
data class HandoffInput(
    val targetKind: WorkspaceKind,
    val previewToken: String,
    val requestId: String,
    val stopAffectedSessions: Boolean = false,
    val taskBranchName: String? = null
)

/**
 * 恢复工作区输入。
 */
@Serializable
data class RestoreWorkspaceInput(
    val requestId: String
)

/**
 * 归档工作区输入。
 */
@Serializable
data class ArchiveWorkspaceInput(
    val requestId: String,
    val confirmDiscardIgnored: Boolean = false
)

/**
 * 创建工作区分支输入。
 */
@Serializable
data class CreateWorkspaceBranchInput(
    val name: String,
    val expectedHead: String,
    val expectedGeneration: Long
)

/**
 * 执行工作区环境动作输入。
 */
@Serializable
data class WorkspaceActionInput(
    val environmentId: String,
    val actionId: String,
    val environmentVersion: Long,
    val requestId: String,
    /** 发起动作的会话，执行审批回到该会话。旧请求可省略。 */
    val conversationId: String? = null
)

/**
 * 崩溃恢复提交输入。
 */
@Serializable
data class WorkspaceRecoveryInput(
    val strategy: RecoveryStrategy,
    val snapshotId: String? = null,
    val keepWorkspaceId: String? = null,
    val expectedOperationVersion: Long,
    val previewToken: String,
    val requestId: String
)

/**
 * 会话归档变更请求。
 */
@Serializable
data class SetConversationArchivedInput(
    val archived: Boolean,
    val requestId: String
)

/**
 * 会话固定变更请求。
 */
@Serializable
data class SetConversationPinnedInput(
    val pinned: Boolean
)

/**
 * 项目环境保存请求。
 */
@Serializable
data class SaveProjectEnvironmentInput(
    val environment: ProjectEnvironment,
    val expectedVersion: Long
)

/**
 * 工作区准备重试请求。
 */
@Serializable
data class RetryWorkspacePreparationInput(
    val failedOperationId: String,
    val requestId: String
)

/**
 * 工作区环境安装重试请求。
 */
@Serializable
data class RetryWorkspaceSetupInput(
    val failedOperationId: String,
    val expectedEnvironmentHash: String,
    val requestId: String
)

/**
 * 工作区崩溃恢复预览请求。
 */
@Serializable
data class PreviewWorkspaceRecoveryInput(
    val strategy: RecoveryStrategy,
    val snapshotId: String? = null,
    val keepWorkspaceId: String? = null
)
