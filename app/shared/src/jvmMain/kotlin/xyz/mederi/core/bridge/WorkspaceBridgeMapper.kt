package xyz.mederi.core.bridge

import kotlinx.serialization.json.Json
import xyz.mederi.workspace.WorkspaceKind as CoreWorkspaceKind
import xyz.mederi.workspace.WorkspaceState as CoreWorkspaceState
import xyz.mederi.workspace.WorkspaceOperationKind as CoreWorkspaceOperationKind
import xyz.mederi.workspace.WorkspaceOperationState as CoreWorkspaceOperationState
import xyz.mederi.workspace.WorkspacePhase as CoreWorkspacePhase
import xyz.mederi.workspace.WorkspaceErrorCode as CoreWorkspaceErrorCode
import xyz.mederi.workspace.WorkspaceCapability as CoreWorkspaceCapability
import xyz.mederi.workspace.WorkspaceFileArea as CoreWorkspaceFileArea
import xyz.mederi.workspace.WorkspaceFileKind as CoreWorkspaceFileKind
import xyz.mederi.workspace.RecoveryStrategy as CoreRecoveryStrategy
import xyz.mederi.workspace.WorkspaceSelection as CoreWorkspaceSelection
import xyz.mederi.workspace.PendingWorkspaceSelection as CorePendingWorkspaceSelection
import xyz.mederi.workspace.Workspace as CoreWorkspace
import xyz.mederi.workspace.WorkspaceError as CoreWorkspaceError
import xyz.mederi.workspace.GitBranchInfo as CoreGitBranchInfo
import xyz.mederi.workspace.RepositoryInfo as CoreRepositoryInfo
import xyz.mederi.workspace.WorkspaceInfo as CoreWorkspaceInfo
import xyz.mederi.workspace.ConversationWorkspaceInfo as CoreConversationWorkspaceInfo
import xyz.mederi.workspace.AffectedSession as CoreAffectedSession
import xyz.mederi.workspace.WorkspaceConflict as CoreWorkspaceConflict
import xyz.mederi.workspace.HandoffPreview as CoreHandoffPreview
import xyz.mederi.workspace.RecoverySnapshotInfo as CoreRecoverySnapshotInfo
import xyz.mederi.workspace.RecoveryPreview as CoreRecoveryPreview
import xyz.mederi.workspace.WorktreeSettings as CoreWorktreeSettings
import xyz.mederi.workspace.PlatformScript as CorePlatformScript
import xyz.mederi.workspace.EnvironmentAction as CoreEnvironmentAction
import xyz.mederi.workspace.ProjectEnvironment as CoreProjectEnvironment
import xyz.mederi.workspace.WorkspaceFileEntry as CoreWorkspaceFileEntry
import xyz.mederi.workspace.WorkspaceFileContent as CoreWorkspaceFileContent
import xyz.mederi.workspace.LogChunk as CoreLogChunk
import xyz.mederi.workspace.WorkspaceOperation as CoreWorkspaceOperation
import xyz.mederi.workspace.WorkspaceOperationPlan as CoreWorkspaceOperationPlan
import xyz.mederi.workspace.WorkspacePhaseRecord as CoreWorkspacePhaseRecord
import xyz.mederi.workspace.RecoveryResolution as CoreRecoveryResolution

import xyz.mederi.workspace.HandoffPreviewInput as CoreHandoffPreviewInput
import xyz.mederi.workspace.HandoffInput as CoreHandoffInput
import xyz.mederi.workspace.RestoreWorkspaceInput as CoreRestoreWorkspaceInput
import xyz.mederi.workspace.ArchiveWorkspaceInput as CoreArchiveWorkspaceInput
import xyz.mederi.workspace.CreateWorkspaceBranchInput as CoreCreateWorkspaceBranchInput
import xyz.mederi.workspace.WorkspaceActionInput as CoreWorkspaceActionInput
import xyz.mederi.workspace.WorkspaceRecoveryInput as CoreWorkspaceRecoveryInput

