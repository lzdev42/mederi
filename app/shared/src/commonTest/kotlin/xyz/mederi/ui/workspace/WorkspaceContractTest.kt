package xyz.mederi.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.ConversationWorkspaceInfo
import xyz.mederi.core.contract.models.CreateWorkspaceBranchInput
import xyz.mederi.core.contract.models.HandoffInput
import xyz.mederi.core.contract.models.HandoffPreview
import xyz.mederi.core.contract.models.ProjectEnvironment
import xyz.mederi.core.contract.models.RecoveryPreview
import xyz.mederi.core.contract.models.RecoveryStrategy
import xyz.mederi.core.contract.models.RepositoryInfo
import xyz.mederi.core.contract.models.Workspace
import xyz.mederi.core.contract.models.WorkspaceCapability
import xyz.mederi.core.contract.models.WorkspaceError
import xyz.mederi.core.contract.models.WorkspaceErrorCode
import xyz.mederi.core.contract.models.WorkspaceFileArea
import xyz.mederi.core.contract.models.WorkspaceInfo
import xyz.mederi.core.contract.models.WorkspaceKind
import xyz.mederi.core.contract.models.WorkspaceOperation
import xyz.mederi.core.contract.models.WorkspaceOperationKind
import xyz.mederi.core.contract.models.WorkspaceOperationPlan
import xyz.mederi.core.contract.models.WorkspaceOperationState
import xyz.mederi.core.contract.models.WorkspacePhase
import xyz.mederi.core.contract.models.WorkspaceSelection
import xyz.mederi.core.contract.models.WorktreeSettings

class WorkspaceContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun roundTripsAllWorkspaceDtosAndLegacyDefaults() {
        // 1. 旧 Conversation JSON 兼容（缺少 workspaceId, associatedWorktreeId, archivedAt, pinned）
        val legacyJson = """
            {
                "id": "c1",
                "projectId": "p1",
                "title": "Legacy Conversation",
                "status": "Idle",
                "createdAt": 1700000000000,
                "updatedAt": 1700000000000,
                "agent": "AUTONOMOUS"
            }
        """.trimIndent()
        val conv = json.decodeFromString(Conversation.serializer(), legacyJson)
        assertEquals("c1", conv.id)
        assertNull(conv.workspaceId)
        assertNull(conv.associatedWorktreeId)
        assertNull(conv.archivedAt)
        assertFalse(conv.pinned)

        // 2. Workspace 往返
        val ws = Workspace(
            id = "ws1",
            projectId = "p1",
            kind = WorkspaceKind.MANAGED_WORKTREE,
            state = xyz.mederi.core.contract.models.WorkspaceState.READY,
            checkoutRoot = "/path/to/checkout",
            projectRelativePath = "sub",
            generation = 2L,
            taskBranchName = "mederi/task-1",
            associatedSessionId = "c1",
            createdAt = "2026-11-02T00:00:00Z",
            lastUsedAt = "2026-11-02T00:00:00Z"
        )
        val wsEncoded = json.encodeToString(Workspace.serializer(), ws)
        val wsDecoded = json.decodeFromString(Workspace.serializer(), wsEncoded)
        assertEquals(ws, wsDecoded)

        // 3. WorktreeSettings 往返
        val settings = WorktreeSettings(rootPath = "/wt/root", maxManagedWorktrees = 20, automaticCleanup = true, version = 3L)
        val setEncoded = json.encodeToString(WorktreeSettings.serializer(), settings)
        val setDecoded = json.decodeFromString(WorktreeSettings.serializer(), setEncoded)
        assertEquals(settings, setDecoded)

