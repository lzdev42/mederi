package xyz.mederi.ui.workspace

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.CreateConversationInput
import xyz.mederi.core.contract.models.ArchiveWorkspaceInput
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationWorkspaceInfo
import xyz.mederi.core.contract.models.HandoffInput
import xyz.mederi.core.contract.models.HandoffPreview
import xyz.mederi.core.contract.models.HandoffPreviewInput
import xyz.mederi.core.contract.models.RecoveryPreview
import xyz.mederi.core.contract.models.RecoveryStrategy
import xyz.mederi.core.contract.models.WorkspaceError
import xyz.mederi.core.contract.models.WorkspaceErrorCode
import xyz.mederi.core.contract.models.WorkspaceInfo
import xyz.mederi.core.contract.models.WorkspaceKind
import xyz.mederi.core.contract.models.WorkspaceOperation
import xyz.mederi.core.contract.models.WorkspaceOperationKind
import xyz.mederi.core.contract.models.WorkspaceOperationPlan
import xyz.mederi.core.contract.models.WorkspaceOperationState
import xyz.mederi.core.contract.models.WorkspacePhase
import xyz.mederi.core.contract.models.WorkspaceRecoveryInput
import xyz.mederi.core.contract.models.WorkspaceSelection
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.components.WorkspaceSelector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RF-08 真实 UI 路由与 API 闭环集成测试。
 *
 * 全部用例使用**真实** [WorkspaceViewModel] + 记录型 [RecordingAiCore]（委托 MockAiCore），
 * 从生产入口（VM 方法 / 真实 WorkspaceSelector 点击）驱动，断言真实调用顺序与参数：
 * 真实 workspaceId / requestId / preview token。禁止用
 * `setContent(HandoffPanelContent, onExecute={clicked=true})` 替代接线测试。
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class WorkspaceRepairUiIntegrationTest {

    /** 记录型 AiCore：委托 MockAiCore，完整记录工作区相关调用与其参数。 */
    private class RecordingAiCore(val delegate: MockAiCore = MockAiCore()) : AiCore by delegate {
        data class PreviewHandoffCall(val sessionId: String, val input: HandoffPreviewInput)
        data class HandoffCall(val sessionId: String, val input: HandoffInput)
        data class ArchiveCall(val workspaceId: String, val input: ArchiveWorkspaceInput)
        data class RecoveryPreviewCall(
            val operationId: String,
            val strategy: RecoveryStrategy,
            val snapshotId: String?,
            val keepWorkspaceId: String?
        )
        data class RecoveryExecuteCall(val operationId: String, val input: WorkspaceRecoveryInput)
        data class RetryPrepCall(val sessionId: String, val failedOperationId: String, val requestId: String)
        data class PinnedCall(val sessionId: String, val pinned: Boolean)

        val previewHandoffCalls = mutableListOf<PreviewHandoffCall>()
        val handoffCalls = mutableListOf<HandoffCall>()
        val listWorkspacesCalls = mutableListOf<String>()
        val archiveCalls = mutableListOf<ArchiveCall>()
        val listArchivedCalls = mutableListOf<String>()
        val archiveConversationCalls = mutableListOf<Triple<String, Boolean, String>>()
        val pinnedCalls = mutableListOf<PinnedCall>()
        val recoveryPreviewCalls = mutableListOf<RecoveryPreviewCall>()
        val recoveryExecuteCalls = mutableListOf<RecoveryExecuteCall>()
        val cancelCalls = mutableListOf<String>()
        val retryPrepCalls = mutableListOf<RetryPrepCall>()

        override suspend fun previewWorkspaceHandoff(sessionId: String, input: HandoffPreviewInput): Result<HandoffPreview> {
            previewHandoffCalls.add(PreviewHandoffCall(sessionId, input))
            return delegate.previewWorkspaceHandoff(sessionId, input)
        }

        override suspend fun handoffWorkspace(sessionId: String, input: HandoffInput): Result<WorkspaceOperation> {
            handoffCalls.add(HandoffCall(sessionId, input))
            return delegate.handoffWorkspace(sessionId, input)
        }

        override suspend fun listWorkspaces(projectId: String): Result<List<WorkspaceInfo>> {
            listWorkspacesCalls.add(projectId)
            return delegate.listWorkspaces(projectId)
        }

        override suspend fun archiveWorkspace(workspaceId: String, input: ArchiveWorkspaceInput): Result<WorkspaceOperation> {
            archiveCalls.add(ArchiveCall(workspaceId, input))
            return delegate.archiveWorkspace(workspaceId, input)
        }

        override suspend fun listArchivedConversations(projectId: String): Result<List<Conversation>> {
            listArchivedCalls.add(projectId)
            return delegate.listArchivedConversations(projectId)
        }

        override suspend fun setConversationArchived(sessionId: String, archived: Boolean, requestId: String): Result<WorkspaceOperation> {
            archiveConversationCalls.add(Triple(sessionId, archived, requestId))
            return delegate.setConversationArchived(sessionId, archived, requestId)
        }

        override suspend fun setConversationPinned(sessionId: String, pinned: Boolean): Result<Conversation> {
            pinnedCalls.add(PinnedCall(sessionId, pinned))
            return delegate.setConversationPinned(sessionId, pinned)
        }

        override suspend fun previewWorkspaceRecovery(
            operationId: String,
            strategy: RecoveryStrategy,
            snapshotId: String?,
            keepWorkspaceId: String?
        ): Result<RecoveryPreview> {
            recoveryPreviewCalls.add(RecoveryPreviewCall(operationId, strategy, snapshotId, keepWorkspaceId))
            return delegate.previewWorkspaceRecovery(operationId, strategy, snapshotId, keepWorkspaceId)
        }

        override suspend fun recoverWorkspaceOperation(operationId: String, input: WorkspaceRecoveryInput): Result<WorkspaceOperation> {
            recoveryExecuteCalls.add(RecoveryExecuteCall(operationId, input))
            return delegate.recoverWorkspaceOperation(operationId, input)
        }

        override suspend fun cancelWorkspaceOperation(id: String): Result<WorkspaceOperation> {
            cancelCalls.add(id)
            return delegate.cancelWorkspaceOperation(id)
        }

        override suspend fun retryWorkspacePreparation(
            sessionId: String,
            failedOperationId: String,
            requestId: String
        ): Result<WorkspaceOperation> {
            retryPrepCalls.add(RetryPrepCall(sessionId, failedOperationId, requestId))
            return delegate.retryWorkspacePreparation(sessionId, failedOperationId, requestId)
        }
    }

    private fun operation(
        id: String,
        sessionId: String?,
        workspaceId: String,
        kind: WorkspaceOperationKind,
        state: WorkspaceOperationState,
        phase: WorkspacePhase,
        plan: WorkspaceOperationPlan,
        errorCode: WorkspaceErrorCode? = null,
        projectId: String = "p1"
    ) = WorkspaceOperation(
        id = id,
        projectId = projectId,
        sessionId = sessionId,
        workspaceId = workspaceId,
        kind = kind,
        state = state,
        phase = phase,
        idempotencyKey = "key_$id",
        requestHash = "hash_$id",
        createdAt = "2026-11-02T00:00:00Z",
        updatedAt = "2026-11-02T00:00:00Z",
        error = errorCode?.let { WorkspaceError(it, "desc for $id", retryable = true, operationId = id) }
            ?: WorkspaceError(WorkspaceErrorCode.IO_ERROR, "op $id", operationId = id),
        operationPlan = plan
    )

    @Test
    fun realHandoffEntryRequestsPreviewAndSubmitsExactToken() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val recording = RecordingAiCore()
        val store = ViewModelStore()
        try {
            val appState = AppState(aiCore = recording, preferences = InMemoryPreferencesStore(), scope = backgroundScope)
            val vm = WorkspaceViewModel(appState)
            store.put("vm", vm)
            runCurrent()

            val conv = recording.createConversation(
                CreateConversationInput(
                    projectId = "p1",
                    workspaceSelection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "main")
                )
            ).getOrThrow()
            vm.attach(conv.id)
            runCurrent()
            assertNotNull(conv.workspaceId)

            // 生产入口：WorkspaceSelector「迁移工作区」菜单项调用的就是 openHandoffDialog()。
            vm.openHandoffDialog()
            runCurrent()

            // 1. 请求预览：真实 sessionId + 目标 kind（R13：源 MANAGED → 目标 LOCAL，与真实 Manager 拒绝同类型一致）
            assertEquals(1, recording.previewHandoffCalls.size)
            assertEquals(conv.id, recording.previewHandoffCalls.single().sessionId)
            assertEquals(WorkspaceKind.LOCAL, recording.previewHandoffCalls.single().input.targetKind)

            // 2. VM 状态进入 Handoff，携带 core 返回的真实 token
            val handoffState = vm.dialogState.value as? WorkspaceDialogState.Handoff
            assertNotNull(handoffState)
            val preview = requireNotNull(handoffState.preview)
            assertTrue(preview.token.startsWith("token_preview_"))
            assertTrue(preview.source.workspace.id == conv.workspaceId)
            assertEquals(WorkspaceKind.LOCAL, preview.target.workspace.kind)
            val previewToken = preview.token

            // 3. 提交：必须携带预览返回的**同一个** token + 唯一 requestId
            vm.executeHandoff()
            runCurrent()

            assertEquals(1, recording.handoffCalls.size)
            val handoff = recording.handoffCalls.single()
            assertEquals(conv.id, handoff.sessionId)
            assertEquals(previewToken, handoff.input.previewToken)
            assertEquals(WorkspaceKind.LOCAL, handoff.input.targetKind)
            assertTrue(handoff.input.requestId.startsWith("handoff-"))
            assertEquals(false, handoff.input.stopAffectedSessions)

            // 4. 成功后对话框关闭
            assertEquals(WorkspaceDialogState.None, vm.dialogState.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun realRoutesExposeArchivePinManagementAndRecovery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val recording = RecordingAiCore()
        val store = ViewModelStore()
        try {
            val appState = AppState(aiCore = recording, preferences = InMemoryPreferencesStore(), scope = backgroundScope)
            val vm = WorkspaceViewModel(appState)
            store.put("vm", vm)
            runCurrent()

            val conv = recording.createConversation(
                CreateConversationInput(
                    projectId = "p1",
                    workspaceSelection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE)
                )
            ).getOrThrow()
            vm.attach(conv.id)
            runCurrent()
            val wsId = requireNotNull(conv.workspaceId)

            // 1. 管理入口：listWorkspaces(真实项目) → Management 状态
            vm.openManagement()
            runCurrent()
            assertEquals(listOf("p1"), recording.listWorkspacesCalls.toList())
            val mgmt = vm.dialogState.value as? WorkspaceDialogState.Management
            assertNotNull(mgmt)
            assertTrue(mgmt.workspaces.any { it.workspace.id == wsId })

            // 2. 归档入口：真实 workspaceId + requestId；成功后刷新管理列表
            vm.archiveWorkspace(wsId)
            runCurrent()
            assertEquals(1, recording.archiveCalls.size)
            assertEquals(wsId, recording.archiveCalls.single().workspaceId)
            assertTrue(recording.archiveCalls.single().input.requestId.startsWith("archive-"))
            assertTrue(recording.listWorkspacesCalls.size >= 2)

            // 3. 归档列表入口：先种一个已归档会话，再经 VM 打开列表
            recording.delegate.setConversationArchived(conv.id, archived = true, requestId = "seed_archive_1")
            vm.openArchiveList()
            runCurrent()
            assertEquals(listOf("p1"), recording.listArchivedCalls.toList())
            val arch = vm.dialogState.value as? WorkspaceDialogState.ArchiveList
            assertNotNull(arch)
            assertTrue(arch.archived.any { it.id == conv.id })

            // 4. 归档列表「恢复」按钮入口：取消归档 + 刷新列表回显
            vm.restoreArchivedConversation(conv)
            runCurrent()
            assertTrue(recording.archiveConversationCalls.any { it.first == conv.id && it.second == false })
            val archAfter = vm.dialogState.value as? WorkspaceDialogState.ArchiveList
            assertNotNull(archAfter)
            assertFalse(archAfter.archived.any { it.id == conv.id })

            // 5. 固定入口：setConversationPinned 真实会话
            vm.setPinned(conv.id, true)
            runCurrent()
            assertEquals(1, recording.pinnedCalls.size)
            assertEquals(conv.id, recording.pinnedCalls.single().sessionId)
            assertEquals(true, recording.pinnedCalls.single().pinned)
            assertEquals(true, recording.delegate.getSnapshot(conv.id).getOrThrow().conversation.pinned)

            // 6. 恢复策略入口：种一个失败操作 → previewWorkspaceRecovery(真实 operationId)
            recording.delegate.injectWorkspaceOperation(
                operation(
                    id = "op_failed_recovery",
                    sessionId = conv.id,
                    workspaceId = wsId,
                    kind = WorkspaceOperationKind.CREATE,
                    state = WorkspaceOperationState.FAILED,
                    phase = WorkspacePhase.SNAPSHOT,
                    plan = WorkspaceOperationPlan.CreatePlan(
                        requestHash = "h_failed",
                        selection = WorkspaceSelection(),
                        targetPath = "/mock"
                    ),
                    errorCode = WorkspaceErrorCode.SNAPSHOT_INCOMPLETE
                )
            )
            vm.previewRecovery("op_failed_recovery")
            runCurrent()
            assertEquals(1, recording.recoveryPreviewCalls.size)
            assertEquals("op_failed_recovery", recording.recoveryPreviewCalls.single().operationId)
            assertEquals(RecoveryStrategy.RESTORE_TO_NEW_MANAGED, recording.recoveryPreviewCalls.single().strategy)
            val recov = vm.dialogState.value as? WorkspaceDialogState.Recovery
            assertNotNull(recov)
            assertEquals("op_failed_recovery", recov.operationId)
            assertTrue(requireNotNull(recov.preview).token.startsWith("recov_token_"))
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun realSelectorFlowsThroughConversationCreationAndFirstSend() = runComposeUiTest {
        // 使 viewModelScope 回调在调用点同步执行，保证 Compose 渲染读到确定状态。
        Dispatchers.setMain(Dispatchers.Unconfined)
        val recording = RecordingAiCore()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val appState = AppState(aiCore = recording, preferences = InMemoryPreferencesStore(), scope = appScope)
            val vm = WorkspaceViewModel(appState)

            // 新会话创建（生产 CreateConversationInput 路径）即建立工作区选择器状态
            val conv = recording.createConversation(
                CreateConversationInput(
                    projectId = "p1",
                    workspaceSelection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "main")
                )
            ).getOrThrow()
            vm.selectProject("p1")
            vm.attach(conv.id)

            assertNotNull(conv.workspaceId)
            val active = requireNotNull(vm.snapshot?.workspaceInfo?.active)
            assertEquals(WorkspaceKind.MANAGED_WORKTREE, active.workspace.kind)

            var handoffItem = ""
            var managementItem = ""
            var archivedItem = ""
            setContent {
                AppTheme {
                    handoffItem = stringResource(Res.string.workspace_open_handoff)
                    managementItem = stringResource(Res.string.workspace_management_title)
                    archivedItem = stringResource(Res.string.workspace_archived_list_title)
                    CompositionLocalProvider(LocalAppState provides appState) {
                        WorkspaceSelector(viewModel = vm)
                    }
                }
            }
            waitForIdle()

            // 选择器标签：managed worktree 的 branch（生产 resolveSelectorLabels 逻辑）
            onNodeWithText("main").assertIsDisplayed()

            // 打开生产下拉：已有会话入口包含 Handoff / 管理 / 归档列表
            onNodeWithText("main").performClick()
            waitForIdle()
            onNodeWithText(handoffItem).assertIsDisplayed()
            onNodeWithText(managementItem).assertIsDisplayed()
            onNodeWithText(archivedItem).assertIsDisplayed()

            // 点击真实「迁移工作区 (Handoff)」→ 真实 VM 状态 → 真实 AiCore 预览调用
            onNodeWithText(handoffItem).performClick()
            waitForIdle()

            assertEquals(1, recording.previewHandoffCalls.size)
            assertEquals(conv.id, recording.previewHandoffCalls.single().sessionId)
            assertTrue(vm.dialogState.value is WorkspaceDialogState.Handoff)
        } finally {
            appScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun phoneRoutesCompleteBusyConflictCancelRetryAndRecovery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val recording = RecordingAiCore()
        val store = ViewModelStore()
        try {
            val appState = AppState(aiCore = recording, preferences = InMemoryPreferencesStore(), scope = backgroundScope)
            val vm = WorkspaceViewModel(appState)
            store.put("vm", vm)
            runCurrent()

            val conv = recording.createConversation(
                CreateConversationInput(
                    projectId = "p1",
                    workspaceSelection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE)
                )
            ).getOrThrow()
            val wsId = requireNotNull(conv.workspaceId)
            val busyOp = operation(
                id = "op_busy_1",
                sessionId = conv.id,
                workspaceId = wsId,
                kind = WorkspaceOperationKind.CREATE,
                state = WorkspaceOperationState.FAILED,
                phase = WorkspacePhase.VALIDATE,
                plan = WorkspaceOperationPlan.CreatePlan(
                    requestHash = "h_busy",
                    selection = WorkspaceSelection(),
                    targetPath = "/mock"
                ),
                errorCode = WorkspaceErrorCode.WORKSPACE_BUSY
            )
            recording.delegate.injectWorkspaceOperation(busyOp)
            recording.delegate.injectSnapshot(
                conv.id,
                recording.getSnapshot(conv.id).getOrThrow().copy(
                    workspaceInfo = ConversationWorkspaceInfo(active = recording.getConversationWorkspace(conv.id).getOrThrow().active),
                    activeWorkspaceOperation = busyOp
                )
            )
            vm.attach(conv.id)
            runCurrent()

            // 1. VM 暴露操作状态：生产状态条/面板读取同一投影
            assertNotNull(vm.snapshot?.activeWorkspaceOperation)
            val resolved = vm.workspacePresenter.resolveActiveOperation(vm.conversationId, vm.snapshot)
            assertEquals("op_busy_1", resolved?.id)
            assertEquals(WorkspaceErrorCode.WORKSPACE_BUSY, resolved?.error?.code)

            // 2. 取消路由：真实 opId → cancelWorkspaceOperation
            vm.cancelWorkspaceOperation(busyOp)
            runCurrent()
            assertEquals(listOf("op_busy_1"), recording.cancelCalls.toList())

            // 3. 重试路由：真实会话 + failedOperationId → retryWorkspacePreparation
            vm.retryWorkspaceOperation(busyOp)
            runCurrent()
            assertEquals(1, recording.retryPrepCalls.size)
            assertEquals(conv.id, recording.retryPrepCalls.single().sessionId)
            assertEquals("op_busy_1", recording.retryPrepCalls.single().failedOperationId)
            assertTrue(recording.retryPrepCalls.single().requestId.startsWith("retry_"))

            // 4. 恢复路由：RECOVERY_REQUIRED 操作 → 预览 + 执行（同一 token / operationVersion / requestId）
            val recoveryOp = operation(
                id = "op_recovery_1",
                sessionId = conv.id,
                workspaceId = wsId,
                kind = WorkspaceOperationKind.HANDOFF,
                state = WorkspaceOperationState.RECOVERY_REQUIRED,
                phase = WorkspacePhase.SNAPSHOT,
                plan = WorkspaceOperationPlan.HandoffPlan(
                    requestHash = "h_recovery",
                    sourceWorkspaceId = wsId,
                    targetWorkspaceId = "ws_target",
                    targetKind = WorkspaceKind.MANAGED_WORKTREE,
                    taskBranchName = "mederi/task",
                    expectedSourceHeadOid = "oid_src"
                ),
                errorCode = WorkspaceErrorCode.RECOVERY_REQUIRED
            )
            recording.delegate.injectWorkspaceOperation(recoveryOp)

            vm.previewRecovery(recoveryOp.id)
            runCurrent()
            assertEquals(1, recording.recoveryPreviewCalls.size)
            assertEquals(recoveryOp.id, recording.recoveryPreviewCalls.single().operationId)
            val recovState = vm.dialogState.value as? WorkspaceDialogState.Recovery
            assertNotNull(recovState)
            assertEquals(recoveryOp.id, recovState.operationId)
            val recovToken = requireNotNull(recovState.preview).token

            vm.executeRecovery(RecoveryStrategy.RESTORE_TO_NEW_MANAGED)
            runCurrent()
            assertEquals(1, recording.recoveryExecuteCalls.size)
            val exec = recording.recoveryExecuteCalls.single()
            assertEquals(recoveryOp.id, exec.operationId)
            assertEquals(recovToken, exec.input.previewToken)
            assertEquals(RecoveryStrategy.RESTORE_TO_NEW_MANAGED, exec.input.strategy)
            assertEquals(recoveryOp.version, exec.input.expectedOperationVersion)
            assertTrue(exec.input.requestId.startsWith("recover-"))
            assertEquals(WorkspaceDialogState.None, vm.dialogState.value)
            assertEquals(WorkspaceOperationState.SUCCEEDED, recording.delegate.getWorkspaceOperation(recoveryOp.id).getOrThrow().state)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun reconnectDoesNotDuplicateWorkspaceOperations() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val recording = RecordingAiCore()
        val store = ViewModelStore()
        try {
            val appState = AppState(aiCore = recording, preferences = InMemoryPreferencesStore(), scope = backgroundScope)
            val vm1 = WorkspaceViewModel(appState)
            store.put("vm1", vm1)
            runCurrent()

            val conv = recording.createConversation(
                CreateConversationInput(
                    projectId = "p1",
                    workspaceSelection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE)
                )
            ).getOrThrow()
            vm1.attach(conv.id)
            runCurrent()

            // 原 VM 提交一次 Handoff，捕获其真实 requestId / preview token / core 操作
            vm1.openHandoffDialog()
            runCurrent()
            vm1.executeHandoff()
            runCurrent()
            assertEquals(1, recording.handoffCalls.size)
            val requestId = recording.handoffCalls.single().input.requestId
            val previewToken = recording.handoffCalls.single().input.previewToken
            val firstOp = recording.delegate.getWorkspaceOperation("op_$requestId").getOrThrow()

            // 重连：同 AiCore 上新建 VM 并 attach 同一会话；attach 本身不得产生任何新操作。
            // 先 runCurrent 让新 VM 的 init 收集器消费 selectedConversationId 的初始 null，
            // 否则 attach 后收集器首帧会把会话 detach 掉（与生产时序一致：attach 由会话选中事件驱动）。
            val vm2 = WorkspaceViewModel(appState)
            store.put("vm2", vm2)
            runCurrent()
            vm2.attach(conv.id)
            runCurrent()
            assertEquals(1, recording.handoffCalls.size)
            assertEquals(1, recording.previewHandoffCalls.size) // 仅原 VM 的 preview

            // 重连客户端重放同一 in-flight request（同一 requestId）→ core 幂等去重，不产生第二个操作
            val retried = recording.handoffWorkspace(
                conv.id,
                HandoffInput(
                    targetKind = WorkspaceKind.MANAGED_WORKTREE,
                    previewToken = previewToken,
                    requestId = requestId,
                    stopAffectedSessions = false
                )
            ).getOrThrow()
            assertEquals(firstOp.id, retried.id)

            val handoffOps = recording.delegate.listWorkspaceOperations("p1").getOrThrow()
                .filter { it.kind == WorkspaceOperationKind.HANDOFF && it.idempotencyKey == "HANDOFF:$requestId" }
            assertEquals(1, handoffOps.size)

            // 重连后的 VM 用自己新的 requestId 完成一次全新 Handoff，与原操作互不干扰
            vm2.openHandoffDialog()
            runCurrent()
            vm2.executeHandoff()
            runCurrent()
            assertEquals(3, recording.handoffCalls.size)
            assertEquals(2, recording.handoffCalls.map { it.input.requestId }.toSet().size)
            assertEquals(WorkspaceDialogState.None, vm2.dialogState.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}