import xyz.mederi.core.contract.models.WorkspaceKind as SharedWorkspaceKind
import xyz.mederi.core.contract.models.WorkspaceState as SharedWorkspaceState
import xyz.mederi.core.contract.models.WorkspaceOperationKind as SharedWorkspaceOperationKind
import xyz.mederi.core.contract.models.WorkspaceOperationState as SharedWorkspaceOperationState
import xyz.mederi.core.contract.models.WorkspacePhase as SharedWorkspacePhase
import xyz.mederi.core.contract.models.WorkspaceErrorCode as SharedWorkspaceErrorCode
import xyz.mederi.core.contract.models.WorkspaceCapability as SharedWorkspaceCapability
import xyz.mederi.core.contract.models.WorkspaceFileArea as SharedWorkspaceFileArea
import xyz.mederi.core.contract.models.RecoveryStrategy as SharedRecoveryStrategy
import xyz.mederi.core.contract.models.WorkspaceSelection as SharedWorkspaceSelection
import xyz.mederi.core.contract.models.PendingWorkspaceSelection as SharedPendingWorkspaceSelection
import xyz.mederi.core.contract.models.Workspace as SharedWorkspace
import xyz.mederi.core.contract.models.WorkspaceError as SharedWorkspaceError
import xyz.mederi.core.contract.models.GitBranchInfo as SharedGitBranchInfo
import xyz.mederi.core.contract.models.RepositoryInfo as SharedRepositoryInfo
import xyz.mederi.core.contract.models.WorkspaceInfo as SharedWorkspaceInfo
import xyz.mederi.core.contract.models.ConversationWorkspaceInfo as SharedConversationWorkspaceInfo
import xyz.mederi.core.contract.models.AffectedSession as SharedAffectedSession
import xyz.mederi.core.contract.models.WorkspaceConflict as SharedWorkspaceConflict
import xyz.mederi.core.contract.models.HandoffPreview as SharedHandoffPreview
import xyz.mederi.core.contract.models.RecoverySnapshotInfo as SharedRecoverySnapshotInfo
import xyz.mederi.core.contract.models.RecoveryPreview as SharedRecoveryPreview
import xyz.mederi.core.contract.models.WorktreeSettings as SharedWorktreeSettings
import xyz.mederi.core.contract.models.PlatformScript as SharedPlatformScript
import xyz.mederi.core.contract.models.EnvironmentAction as SharedEnvironmentAction
import xyz.mederi.core.contract.models.ProjectEnvironment as SharedProjectEnvironment
import xyz.mederi.core.contract.models.WorkspaceFileEntry as SharedWorkspaceFileEntry
import xyz.mederi.core.contract.models.WorkspaceFileContent as SharedWorkspaceFileContent
import xyz.mederi.core.contract.models.LogChunk as SharedLogChunk
import xyz.mederi.core.contract.models.WorkspaceOperation as SharedWorkspaceOperation
import xyz.mederi.core.contract.models.WorkspaceOperationPlan as SharedWorkspaceOperationPlan
import xyz.mederi.core.contract.models.WorkspacePhaseRecord as SharedWorkspacePhaseRecord
import xyz.mederi.core.contract.models.RecoveryResolution as SharedRecoveryResolution

import xyz.mederi.core.contract.models.HandoffPreviewInput as SharedHandoffPreviewInput
import xyz.mederi.core.contract.models.HandoffInput as SharedHandoffInput
import xyz.mederi.core.contract.models.RestoreWorkspaceInput as SharedRestoreWorkspaceInput
import xyz.mederi.core.contract.models.ArchiveWorkspaceInput as SharedArchiveWorkspaceInput
import xyz.mederi.core.contract.models.CreateWorkspaceBranchInput as SharedCreateWorkspaceBranchInput
import xyz.mederi.core.contract.models.WorkspaceActionInput as SharedWorkspaceActionInput
import xyz.mederi.core.contract.models.WorkspaceRecoveryInput as SharedWorkspaceRecoveryInput

private val json = Json { ignoreUnknownKeys = true }

// Core -> Shared 映射器
internal fun CoreWorkspaceKind.toContract(): SharedWorkspaceKind =
    SharedWorkspaceKind.valueOf(name)

internal fun SharedWorkspaceKind.toCore(): CoreWorkspaceKind =
    CoreWorkspaceKind.valueOf(name)

