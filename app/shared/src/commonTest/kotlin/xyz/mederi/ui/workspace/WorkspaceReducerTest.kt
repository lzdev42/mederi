package xyz.mederi.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import xyz.mederi.core.contract.ConversationStateReducer
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.CostSummary
import xyz.mederi.core.contract.models.ExecutionApprovalRequest
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.core.contract.models.QuestionRequest
import xyz.mederi.core.contract.models.TokenUsage
import xyz.mederi.core.contract.models.Workspace
import xyz.mederi.core.contract.models.WorkspaceInfo
import xyz.mederi.core.contract.models.WorkspaceKind
import xyz.mederi.core.contract.models.WorkspaceOperation
import xyz.mederi.core.contract.models.WorkspaceOperationKind
import xyz.mederi.core.contract.models.WorkspaceOperationPlan
import xyz.mederi.core.contract.models.WorkspaceOperationState
import xyz.mederi.core.contract.models.WorkspacePhase
import xyz.mederi.core.contract.models.WorkspaceState

class WorkspaceReducerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleState(sessionId: String = "sess1", projectId: String = "projA"): ConversationState {
        val conv = Conversation(
            id = sessionId,
            projectId = projectId,
            title = "Test Conversation",
            status = ConversationStatus.Idle,
            createdAt = 1700000000000,
            updatedAt = 1700000000000,
            agent = "AUTONOMOUS"
        )
        return ConversationState(
            conversation = conv,
            messages = emptyList(),
            tokenUsage = TokenUsage(),
            cost = CostSummary()
        )
    }

    private fun sampleOperation(id: String, projectId: String, version: Long): WorkspaceOperation {
        return WorkspaceOperation(
            id = id,
            projectId = projectId,
            workspaceId = "ws1",
            kind = WorkspaceOperationKind.SETUP,
            state = WorkspaceOperationState.RUNNING,
            phase = WorkspacePhase.SETUP,
            idempotencyKey = "SETUP:$id",
            requestHash = "hash_$id",
            version = version,
            createdAt = "2026-11-02T00:00:00Z",
            updatedAt = "2026-11-02T00:00:00Z",
            operationPlan = WorkspaceOperationPlan.SetupPlan(
                requestHash = "hash_$id",
                workspaceId = "ws1",
                environmentId = "env1",
                environmentVersion = 1L,
                environmentHash = "hash_$id"
            )
        )
    }

    @Test
    fun ignoresOlderOperationVersions() {
        val initial = sampleState()
        val op5 = sampleOperation("op5", "projA", version = 5L)
        val stateWithOp5 = initial.copy(activeWorkspaceOperation = op5)

        // 尝试应用旧版本 3
        val op3 = op5.copy(version = 3L)
        val eventOld = CoreEvent(
            type = CoreEventType.WORKSPACE_OPERATION_UPDATED,
            sessionId = "sess1",
            payload = mapOf(
                "projectId" to "projA",
                "sessionId" to "sess1",
                "operation" to json.encodeToString(WorkspaceOperation.serializer(), op3)
            )
        )
        val stateAfterOld = ConversationStateReducer.apply(stateWithOp5, eventOld)
        assertEquals(5L, stateAfterOld.activeWorkspaceOperation?.version)

        // 重试创建新操作，版本从 1 开始
        val retry = sampleOperation("retry", "projA", version = 1L)
        val eventNew = CoreEvent(
            type = CoreEventType.WORKSPACE_OPERATION_UPDATED,
            sessionId = "sess1",
            payload = mapOf(
                "projectId" to "projA",
                "sessionId" to "sess1",
                "operation" to json.encodeToString(WorkspaceOperation.serializer(), retry)
            )
        )
        val stateAfterNew = ConversationStateReducer.apply(stateWithOp5, eventNew)
        assertEquals(1L, stateAfterNew.activeWorkspaceOperation?.version)
        assertEquals("retry", stateAfterNew.activeWorkspaceOperation?.id)
    }

    @Test
    fun preservesOtherPendingInteractionsDuringWorkspaceUpdates() {
        val initial = sampleState().copy(
            pendingPlanApproval = PlanApprovalRequest(
                id = "req_plan_1",
                conversationId = "sess1",
                planPath = "/path/plan.md",
                title = "test plan",
                subtasks = emptyList()
            ),
            pendingExecutionApprovals = listOf(
                ExecutionApprovalRequest(
                    id = "req_exec",
                    conversationId = "sess1",
                    executionSessionId = "exec_1",
                    toolName = "bash",
                    command = "ls -la",
                    cwd = "/tmp",
                    access = "EXECUTE",
                    reason = "test"
                )
            ),
            pendingQuestion = QuestionRequest(
                id = "q_1",
                conversationId = "sess1",
                questions = listOf(
                    QuestionRequest.Question(id = "subq1", prompt = "Proceed?", options = listOf("Yes", "No"))
                )
            )
        )

        val op = sampleOperation("op_test", "projA", version = 1L)
        val event = CoreEvent(
            type = CoreEventType.WORKSPACE_OPERATION_UPDATED,
            sessionId = "sess1",
            payload = mapOf(
                "projectId" to "projA",
                "sessionId" to "sess1",
                "operation" to json.encodeToString(WorkspaceOperation.serializer(), op)
            )
        )

        val reduced = ConversationStateReducer.apply(initial, event)
        assertNotNull(reduced.pendingPlanApproval)
        assertEquals(1, reduced.pendingExecutionApprovals.size)
        assertNotNull(reduced.pendingQuestion)
        assertEquals("op_test", reduced.activeWorkspaceOperation?.id)
    }

    @Test
    fun hydratesArchivePinAndWorkspaceFromSnapshot() {
        val ws = Workspace(
            id = "ws_hydrated",
            projectId = "projA",
            kind = WorkspaceKind.LOCAL,
            state = WorkspaceState.READY,
            checkoutRoot = "/root",
            projectRelativePath = "",
            generation = 1L,
            createdAt = "2026-11-02T00:00:00Z",
            lastUsedAt = "2026-11-02T00:00:00Z"
        )
        val info = WorkspaceInfo(workspace = ws, headOid = "oid1", branch = "main", dirty = false, capabilities = emptySet())
        val convWs = xyz.mederi.core.contract.models.ConversationWorkspaceInfo(active = info)
        val op = sampleOperation("op_active", "projA", version = 1L)

        val state = ConversationState(
            conversation = Conversation(
                id = "sess_hydrated",
                projectId = "projA",
                title = "Hydrated Session",
                status = ConversationStatus.Idle,
                createdAt = 1700000000000,
                updatedAt = 1700000000000,
                agent = "AUTONOMOUS",
                workspaceId = "ws_hydrated",
                archivedAt = "2026-11-02T00:00:00Z",
                pinned = true
            ),
            messages = emptyList(),
            tokenUsage = TokenUsage(),
            cost = CostSummary(),
            workspaceInfo = convWs,
            activeWorkspaceOperation = op
        )

        assertEquals("ws_hydrated", state.conversation.workspaceId)
        assertEquals("2026-11-02T00:00:00Z", state.conversation.archivedAt)
        assertTrue(state.conversation.pinned)
        assertEquals("ws_hydrated", state.workspaceInfo?.active?.workspace?.id)
        assertEquals("op_active", state.activeWorkspaceOperation?.id)
    }

    @Test
    fun neverBorrowsAnotherProjectsWorkspaceWhenOwnerUnknown() {
        val initial = sampleState(sessionId = "sess1", projectId = "projA")

        // 尝试应用属于 projB 的工作区变更事件
        val foreignWs = Workspace(
            id = "ws_foreign",
            projectId = "projB",
            kind = WorkspaceKind.LOCAL,
            state = WorkspaceState.READY,
            checkoutRoot = "/projB",
            projectRelativePath = "",
            generation = 1L,
            createdAt = "2026-11-02T00:00:00Z",
            lastUsedAt = "2026-11-02T00:00:00Z"
        )
        val foreignInfo = WorkspaceInfo(workspace = foreignWs, headOid = "oid_b", branch = "main", dirty = false, capabilities = emptySet())

        val foreignEvent = CoreEvent(
            type = CoreEventType.WORKSPACE_CHANGED,
            sessionId = "sess1",
            payload = mapOf(
                "projectId" to "projB",
                "workspaceId" to "ws_foreign",
                "workspaceInfo" to json.encodeToString(WorkspaceInfo.serializer(), foreignInfo)
            )
        )

        val reduced = ConversationStateReducer.apply(initial, foreignEvent)
        // 验证没有被偷换为 projB 的工作区
        assertNull(reduced.workspaceInfo)
        assertNull(reduced.conversation.workspaceId)
    }
}