        // 4. ProjectEnvironment 往返
        val env = ProjectEnvironment(id = "env1", name = "Test Env", version = 1L)
        val envEncoded = json.encodeToString(ProjectEnvironment.serializer(), env)
        val envDecoded = json.decodeFromString(ProjectEnvironment.serializer(), envEncoded)
        assertEquals(env, envDecoded)
    }

    @Test
    fun mirrorsCoreEnumsErrorsAndWorkspaceFields() {
        // 断言 Shared 枚举所有项都在预期集合中且无拼写错误
        val kindNames = WorkspaceKind.entries.map { it.name }.toSet()
        assertTrue(kindNames.containsAll(listOf("LOCAL", "MANAGED_WORKTREE")))

        val stateNames = xyz.mederi.core.contract.models.WorkspaceState.entries.map { it.name }.toSet()
        assertTrue(stateNames.containsAll(listOf("REQUESTED", "PREPARING", "READY", "TRANSFERRING", "ARCHIVING", "ARCHIVED", "RESTORING", "RECOVERY_REQUIRED", "MISSING")))

        val opKindNames = WorkspaceOperationKind.entries.map { it.name }.toSet()
        assertTrue(opKindNames.containsAll(listOf("CREATE", "HANDOFF", "ARCHIVE", "RESTORE", "SETUP", "ACTION")))

        val opStateNames = WorkspaceOperationState.entries.map { it.name }.toSet()
        assertTrue(opStateNames.containsAll(listOf("PENDING", "RUNNING", "WAITING_APPROVAL", "CANCELLING", "SUCCEEDED", "FAILED", "CANCELLED", "RECOVERY_REQUIRED")))

        val errorCodes = WorkspaceErrorCode.entries.map { it.name }.toSet()
        assertTrue(errorCodes.containsAll(listOf(
            "NOT_GIT_REPOSITORY", "NO_BASE_COMMIT", "BASE_CHANGED", "PROJECT_SUBDIRECTORY_MISSING",
            "WORKSPACE_BUSY", "WORKSPACE_MISSING", "STALE_GENERATION", "STALE_PREVIEW", "STALE_OPERATION",
            "BRANCH_IN_USE", "TARGET_DIRTY", "PATH_DENIED", "IDENTITY_CHANGED", "SNAPSHOT_INCOMPLETE",
            "SNAPSHOT_CORRUPT", "EXTERNAL_CHANGE", "UNSUPPORTED_REPOSITORY_STATE", "UNSUPPORTED_FILE_TYPE",
            "ENVIRONMENT_CHANGED", "SETUP_FAILED", "SETUP_TIMEOUT", "OPERATION_CANCELLED", "RECOVERY_REQUIRED",
            "INVALID_REQUEST", "IO_ERROR"
        )))

        val capNames = WorkspaceCapability.entries.map { it.name }.toSet()
        assertTrue(capNames.containsAll(listOf("OPEN_FILES", "OPEN_TERMINAL", "CREATE_BRANCH", "HANDOFF", "ARCHIVE", "RESTORE", "RUN_ACTION")))

        val fileAreaNames = WorkspaceFileArea.entries.map { it.name }.toSet()
        assertTrue(fileAreaNames.containsAll(listOf("PROJECT", "ARTIFACT")))

        val stratNames = RecoveryStrategy.entries.map { it.name }.toSet()
        assertTrue(stratNames.containsAll(listOf("KEEP_VERIFIED_CURRENT", "RESTORE_TO_NEW_MANAGED")))
    }

    @Test
    fun preservesCancellationAndStructuredErrors() {
        val err = WorkspaceError(
            code = WorkspaceErrorCode.PATH_DENIED,
            message = "Access to /etc/shadow is denied",
            retryable = false,
            operationId = "op1",
            diagnostic = "rule: sandbox"
        )
        val op = WorkspaceOperation(
            id = "op1",
            projectId = "p1",
            workspaceId = "ws1",
            kind = WorkspaceOperationKind.ACTION,
            state = WorkspaceOperationState.FAILED,
            phase = WorkspacePhase.COMPLETE,
            idempotencyKey = "ACTION:req1",
            requestHash = "hash1",
            cancelRequested = true,
            error = err,
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z",
            operationPlan = WorkspaceOperationPlan.ActionPlan(
                requestHash = "hash1",
                workspaceId = "ws1",
                environmentId = "env1",
                actionId = "build",
                environmentVersion = 1L
            )
        )
        val opEncoded = json.encodeToString(WorkspaceOperation.serializer(), op)
        val opDecoded = json.decodeFromString(WorkspaceOperation.serializer(), opEncoded)

        assertEquals(op, opDecoded)
        assertTrue(opDecoded.cancelRequested)
        assertEquals(WorkspaceErrorCode.PATH_DENIED, opDecoded.error?.code)
        assertEquals("rule: sandbox", opDecoded.error?.diagnostic)
    }
}