internal fun CoreWorkspaceState.toContract(): SharedWorkspaceState =
    SharedWorkspaceState.valueOf(name)

internal fun CoreWorkspaceOperationKind.toContract(): SharedWorkspaceOperationKind =
    SharedWorkspaceOperationKind.valueOf(name)

internal fun CoreWorkspaceOperationState.toContract(): SharedWorkspaceOperationState =
    SharedWorkspaceOperationState.valueOf(name)

internal fun CoreWorkspacePhase.toContract(): SharedWorkspacePhase =
    SharedWorkspacePhase.valueOf(name)

internal fun CoreWorkspaceErrorCode.toContract(): SharedWorkspaceErrorCode =
    SharedWorkspaceErrorCode.valueOf(name)

internal fun CoreWorkspaceCapability.toContract(): SharedWorkspaceCapability =
    SharedWorkspaceCapability.valueOf(name)

internal fun CoreWorkspaceFileArea.toContract(): SharedWorkspaceFileArea =
    SharedWorkspaceFileArea.valueOf(name)

internal fun SharedWorkspaceFileArea.toCore(): CoreWorkspaceFileArea =
    CoreWorkspaceFileArea.valueOf(name)

internal fun CoreRecoveryStrategy.toContract(): SharedRecoveryStrategy =
    SharedRecoveryStrategy.valueOf(name)

internal fun SharedRecoveryStrategy.toCore(): CoreRecoveryStrategy =
    CoreRecoveryStrategy.valueOf(name)

internal fun CoreWorkspaceSelection.toContract(): SharedWorkspaceSelection =
    SharedWorkspaceSelection(
        kind = kind.toContract(),
        baseRef = baseRef,
        environmentId = environmentId,
        carryLocalChanges = carryLocalChanges
    )

internal fun SharedWorkspaceSelection.toCore(): CoreWorkspaceSelection =
    CoreWorkspaceSelection(
        kind = kind.toCore(),
        baseRef = baseRef,
        environmentId = environmentId,
        carryLocalChanges = carryLocalChanges
    )

internal fun CorePendingWorkspaceSelection.toContract(): SharedPendingWorkspaceSelection =
    SharedPendingWorkspaceSelection(
        selection = selection.toContract(),
        resolvedBaseOid = resolvedBaseOid,
        sourceCheckoutId = sourceCheckoutId,
        environmentId = environmentId,
        environmentVersion = environmentVersion,
        environmentHash = environmentHash
    )

internal fun CoreWorkspace.toContract(): SharedWorkspace =
    SharedWorkspace(
        id = id,
        projectId = projectId,
        kind = kind.toContract(),
        checkoutRoot = checkoutRoot,
        projectRelativePath = projectRelativePath,
        gitCommonDirectory = gitCommonDirectory,
        baseCommit = baseCommit,
        associatedSessionId = associatedSessionId,
        state = state.toContract(),
        generation = generation,
        lastSnapshotId = lastSnapshotId,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        pendingSelection = pendingSelection?.toContract(),
        taskBranchName = taskBranchName
    )

internal fun CoreWorkspaceError.toContract(): SharedWorkspaceError =
    SharedWorkspaceError(
        code = code.toContract(),
        message = message,
        retryable = retryable,
        operationId = operationId,
        diagnostic = diagnostic
    )

internal fun CoreGitBranchInfo.toContract(): SharedGitBranchInfo =
    SharedGitBranchInfo(
        name = name,
        headOid = headOid,
        inUseByCheckout = inUseByCheckout
    )

internal fun CoreRepositoryInfo.toContract(): SharedRepositoryInfo =
    SharedRepositoryInfo(
        isGit = isGit,
        checkoutRoot = checkoutRoot,
        projectRelativePath = projectRelativePath,
        headOid = headOid,
        branch = branch,
        branches = branches.map { it.toContract() },
        dirty = dirty,
        unsupportedReasons = unsupportedReasons
    )

internal fun CoreWorkspaceInfo.toContract(): SharedWorkspaceInfo =
    SharedWorkspaceInfo(
        workspace = workspace.toContract(),
        headOid = headOid,
        branch = branch,
        dirty = dirty,
        capabilities = capabilities.map { it.toContract() }.toSet(),
        bytesOnDisk = bytesOnDisk,
        error = error?.toContract()
    )

internal fun CoreConversationWorkspaceInfo.toContract(): SharedConversationWorkspaceInfo =
    SharedConversationWorkspaceInfo(
        active = active.toContract(),
        associated = associated?.toContract(),
        operation = operation?.toContract()
    )

internal fun CoreAffectedSession.toContract(): SharedAffectedSession =
    SharedAffectedSession(
        id = id,
        title = title,
        running = running
    )

internal fun CoreWorkspaceConflict.toContract(): SharedWorkspaceConflict =
    SharedWorkspaceConflict(
        path = path,
        reason = reason.toContract()
    )

internal fun CoreHandoffPreview.toContract(): SharedHandoffPreview =
    SharedHandoffPreview(
        token = token,
        expiresAt = expiresAt,
        source = source.toContract(),
        target = target.toContract(),
        fingerprints = fingerprints,
        affectedSessions = affectedSessions.map { it.toContract() },
        taskBranch = taskBranch,
        conflicts = conflicts.map { it.toContract() }
    )

internal fun CoreRecoverySnapshotInfo.toContract(): SharedRecoverySnapshotInfo =
    SharedRecoverySnapshotInfo(
        snapshotId = snapshotId,
        createdAt = createdAt,
        reason = reason,
        sizeBytes = sizeBytes
    )

internal fun CoreRecoveryPreview.toContract(): SharedRecoveryPreview =
    SharedRecoveryPreview(
        token = token,
        operationVersion = operationVersion,
        sourceWorkspace = sourceWorkspace?.toContract(),
        targetWorkspace = targetWorkspace?.toContract(),
        eligibleSnapshots = eligibleSnapshots.map { it.toContract() },
        conflicts = conflicts.map { it.toContract() },
        expiresAt = expiresAt
    )

internal fun CoreWorktreeSettings.toContract(): SharedWorktreeSettings =
    SharedWorktreeSettings(
        rootPath = rootPath,
        maxManagedWorktrees = maxManagedWorktrees,
        automaticCleanup = automaticCleanup,
        version = version
    )

internal fun SharedWorktreeSettings.toCore(): CoreWorktreeSettings =
    CoreWorktreeSettings(
        rootPath = rootPath,
        maxManagedWorktrees = maxManagedWorktrees,
        automaticCleanup = automaticCleanup,
        version = version
    )

internal fun CorePlatformScript.toContract(): SharedPlatformScript =
    SharedPlatformScript(
        default = default,
        macos = macos,
        linux = linux,
        windows = windows,
        sandbox = sandbox,
        timeoutSeconds = timeoutSeconds
    )

internal fun SharedPlatformScript.toCore(): CorePlatformScript =
    CorePlatformScript(
        default = default,
        macos = macos,
        linux = linux,
        windows = windows,
        sandbox = sandbox,
        timeoutSeconds = timeoutSeconds
    )

internal fun CoreEnvironmentAction.toContract(): SharedEnvironmentAction =
    SharedEnvironmentAction(
        id = id,
        name = name,
        icon = icon,
        script = script.toContract()
    )

internal fun SharedEnvironmentAction.toCore(): CoreEnvironmentAction =
    CoreEnvironmentAction(
        id = id,
        name = name,
        icon = icon,
        script = script.toCore()
    )

internal fun CoreProjectEnvironment.toContract(): SharedProjectEnvironment =
    SharedProjectEnvironment(
        id = id,
        name = name,
        version = version,
        setup = setup?.toContract(),
        actions = actions.map { it.toContract() }
    )

internal fun SharedProjectEnvironment.toCore(): CoreProjectEnvironment =
    CoreProjectEnvironment(
        id = id,
        name = name,
        version = version,
        setup = setup?.toCore(),
        actions = actions.map { it.toCore() }
    )

internal fun CoreWorkspaceFileEntry.toContract(): SharedWorkspaceFileEntry =
    SharedWorkspaceFileEntry(
        name = name,
        relativePath = relativePath,
        kind = if (kind == CoreWorkspaceFileKind.DIRECTORY) "directory" else "file",
        size = size
    )

internal fun CoreWorkspaceFileContent.toContract(): SharedWorkspaceFileContent =
    SharedWorkspaceFileContent(
        relativePath = relativePath,
        text = text,
        mime = mime,
        truncated = truncated,
        nextOffset = nextOffset
    )

internal fun CoreLogChunk.toContract(): SharedLogChunk =
    SharedLogChunk(
        bytesUtf8 = text,
        nextOffset = nextOffset,
        eof = eof,
        truncated = truncated
    )

internal fun CoreWorkspaceOperation.toContract(): SharedWorkspaceOperation {
    val serializedPlan = json.encodeToString(CoreWorkspaceOperationPlan.serializer(), operationPlan)
    val contractPlan = json.decodeFromString(SharedWorkspaceOperationPlan.serializer(), serializedPlan)
    val serializedPhases = json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(CoreWorkspacePhaseRecord.serializer()),
        phaseRecords
    )
    val contractPhases = json.decodeFromString(
        kotlinx.serialization.builtins.ListSerializer(SharedWorkspacePhaseRecord.serializer()),
        serializedPhases
    )
    val contractResolution = recoveryResolution?.let { res ->
        val s = json.encodeToString(CoreRecoveryResolution.serializer(), res)
        json.decodeFromString(SharedRecoveryResolution.serializer(), s)
    }

    return SharedWorkspaceOperation(
        id = id,
        projectId = projectId,
        sessionId = sessionId,
        workspaceId = workspaceId,
        kind = kind.toContract(),
        state = state.toContract(),
        phase = phase.toContract(),
        idempotencyKey = idempotencyKey,
        requestHash = requestHash,
        sourceWorkspaceId = sourceWorkspaceId,
        targetWorkspaceId = targetWorkspaceId,
        sourceSnapshotId = sourceSnapshotId,
        targetSnapshotId = targetSnapshotId,
        cancelRequested = cancelRequested,
        progressCompleted = progressCompleted,
        progressTotal = progressTotal,
        error = error?.toContract(),
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
        parentOperationId = parentOperationId,
        operationPlan = contractPlan,
        phaseRecords = contractPhases,
        recoveryResolution = contractResolution
    )
}

internal fun SharedHandoffPreviewInput.toCore(): CoreHandoffPreviewInput =
    CoreHandoffPreviewInput(
        targetKind = targetKind.toCore(),
        taskBranchName = taskBranchName
    )

internal fun SharedHandoffInput.toCore(): CoreHandoffInput =
    CoreHandoffInput(
        targetKind = targetKind.toCore(),
        previewToken = previewToken,
        requestId = requestId,
        stopAffectedSessions = stopAffectedSessions,
        taskBranchName = taskBranchName
    )

internal fun SharedRestoreWorkspaceInput.toCore(): CoreRestoreWorkspaceInput =
    CoreRestoreWorkspaceInput(
        requestId = requestId
    )

internal fun SharedArchiveWorkspaceInput.toCore(): CoreArchiveWorkspaceInput =
    CoreArchiveWorkspaceInput(
        requestId = requestId,
        confirmDiscardIgnored = confirmDiscardIgnored
    )

internal fun SharedCreateWorkspaceBranchInput.toCore(): CoreCreateWorkspaceBranchInput =
    CoreCreateWorkspaceBranchInput(
        name = name,
        expectedHead = expectedHead,
        expectedGeneration = expectedGeneration
    )

internal fun SharedWorkspaceActionInput.toCore(): CoreWorkspaceActionInput =
    CoreWorkspaceActionInput(
        environmentId = environmentId,
        actionId = actionId,
        environmentVersion = environmentVersion,
        requestId = requestId,
        conversationId = conversationId
    )

internal fun SharedWorkspaceRecoveryInput.toCore(): CoreWorkspaceRecoveryInput =
    CoreWorkspaceRecoveryInput(
        strategy = strategy.toCore(),
        snapshotId = snapshotId,
        keepWorkspaceId = keepWorkspaceId,
        expectedOperationVersion = expectedOperationVersion,
        previewToken = previewToken,
        requestId = requestId
    )
