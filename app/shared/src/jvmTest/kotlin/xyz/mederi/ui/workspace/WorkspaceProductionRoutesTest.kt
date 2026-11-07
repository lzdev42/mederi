package xyz.mederi.ui.workspace

import androidx.lifecycle.ViewModelStore
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.channels.Channel
import xyz.mederi.Mederi
import xyz.mederi.api.CreateProjectRequest
import xyz.mederi.core.bridge.MederiEventAggregator
import xyz.mederi.core.bridge.toContract
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.CreateConversationInput
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.core.bridge.MederiAiCore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.getString
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.WorkspaceDialogs
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.components.WorkspaceOperationStatusBar
import xyz.mederi.ui.components.WorkspaceSelector
import xyz.mederi.tools.sandbox.ExecutionPermissionMode
import xyz.mederi.tools.sandbox.SandboxSettings
import kotlin.test.assertFalse
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.workspace.WorkspaceKind
import xyz.mederi.workspace.WorkspaceOperation
import xyz.mederi.workspace.WorkspaceOperationKind
import xyz.mederi.workspace.WorkspaceOperationPlan
import xyz.mederi.workspace.WorkspaceOperationState
import xyz.mederi.workspace.WorkspacePhase
import xyz.mederi.workspace.WorkspaceSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T09 真实状态投影与事件发射——生产链路（真实 Mederi + 真实桥接聚合器）回归测试。
 *
 * 覆盖 S11 的两个缺陷：
 * 1. getSnapshot / observe 初始快照水合 workspaceInfo + activeWorkspaceOperation（此前恒为 null）。
 * 2. getConversationWorkspace 的活跃操作词表补入 RECOVERY_REQUIRED（崩溃恢复后仍须可操作）。
 *
 * 走真实 Mederi（SQLite 存储 + 真实 WorkspaceManager）+ 真实 [MederiEventAggregator.observe]，
 * 并复用 getSnapshot 内部同一条水合表达式（`getConversationWorkspace(id).toContract()`），
 * 验证"快照/事件"与"存储真理源"一致，而非各自为政。
 */
@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
class WorkspaceProductionRoutesTest {

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(
            listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid") + args
        ).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git $args failed: $output" }
    }

    /**
     * 真实 Mederi fixture：建一个带初始 commit 的 git 项目，返回 (mederi, projectId, projectDir)。
     */
    private fun mederiFixture(block: suspend (Mederi, String, File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("ws-prod-routes").toFile().canonicalFile
        try {
            val mederi = Mederi.create { configDir = File(root, "runtime").path }
            try {
                val projectDir = File(root, "repo").apply { mkdirs() }
                git(projectDir, "init")
                File(projectDir, "base.txt").writeText("base content")
                git(projectDir, "add", ".")
                git(projectDir, "commit", "-m", "base")
                val project = mederi.projects.create(CreateProjectRequest("Prod routes fixture", projectDir.path))
                block(mederi, project.id, projectDir)
            } finally {
                mederi.sandboxManager.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun now() = Instant.now().toString()

    private suspend fun insertBoundSession(mederi: Mederi, sessionId: String, projectId: String, workspaceId: String) {
        mederi.workspaceManager.sessionStore.insert(
            Session(
                id = sessionId,
                projectId = projectId,
                title = "Bound session",
                status = SessionStatus.IDLE,
                agentMode = AgentMode.AUTONOMOUS,
                aiModel = null,
                reasoningLevel = null,
                env = emptyMap(),
                createdAt = now(),
                updatedAt = now(),
                workspaceId = workspaceId
            )
        )
    }

    @Test
    fun realBridgeHydratesWorkspaceAndOperationForSnapshotAndEvents() = mederiFixture { mederi, projectId, projectDir ->
        // 真实创建 + 准备 MANAGED_WORKTREE 工作区到 READY（active 工作区存在且 git 有效）
        val ws = mederi.workspaceManager.createWorkspace(
            projectId, projectDir.path,
            selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD")
        )
        val createOp = mederi.workspaceManager.prepareWorkspace(ws.id, projectDir.path, "req_hydrate")
        assertEquals(WorkspaceOperationState.SUCCEEDED, createOp.state)

        // 绑定会话到该工作区
        val convId = "conv_hydrate"
        insertBoundSession(mederi, convId, projectId, ws.id)

        // 注入一条 PENDING 操作（workspaceId = 绑定工作区），作为"活跃操作"的可操作事实
        mederi.workspaceManager.workspaceStore.saveOperation(
            WorkspaceOperation(
                id = "op_pending_action",
                projectId = projectId,
                sessionId = convId,
                workspaceId = ws.id,
                kind = WorkspaceOperationKind.ACTION,
                state = WorkspaceOperationState.PENDING,
                phase = WorkspacePhase.VALIDATE,
                idempotencyKey = "ACTION:req_p",
                requestHash = "hash_p",
                operationPlan = WorkspaceOperationPlan.ActionPlan(
                    requestHash = "hash_p",
                    workspaceId = ws.id,
                    environmentId = "env_gate",
                    actionId = "act_mark",
                    environmentVersion = 0L
                ),
                createdAt = now(),
                updatedAt = now()
            )
        )

        // 缺陷 1a：getSnapshot 水合（step 5 内部同一表达式）——workspaceInfo 非空且含 active 工作区
        val snapshotWsInfo = runCatching {
            mederi.workspaceManager.getConversationWorkspace(convId).toContract()
        }.getOrNull()
        assertNotNull(snapshotWsInfo, "getSnapshot 水合必须产出 workspaceInfo")
        assertNotNull(snapshotWsInfo.active)
        assertEquals(ws.id, snapshotWsInfo.active.workspace.id, "active 工作区必须是对话绑定的工作区")
        assertEquals(xyz.mederi.core.contract.models.WorkspaceState.READY, snapshotWsInfo.active.workspace.state)
        // activeWorkspaceOperation 反映操作状态：与 workspaceInfo.operation 同源（同为该 PENDING 操作）
        assertNotNull(snapshotWsInfo.operation, "活跃操作必须被投影到 workspaceInfo.operation")
        assertEquals(xyz.mederi.core.contract.models.WorkspaceOperationState.PENDING, snapshotWsInfo.operation?.state)

        // 缺陷 1b：observe 首快照也水合 workspaceInfo + activeWorkspaceOperation（step 7 真实方法）
        val first = MederiEventAggregator.observe(
            conversationId = convId,
            sessions = mederi.sessions,
            modelToProvider = { null },
            workspaceInfo = { id ->
                runCatching { mederi.workspaceManager.getConversationWorkspace(id).toContract() }.getOrNull()
            }
        ).first()

        assertNotNull(first.workspaceInfo, "observe 首快照必须水合 workspaceInfo")
        assertEquals(ws.id, first.workspaceInfo?.active?.workspace?.id, "observe 首快照 active 工作区必须正确")
        // 一致性：activeWorkspaceOperation 与 workspaceInfo.operation 同源（反映同一 PENDING 操作）
        assertEquals(first.workspaceInfo?.operation?.id, first.activeWorkspaceOperation?.id)
        assertEquals(xyz.mederi.core.contract.models.WorkspaceOperationState.PENDING, first.activeWorkspaceOperation?.state)
    }

    @Test
    fun recoveryRequiredRemainsActionableAfterRemoteReconnect() = mederiFixture { mederi, projectId, projectDir ->
        // 真实创建 + 准备一个 MANAGED_WORKTREE 工作区到 READY（崩溃恢复后仍存在的绑定工作区）
        val ws = mederi.workspaceManager.createWorkspace(
            projectId, projectDir.path,
            selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD")
        )
        val createOp = mederi.workspaceManager.prepareWorkspace(ws.id, projectDir.path, "req_recovery")
        assertEquals(WorkspaceOperationState.SUCCEEDED, createOp.state)

        val convId = "conv_recovery"
        insertBoundSession(mederi, convId, projectId, ws.id)

        // 注入崩溃恢复：一条已持久化的 RUNNING HANDOFF 操作，目标工作区缺失（模拟崩溃在落盘后、目标物化前）。
        // 真实 core 的 scanAndRecoverPendingOperations → recoverSingleOperation 会把它安全置为 RECOVERY_REQUIRED。
        val crashedOp = WorkspaceOperation(
            id = "op_crashed_handoff",
            projectId = projectId,
            sessionId = convId,
            workspaceId = ws.id,
            kind = WorkspaceOperationKind.HANDOFF,
            state = WorkspaceOperationState.RUNNING,
            phase = WorkspacePhase.MATERIALIZE_TARGET,
            idempotencyKey = "HANDOFF:req_crash",
            requestHash = "hash_crash",
            sourceWorkspaceId = ws.id,
            targetWorkspaceId = "ws_tgt_missing",
            operationPlan = WorkspaceOperationPlan.HandoffPlan(
                requestHash = "hash_crash",
                sourceWorkspaceId = ws.id,
                targetWorkspaceId = "ws_tgt_missing",
                targetKind = WorkspaceKind.MANAGED_WORKTREE,
                taskBranchName = "task-crash",
                expectedSourceHeadOid = "",
                expectedTargetHeadOid = null,
                stopAffectedSessions = false,
                previewToken = null
            ),
            createdAt = now(),
            updatedAt = now()
        )
        mederi.workspaceManager.workspaceStore.saveOperation(crashedOp)

        // 真实核心恢复扫描：把未决操作归位为 RECOVERY_REQUIRED（而非静默丢弃/借用）
        val recovered = mederi.workspaceManager.scanAndRecoverPendingOperations(projectId)
        val recOp = recovered.firstOrNull { it.id == crashedOp.id }
        assertNotNull(recOp, "崩溃操作必须被恢复扫描认领")
        assertEquals(WorkspaceOperationState.RECOVERY_REQUIRED, recOp.state, "无法安全回滚的操作必须置为 RECOVERY_REQUIRED")

        // 缺陷 2：getConversationWorkspace 的活跃操作词表含 RECOVERY_REQUIRED —— 远程重连后仍须可操作
        val convWs = mederi.workspaceManager.getConversationWorkspace(convId)
        assertEquals(ws.id, convWs.active.workspace.id)
        assertNotNull(convWs.operation, "RECOVERY_REQUIRED 操作必须经 getConversationWorkspace 暴露（可操作）")
        assertEquals(WorkspaceOperationState.RECOVERY_REQUIRED, convWs.operation?.state)

        // getSnapshot 水合（step 5 表达式）：activeWorkspaceOperation 含该 RECOVERY_REQUIRED 操作
        val snapshotWsInfo = runCatching { mederi.workspaceManager.getConversationWorkspace(convId).toContract() }.getOrNull()
        assertNotNull(snapshotWsInfo)
        assertEquals(xyz.mederi.core.contract.models.WorkspaceOperationState.RECOVERY_REQUIRED, snapshotWsInfo.operation?.state)
        assertEquals("op_crashed_handoff", snapshotWsInfo.operation?.id)

        // observe 首快照（step 7 真实方法）：重连后 activeWorkspaceOperation 仍含 RECOVERY_REQUIRED
        val first = MederiEventAggregator.observe(
            conversationId = convId,
            sessions = mederi.sessions,
            modelToProvider = { null },
            workspaceInfo = { id ->
                runCatching { mederi.workspaceManager.getConversationWorkspace(id).toContract() }.getOrNull()
            }
        ).first()
        assertEquals(xyz.mederi.core.contract.models.WorkspaceOperationState.RECOVERY_REQUIRED, first.activeWorkspaceOperation?.state)
        assertEquals("op_crashed_handoff", first.activeWorkspaceOperation?.id)
        assertTrue(first.workspaceInfo != null, "重连快照 workspaceInfo 必须水合")
    }

    // ==================== T12 请求代次归属与面板失效 ====================
    // 可注入延迟/乱序响应的 AiCore fixture：面板请求挂起在 Channel 上，由测试显式投递结果，
    // 精确复现「A 的响应晚于 B 的面板打开才到达」的串写时序。

    private class GenerationMockAiCore(val delegate: MockAiCore = MockAiCore()) : AiCore by delegate {
        val recoveryPreview = Channel<Result<xyz.mederi.core.contract.models.RecoveryPreview>>(Channel.UNLIMITED)
        val handoffPreview = Channel<Result<xyz.mederi.core.contract.models.HandoffPreview>>(Channel.UNLIMITED)
        val handoffExecute = Channel<Result<xyz.mederi.core.contract.models.WorkspaceOperation>>(Channel.UNLIMITED)

        override suspend fun previewWorkspaceRecovery(
            operationId: String,
            strategy: xyz.mederi.core.contract.models.RecoveryStrategy,
            snapshotId: String?,
            keepWorkspaceId: String?
        ): Result<xyz.mederi.core.contract.models.RecoveryPreview> = recoveryPreview.receive()

        override suspend fun previewWorkspaceHandoff(
            sessionId: String,
            input: xyz.mederi.core.contract.models.HandoffPreviewInput
        ): Result<xyz.mederi.core.contract.models.HandoffPreview> = handoffPreview.receive()

        override suspend fun handoffWorkspace(
            sessionId: String,
            input: xyz.mederi.core.contract.models.HandoffInput
        ): Result<xyz.mederi.core.contract.models.WorkspaceOperation> = handoffExecute.receive()
    }

    private fun mockWsInfo(id: String, projectId: String, kind: xyz.mederi.core.contract.models.WorkspaceKind) =
        xyz.mederi.core.contract.models.WorkspaceInfo(
            workspace = xyz.mederi.core.contract.models.Workspace(
                id = id,
                projectId = projectId,
                kind = kind,
                checkoutRoot = "/mock/$projectId",
                projectRelativePath = "",
                state = xyz.mederi.core.contract.models.WorkspaceState.READY,
                generation = 1L,
                createdAt = "2026-11-04T00:00:00Z",
                lastUsedAt = "2026-11-04T00:00:00Z"
            ),
            headOid = "oid_$id",
            branch = "main",
            dirty = false,
            capabilities = setOf(xyz.mederi.core.contract.models.WorkspaceCapability.OPEN_FILES)
        )

    private fun mockHandoffPreview(tag: String, sourceWs: xyz.mederi.core.contract.models.WorkspaceInfo) =
        xyz.mederi.core.contract.models.HandoffPreview(
            token = "token_handoff_$tag",
            expiresAt = "2026-11-04T01:00:00Z",
            source = sourceWs,
            target = sourceWs.copy(workspace = sourceWs.workspace.copy(id = "${sourceWs.workspace.id}_tgt")),
            fingerprints = emptyMap(),
            affectedSessions = emptyList(),
            taskBranch = "task_$tag",
            conflicts = emptyList()
        )

    private fun mockRecoveryPreview(tag: String) =
        xyz.mederi.core.contract.models.RecoveryPreview(
            token = "token_recovery_$tag",
            operationVersion = 1L,
            sourceWorkspace = null,
            targetWorkspace = null,
            eligibleSnapshots = listOf(
                xyz.mederi.core.contract.models.RecoverySnapshotInfo("snap_$tag", "2026-11-04T00:00:00Z", "test", 10L)
            ),
            conflicts = emptyList(),
            expiresAt = "2026-11-04T01:00:00Z"
        )

    private fun mockSucceededHandoffOp(opId: String, sourceWorkspaceId: String) =
        xyz.mederi.core.contract.models.WorkspaceOperation(
            id = opId,
            projectId = "p1",
            sessionId = null,
            workspaceId = sourceWorkspaceId,
            kind = xyz.mederi.core.contract.models.WorkspaceOperationKind.HANDOFF,
            state = xyz.mederi.core.contract.models.WorkspaceOperationState.SUCCEEDED,
            phase = xyz.mederi.core.contract.models.WorkspacePhase.COMPLETE,
            idempotencyKey = "HANDOFF:$opId",
            requestHash = "hash_$opId",
            sourceWorkspaceId = sourceWorkspaceId,
            targetWorkspaceId = "${sourceWorkspaceId}_tgt",
            createdAt = now(),
            updatedAt = now(),
            operationPlan = xyz.mederi.core.contract.models.WorkspaceOperationPlan.HandoffPlan(
                requestHash = "hash_$opId",
                sourceWorkspaceId = sourceWorkspaceId,
                targetWorkspaceId = "${sourceWorkspaceId}_tgt",
                targetKind = xyz.mederi.core.contract.models.WorkspaceKind.MANAGED_WORKTREE,
                taskBranchName = "task_done",
                expectedSourceHeadOid = "oid_src"
            )
        )

    /**
     * T12：面板请求代次（dialogRequestGeneration）与归属票据（activeDialogRequest）必须同时防御
     * ① 跨会话串写（A→B：A 的响应晚到，不得覆盖 B 已打开的面板）与
     * ② 同会话跨面板串写（dismiss 后重开另一面板 / 刷新预览：旧响应/旧错误晚到，不得覆盖新面板）。
     *
     * 覆盖场景 a（A→B）、b（A→B→A 旧响应不自动恢复）、c（dismiss 后重开）、d（刷新预览）、e（旧执行失败晚到）。
     */
    @Test
    fun dialogRequestsAreInvalidatedOnSwitchDismissAndReplacement() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val mock = GenerationMockAiCore()
        val store = ViewModelStore()
        try {
            val appState = AppState(aiCore = mock, preferences = InMemoryPreferencesStore(), scope = backgroundScope)
            val vm = WorkspaceViewModel(appState)
            store.put("vm", vm)
            runCurrent()

            // 项目与两个会话（各自绑定 LOCAL 工作区，handoff 目标推导为 MANAGED_WORKTREE）
            mock.delegate.createProject(CreateProjectInput(name = "Gen Proj", directory = "/tmp/gen"))
            vm.selectProject("p1")
            runCurrent()
            val convA = mock.delegate.createConversation(
                CreateConversationInput(projectId = "p1", workspaceSelection = xyz.mederi.core.contract.models.WorkspaceSelection(kind = xyz.mederi.core.contract.models.WorkspaceKind.LOCAL))
            ).getOrThrow()
            val convB = mock.delegate.createConversation(
                CreateConversationInput(projectId = "p1", workspaceSelection = xyz.mederi.core.contract.models.WorkspaceSelection(kind = xyz.mederi.core.contract.models.WorkspaceKind.LOCAL))
            ).getOrThrow()
            val wsA = "ws_${convA.id}"
            val wsB = "ws_${convB.id}"

            // ── 场景 a（A→B）：A 请求 recovery 未回 → 切 B → B 打开管理 → A 的 recovery 响应晚到 → B 管理面板不被覆盖
            vm.attach(convA.id)
            runCurrent()
            vm.previewRecovery(operationId = "op_recover_a")
            runCurrent() // recovery 请求挂起在 mock 上
            assertEquals(xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.None, vm.dialogState.value)

            vm.attach(convB.id)
            runCurrent()
            assertEquals(xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.None, vm.dialogState.value, "切会话必须关闭面板")

            vm.openManagement()
            runCurrent()
            // B 的管理结果由 delegate 直接返回，runCurrent 后应已落面板
            val mgmt = vm.dialogState.value as? xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.Management
            assertNotNull(mgmt, "B 的管理面板必须打开")
            assertTrue(mgmt.workspaces.isNotEmpty(), "管理面板必须有数据")
            // 管理面板是项目级列表（listWorkspaces(projectId)，与真实 core 同语义）：列出 p1 下全部工作区，
            // 而非仅当前会话的绑定工作区。B 会话绑定工作区 wsB 必须在列，A 会话的 wsA 同样在列。
            assertTrue(mgmt.workspaces.any { it.workspace.id == wsB }, "管理面板必须包含会话 B 绑定的工作区")
            assertTrue(mgmt.workspaces.any { it.workspace.id == wsA }, "项目级管理面板必须同时列出会话 A 的工作区")
            val mgmtSnapshot = vm.dialogState.value

            // A 的 recovery 响应**晚于** B 面板打开才到达（缺陷复现时序）：代次/归属校验必须拦截
            mock.recoveryPreview.send(Result.success(mockRecoveryPreview("stale_a")))
            runCurrent()
            assertEquals(mgmtSnapshot, vm.dialogState.value, "A 的迟到 recovery 响应不得覆盖 B 已打开的管理面板")

            // ── 场景 b（A→B→A）：切回 A，旧 recovery 响应不自动恢复面板
            vm.attach(convA.id)
            runCurrent()
            assertEquals(xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.None, vm.dialogState.value, "切回 A 后面板必须为空，旧 recovery 不自动恢复")
            assertEquals(null, (vm.dialogState.value as? xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.Recovery)?.preview)

            // ── 场景 c（dismiss 后重开另一面板）：A 再发 recovery → dismiss → 打开 handoff → 旧 recovery 响应晚到 → handoff 不被覆盖
            vm.previewRecovery(operationId = "op_recover_a2")
            runCurrent()
            assertEquals(xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.None, vm.dialogState.value, "预览响应未到前面板为 None")
            vm.dismissDialog()
            assertEquals(xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.None, vm.dialogState.value)

            vm.openHandoffDialog()
            runCurrent()
            assertEquals(xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.None, vm.dialogState.value, "handoff 预览响应未到前面板为 None")
            // 旧 recovery 响应（同会话 A，旧代次）晚到：代次校验必须拦截
            mock.recoveryPreview.send(Result.success(mockRecoveryPreview("stale_a2")))
            runCurrent()
            assertTrue(
                vm.dialogState.value !is xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.Recovery,
                "同会话旧 recovery 响应不得覆盖已打开的 handoff 面板"
            )
            mock.handoffPreview.send(Result.success(mockHandoffPreview("c", mockWsInfo(wsA, "p1", xyz.mederi.core.contract.models.WorkspaceKind.LOCAL))))
            runCurrent()
            val handoffC = vm.dialogState.value as? xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.Handoff
            assertNotNull(handoffC, "handoff 面板必须最终打开")
            assertEquals("token_handoff_c", handoffC.preview?.token)
            assertNull(handoffC.errorMessage)

            // ── 场景 d（刷新预览）：handoff 预览在途时重发预览 → 旧 preview 响应晚到 → 新 preview 不被旧覆盖
            vm.openHandoffDialog()
            runCurrent() // 第 1 次预览在途
            vm.openHandoffDialog()
            runCurrent() // 第 2 次预览在途（代次 +1），第 1 次请求被作废
            mock.handoffPreview.send(Result.success(mockHandoffPreview("d_old", mockWsInfo(wsA, "p1", xyz.mederi.core.contract.models.WorkspaceKind.LOCAL))))
            runCurrent() // 第 1 次（旧代次）preview 响应晚到：代次校验必须丢弃
            mock.handoffPreview.send(Result.success(mockHandoffPreview("d_new", mockWsInfo(wsA, "p1", xyz.mederi.core.contract.models.WorkspaceKind.LOCAL))))
            runCurrent() // 第 2 次（当前代次）preview 响应生效
            val handoffD = vm.dialogState.value as? xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.Handoff
            assertNotNull(handoffD, "handoff 面板必须打开")
            assertEquals("token_handoff_d_new", handoffD.preview?.token, "新 preview 必须生效，旧 preview 晚到不得覆盖")

            // ── 场景 e（旧执行失败晚到）：executeHandoff 失败 → dismiss → 新 handoff 面板 → 旧错误不覆盖新面板
            vm.executeHandoff()
            runCurrent()
            assertTrue(
                (vm.dialogState.value as? xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.Handoff)?.loading == true,
                "executeHandoff 提交后必须进入 loading"
            )
            mock.handoffExecute.send(Result.failure(IllegalStateException("handoff failed stale")))
            runCurrent()
            val handoffE = vm.dialogState.value as? xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.Handoff
            assertNotNull(handoffE)
            assertEquals("handoff failed stale", handoffE.errorMessage, "失败响应必须保留在对话框上")

            vm.dismissDialog()
            assertEquals(xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.None, vm.dialogState.value)

            vm.openHandoffDialog()
            runCurrent() // 新 preview 在途
            // 旧执行失败的**再次**晚到模拟（同一旧代次的错误响应重放）：不得覆盖新面板。
            // 旧代次执行请求已无在途接收，重放响应在传输层被丢弃——fixture 在此排空，
            // 防止缓冲中的旧响应误投递给下一条在途执行请求（Channel 按缓冲先出）。
            mock.handoffExecute.send(Result.failure(IllegalStateException("handoff failed replay")))
            while (mock.handoffExecute.tryReceive().isSuccess) { }
            runCurrent()
            mock.handoffPreview.send(Result.success(mockHandoffPreview("e", mockWsInfo(wsA, "p1", xyz.mederi.core.contract.models.WorkspaceKind.LOCAL))))
            runCurrent()
            val handoffE2 = vm.dialogState.value as? xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.Handoff
            assertNotNull(handoffE2, "重新打开的 handoff 面板必须存在")
            assertEquals("token_handoff_e", handoffE2.preview?.token, "新 preview 必须生效")
            assertNull(handoffE2.errorMessage, "旧执行失败不得覆盖新面板")

            // 收尾：成功执行 handoff 后面板必须关闭
            vm.executeHandoff()
            runCurrent()
            mock.handoffExecute.send(Result.success(mockSucceededHandoffOp("op_handoff_done", wsA)))
            runCurrent()
            assertEquals(xyz.mederi.ui.WorkspaceViewModel.WorkspaceDialogState.None, vm.dialogState.value, "handoff 成功后必须关闭面板")
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    // ══════════ S13：真实 UI 装配（RecordingAiCore 透传真实 MederiAiCore）══════════

    /** 透传记录型 AiCore：委托真实 MederiAiCore（同 configDir 真实持久化），记录工作区 API 调用参数与结果。 */
    private class RecordingRealAiCore(val core: MederiAiCore) : AiCore by core {
        val previewHandoffCalls = mutableListOf<Pair<String, HandoffPreviewInput>>()
        val handoffCalls = mutableListOf<Pair<String, HandoffInput>>()
        val saveEnvCalls = mutableListOf<Triple<String, ProjectEnvironment, Long>>()
        val runActionCalls = mutableListOf<Pair<String, WorkspaceActionInput>>()
        val runActionResults = mutableListOf<Result<xyz.mederi.core.contract.models.WorkspaceOperation>>()
        val worktreeSettingsCalls = mutableListOf<WorktreeSettings>()
        val createBranchCalls = mutableListOf<Pair<String, CreateWorkspaceBranchInput>>()
        val listEnvCalls = mutableListOf<Pair<String, String?>>()
        val recoveryPreviewCalls = mutableListOf<String>()

        override suspend fun previewWorkspaceHandoff(sessionId: String, input: HandoffPreviewInput): Result<HandoffPreview> {
            previewHandoffCalls.add(sessionId to input)
            return core.previewWorkspaceHandoff(sessionId, input)
        }

        override suspend fun handoffWorkspace(sessionId: String, input: HandoffInput): Result<xyz.mederi.core.contract.models.WorkspaceOperation> {
            handoffCalls.add(sessionId to input)
            return core.handoffWorkspace(sessionId, input)
        }

        override suspend fun saveProjectEnvironment(projectId: String, environment: ProjectEnvironment, expectedVersion: Long, workspaceId: String?): Result<ProjectEnvironment> {
            saveEnvCalls.add(Triple(projectId, environment, expectedVersion))
            return core.saveProjectEnvironment(projectId, environment, expectedVersion, workspaceId)
        }

        override suspend fun runWorkspaceAction(workspaceId: String, input: WorkspaceActionInput): Result<xyz.mederi.core.contract.models.WorkspaceOperation> {
            runActionCalls.add(workspaceId to input)
            return core.runWorkspaceAction(workspaceId, input).also { runActionResults.add(it) }
        }

        override suspend fun updateWorktreeSettings(settings: WorktreeSettings): Result<WorktreeSettings> {
            worktreeSettingsCalls.add(settings)
            return core.updateWorktreeSettings(settings)
        }

        override suspend fun createWorkspaceBranch(workspaceId: String, input: CreateWorkspaceBranchInput): Result<WorkspaceInfo> {
            createBranchCalls.add(workspaceId to input)
            return core.createWorkspaceBranch(workspaceId, input)
        }

        override suspend fun listProjectEnvironments(projectId: String, workspaceId: String?): Result<List<ProjectEnvironment>> {
            listEnvCalls.add(projectId to workspaceId)
            return core.listProjectEnvironments(projectId, workspaceId)
        }

        override suspend fun previewWorkspaceRecovery(operationId: String, strategy: RecoveryStrategy, snapshotId: String?, keepWorkspaceId: String?): Result<RecoveryPreview> {
            recoveryPreviewCalls.add(operationId)
            return core.previewWorkspaceRecovery(operationId, strategy, snapshotId, keepWorkspaceId)
        }
    }

    private class RealUiAssembly(
        val root: File,
        val realCore: MederiAiCore,
        val recording: RecordingRealAiCore,
        val appState: AppState,
        val vm: WorkspaceViewModel,
        val scope: CoroutineScope,
        val convId: String,
        val projectId: String,
        val workspaceId: String,
    ) {
        fun close() {
            scope.cancel()
            runCatching { root.deleteRecursively() }
        }
    }

    /**
     * 真实 UI 装配：真实 git 仓库 → 真实 Mederi 预置（项目/工作区/环境/会话/RECOVERY_REQUIRED 操作）→
     * 关闭预置实例 → 同 configDir 的真实 MederiAiCore（initialize 后共享同一 SQLite 真实持久化）→
     * 透传记录 → AppState + 真实 WorkspaceViewModel。
     */
    private fun realUiAssembly(
        kind: WorkspaceKind,
        seedEnv: Boolean = false,
        seedRecoveryRequired: Boolean = false,
    ): RealUiAssembly = runBlocking {
        val repoRoot = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val root = File(repoRoot, "temp/ws-ui-real-${System.nanoTime()}").apply { mkdirs() }
        val projectDir = File(root, "repo").apply { mkdirs() }
        git(projectDir, "init")
        File(projectDir, "base.txt").writeText("base content")
        git(projectDir, "add", ".")
        git(projectDir, "commit", "-m", "base")
        val runtime = File(root, "runtime")
        val convId = "conv_ui_real"
        val setup = Mederi.create { configDir = runtime.path }
        val projectId: String
        val workspaceId: String
        try {
            val project = setup.projects.create(CreateProjectRequest("UI real fixture", projectDir.path))
            projectId = project.id
            setup.sandbox.updateSettings(projectId, SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS))
            val ws = setup.workspaceManager.createWorkspace(
                projectId, projectDir.path,
                selection = WorkspaceSelection(kind = kind, baseRef = "HEAD")
            )
            if (kind == WorkspaceKind.MANAGED_WORKTREE) {
                val op = setup.workspaceManager.prepareWorkspace(ws.id, projectDir.path, "req_ui_real")
                check(op.state == WorkspaceOperationState.SUCCEEDED) { "fixture prepare failed: ${op.state}" }
            }
            workspaceId = ws.id
            if (seedEnv) {
                setup.workspaces.saveProjectEnvironment(
                    projectId,
                    xyz.mederi.workspace.ProjectEnvironment(
                        "env_ui", "UiEnv", 0L,
                        setup = xyz.mederi.workspace.PlatformScript(default = "echo setup_ok"),
                        actions = listOf(
                            xyz.mederi.workspace.EnvironmentAction(
                                "act_ui", "UiAction",
                                script = xyz.mederi.workspace.PlatformScript(default = "echo action_ok")
                            )
                        )
                    ),
                    0L, ws.id
                )
            }
            if (seedRecoveryRequired) {
                setup.workspaceManager.workspaceStore.saveOperation(
                    xyz.mederi.workspace.WorkspaceOperation(
                        id = "op_recovery_ui",
                        projectId = projectId,
                        sessionId = convId,
                        workspaceId = ws.id,
                        kind = xyz.mederi.workspace.WorkspaceOperationKind.ARCHIVE,
                        state = xyz.mederi.workspace.WorkspaceOperationState.RECOVERY_REQUIRED,
                        phase = xyz.mederi.workspace.WorkspacePhase.VALIDATE,
                        idempotencyKey = "ARCHIVE:req_recovery_ui",
                        requestHash = "hash_recovery_ui",
                        operationPlan = xyz.mederi.workspace.WorkspaceOperationPlan.ActionPlan(
                            requestHash = "hash_recovery_ui",
                            workspaceId = ws.id,
                            environmentId = "env_ui",
                            actionId = "act_ui",
                            environmentVersion = 0L
                        ),
                        createdAt = now(),
                        updatedAt = now()
                    )
                )
            }
            setup.workspaceManager.sessionStore.insert(
                Session(
                    id = convId, projectId = projectId, title = "UI real", status = SessionStatus.IDLE,
                    agentMode = AgentMode.AUTONOMOUS, aiModel = null, reasoningLevel = null,
                    env = emptyMap(), createdAt = now(), updatedAt = now(), workspaceId = workspaceId
                )
            )
        } finally {
            setup.sandboxManager.close()
        }
        val realCore = MederiAiCore(configDir = runtime.path)
        realCore.initialize().getOrThrow()
        val recording = RecordingRealAiCore(realCore)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val appState = AppState(aiCore = recording, preferences = InMemoryPreferencesStore(), scope = scope)
        val vm = WorkspaceViewModel(appState)
        vm.selectProject(projectId)
        vm.attach(convId)
        RealUiAssembly(root, realCore, recording, appState, vm, scope, convId, projectId, workspaceId)
    }

    /** 从真实桥计算 selector 标签（与 WorkspaceSelector.shortLabel 保持一致，使用 composeResources 本地化文本）。 */
    private suspend fun realSelectorLabel(asm: RealUiAssembly): String {
        val info = asm.realCore.getConversationWorkspace(asm.convId).getOrThrow()
        val active = info.active
        return if (active.workspace.kind == xyz.mederi.core.contract.models.WorkspaceKind.LOCAL) {
            getString(Res.string.workspace_kind_local)
        } else {
            active.branch ?: active.workspace.taskBranchName ?: getString(Res.string.workspace_kind_worktree)
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.assertTouchHeightAtLeast44(buttonText: String) {
        val height = onNodeWithText(buttonText).fetchSemanticsNode().size.height
        assertTrue(height >= 44, "$buttonText 按钮触达高度 ${height}px < 44px")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.assertNoHorizontalOverflow(nodeText: String, containerWidthPx: Float) {
        val node = onNodeWithText(nodeText).fetchSemanticsNode()
        println("DEBUG_OVERFLOW: $nodeText pos=${node.positionInRoot} size=${node.size} boundsInRoot=${node.boundsInRoot}")
        assertTrue(node.size.width <= containerWidthPx, "$nodeText 宽 ${node.size.width}px 超出容器宽 ${containerWidthPx}px")
    }

    // ── 测试 2：Local↔Managed Handoff 从真实工作区状态驱动（真实菜单→preview→确认点击） ──
    @Test
    fun localAndManagedHandoffAreDrivenFromRealWorkspaceState() = runComposeUiTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val asm = realUiAssembly(WorkspaceKind.LOCAL)
        try {
            val handoffMenuLabel = getString(Res.string.workspace_open_handoff)
            val executeHandoffLabel = getString(Res.string.handoff_execute_action)
            setContent {
                AppTheme {
                    CompositionLocalProvider(LocalAppState provides asm.appState) {
                        WorkspaceSelector(viewModel = asm.vm)
                        WorkspaceDialogs(viewModel = asm.vm)
                    }
                }
            }
            // 真实快照水合：active workspace = LOCAL
            waitUntil(null, 15_000) { asm.vm.snapshot?.workspaceInfo?.active?.workspace?.kind == xyz.mederi.core.contract.models.WorkspaceKind.LOCAL }
            // 真实 selector 标签
            val localLabel = realSelectorLabel(asm)
            onNodeWithText(localLabel).performClick()
            waitForIdle()
            // 真实菜单项：迁移工作区
            onNodeWithText(handoffMenuLabel).performClick()
            waitForIdle()
            // preview 调用：targetKind 从真实 Local 状态推导为 MANAGED_WORKTREE（不是 UI 猜的）
            waitUntil(null, 15_000) { asm.recording.previewHandoffCalls.isNotEmpty() }
            val previewCall = asm.recording.previewHandoffCalls.first()
            assertEquals(asm.convId, previewCall.first, "preview 必须归当前会话")
            assertEquals(xyz.mederi.core.contract.models.WorkspaceKind.MANAGED_WORKTREE, previewCall.second.targetKind, "targetKind 必须从真实 Local 推导为 MANAGED")
            // 面板显示真实 preview（源/目标/token 来自真实 core，非 UI 猜测）
            waitUntil(null, 15_000) {
                (asm.vm.dialogState.value as? WorkspaceViewModel.WorkspaceDialogState.Handoff)?.preview != null
            }
            val dialog = asm.vm.dialogState.value as WorkspaceViewModel.WorkspaceDialogState.Handoff
            val preview = dialog.preview!!
            assertTrue(preview.token.isNotBlank(), "preview token 必须非空")
            assertEquals(xyz.mederi.core.contract.models.WorkspaceKind.MANAGED_WORKTREE, preview.target.workspace.kind, "面板目标必须是真实 preview 的 MANAGED")
            assertEquals(xyz.mederi.core.contract.models.WorkspaceKind.LOCAL, preview.source.workspace.kind, "面板源必须是真实 preview 的 LOCAL")
            // 真实确认点击：开始交接
            assertTouchHeightAtLeast44(executeHandoffLabel)
            onNodeWithText(executeHandoffLabel).performClick()
            waitForIdle()
            waitUntil(null, 60_000) { asm.recording.handoffCalls.isNotEmpty() }
            // 提交的 token 必须等于真实 preview 的 token（未重猜）
            assertEquals(preview.token, asm.recording.handoffCalls.first().second.previewToken, "确认必须提交真实 preview token")
            // 真实执行后：会话绑定切到 managed worktree（经真实桥断言）
            waitUntil(null, 60_000) {
                runBlocking {
                    asm.realCore.getConversationWorkspace(asm.convId).getOrNull()
                        ?.active?.workspace?.kind == xyz.mederi.core.contract.models.WorkspaceKind.MANAGED_WORKTREE
                }
            }
            // 反向 Managed→Local：同样走真实菜单→preview→确认
            waitUntil(null, 15_000) { asm.vm.snapshot?.workspaceInfo?.active?.workspace?.kind == xyz.mederi.core.contract.models.WorkspaceKind.MANAGED_WORKTREE }
            val managedLabel = realSelectorLabel(asm)
            onNodeWithText(managedLabel).performClick()
            waitForIdle()
            onNodeWithText(handoffMenuLabel).performClick()
            waitForIdle()
            waitUntil(null, 15_000) { asm.recording.previewHandoffCalls.size >= 2 }
            assertEquals(xyz.mederi.core.contract.models.WorkspaceKind.LOCAL, asm.recording.previewHandoffCalls[1].second.targetKind, "反向 targetKind 必须从真实 Managed 推导为 LOCAL")
            waitUntil(null, 15_000) {
                (asm.vm.dialogState.value as? WorkspaceViewModel.WorkspaceDialogState.Handoff)?.preview != null
            }
            val backPreview = (asm.vm.dialogState.value as WorkspaceViewModel.WorkspaceDialogState.Handoff).preview!!
            onNodeWithText(executeHandoffLabel).performClick()
            waitForIdle()
            waitUntil(null, 60_000) { asm.recording.handoffCalls.size >= 2 }
            assertEquals(backPreview.token, asm.recording.handoffCalls[1].second.previewToken, "反向确认必须提交真实 preview token")
            waitUntil(null, 60_000) {
                runBlocking {
                    asm.realCore.getConversationWorkspace(asm.convId).getOrNull()
                        ?.active?.workspace?.kind == xyz.mederi.core.contract.models.WorkspaceKind.LOCAL
                }
            }
        } finally {
            Dispatchers.resetMain()
            asm.close()
        }
    }

    // ── 测试 5：手机（320/280dp、fontScale 1.6）环境编辑/动作/限额/建分支/恢复（真实点击 + 44dp 触达 + 无溢出） ──
    @Test
    fun phoneCanConfigureEnvironmentRunActionSetRetentionAndCreateBranch() = runComposeUiTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val asm = realUiAssembly(WorkspaceKind.MANAGED_WORKTREE, seedEnv = true, seedRecoveryRequired = true)
        val containerWidthPx = 320f
        val currentWidth = androidx.compose.runtime.mutableStateOf(320.dp)
        try {
            val editEnvironmentLabel = getString(Res.string.workspace_env_edit)
            val saveEnvironmentLabel = getString(Res.string.workspace_env_save)
            val runActionLabel = getString(Res.string.workspace_env_run_action)
            val worktreeSettingsLabel = getString(Res.string.workspace_worktree_settings)
            val saveSettingsLabel = getString(Res.string.workspace_host_settings_save)
            val branchMenuLabel = getString(Res.string.workspace_create_branch)
            val createBranchLabel = getString(Res.string.workspace_create_branch_create)
            val restoreLabel = getString(Res.string.workspace_restore_action)
            val newWorktreeRoot = File(asm.root, "worktrees").canonicalPath
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                    AppTheme {
                        CompositionLocalProvider(LocalAppState provides asm.appState) {
                            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.width(currentWidth.value)) {
                                WorkspaceSelector(viewModel = asm.vm)
                                WorkspaceDialogs(viewModel = asm.vm)
                                // 状态条（与 ChatListContent 相同的接线：真实 activeWorkspaceOperation + 真实 VM 回调）
                                val activeOp = asm.vm.snapshot?.activeWorkspaceOperation
                                    ?: asm.vm.snapshot?.workspaceInfo?.operation
                                if (activeOp != null) {
                                    WorkspaceOperationStatusBar(
                                        operation = activeOp,
                                        onRetry = { asm.vm.retryWorkspaceOperation(activeOp) },
                                        onCancel = { asm.vm.cancelWorkspaceOperation(activeOp) },
                                        onRecover = { asm.vm.previewRecovery(activeOp.id) },
                                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
            waitUntil(null, 15_000) { asm.vm.snapshot?.workspaceInfo?.active?.workspace?.kind == xyz.mederi.core.contract.models.WorkspaceKind.MANAGED_WORKTREE }
            val label = realSelectorLabel(asm)
            println("DEBUG_LABEL=$label")

            // a. 环境编辑：真实入口 → 真实表单 → 保存
            onNodeWithText(label).performClick()
            waitForIdle()
            onNodeWithText(editEnvironmentLabel).performClick()
            waitForIdle()
            waitUntil(null, 15_000) { asm.vm.environmentEditorState.value is WorkspaceViewModel.EnvironmentEditorState.Editing }
            // 预置环境经真实桥回读：动作行可见
            onNodeWithText("UiAction").assertIsDisplayed()
            // 修改名称 + setup 脚本（真实输入框）
            onNodeWithContentDescription("env_name_field").performTextClearance()
            onNodeWithContentDescription("env_name_field").performTextInput("UiEnvV2")
            onNodeWithContentDescription("env_setup_field").performTextClearance()
            onNodeWithContentDescription("env_setup_field").performTextInput("echo setup_v2")
            assertTouchHeightAtLeast44(saveEnvironmentLabel)
            assertNoHorizontalOverflow(saveEnvironmentLabel, containerWidthPx)
            onNodeWithText(saveEnvironmentLabel).performClick()
            waitForIdle()
            waitUntil(null, 15_000) { asm.recording.saveEnvCalls.isNotEmpty() }
            val saveCall = asm.recording.saveEnvCalls.first()
            assertEquals(asm.projectId, saveCall.first, "保存必须归当前项目")
            assertEquals("UiEnvV2", saveCall.second.name, "保存的环境名必须是输入值")
            assertEquals("env_ui", saveCall.second.id, "保存必须复用回读的环境 ID")
            assertEquals(1L, saveCall.third, "expectedVersion 必须是回读的版本 1")
            // 持久回读：真实桥 listProjectEnvironments 含已保存环境（新名）
            waitUntil(null, 15_000) {
                runBlocking {
                    asm.realCore.listProjectEnvironments(asm.projectId, asm.workspaceId).getOrNull()
                        ?.any { it.id == "env_ui" && it.name == "UiEnvV2" } == true
                }
            }

            // b. 运行动作：环境面板真实按钮
            assertTouchHeightAtLeast44(runActionLabel)
            onNodeWithText(runActionLabel).performClick()
            waitForIdle()
            waitUntil(null, 30_000) { asm.recording.runActionCalls.isNotEmpty() }
            val runCall = asm.recording.runActionCalls.first()
            assertEquals(asm.workspaceId, runCall.first, "动作必须在当前工作区执行")
            assertEquals(asm.convId, runCall.second.conversationId, "动作必须带上发起会话")
            assertEquals("act_ui", runCall.second.actionId, "actionId 必须是环境动作 ID")
            assertEquals("env_ui", runCall.second.environmentId, "environmentId 必须是环境 ID")
            waitUntil(null, 30_000) {
                asm.recording.runActionResults.firstOrNull()?.getOrNull()?.state == xyz.mederi.core.contract.models.WorkspaceOperationState.SUCCEEDED
            }
            assertEquals(asm.convId, asm.recording.runActionResults.first().getOrThrow().sessionId)
            // 关闭环境编辑面板
            onNodeWithContentDescription("Close").performClick()
            waitForIdle()

            // c. 宿主工作副本设置：根/最大副本数/自动清理
            onNodeWithText(label).performClick()
            waitForIdle()
            onNodeWithText(worktreeSettingsLabel).performClick()
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithContentDescription("worktree_root_field").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithContentDescription("worktree_root_field").performTextClearance()
            onNodeWithContentDescription("worktree_root_field").performTextInput(newWorktreeRoot)
            onNodeWithContentDescription("worktree_max_field").performTextClearance()
            onNodeWithContentDescription("worktree_max_field").performTextInput("15")
            assertTouchHeightAtLeast44(saveSettingsLabel)
            assertNoHorizontalOverflow(saveSettingsLabel, containerWidthPx)
            onNodeWithText(saveSettingsLabel).performClick()
            waitForIdle()
            waitUntil(null, 15_000) { asm.recording.worktreeSettingsCalls.isNotEmpty() }
            val wtCall = asm.recording.worktreeSettingsCalls.first()
            assertEquals(newWorktreeRoot, wtCall.rootPath, "root 必须是输入值")
            assertEquals(15, wtCall.maxManagedWorktrees, "max 必须是输入值")
            assertTrue(wtCall.automaticCleanup, "自动清理保持开启")
            // 持久回读：真实桥 getWorktreeSettings 返回保存值
            waitUntil(null, 15_000) {
                runBlocking {
                    asm.realCore.getWorktreeSettings().getOrNull()?.rootPath == newWorktreeRoot
                }
            }

            // d. 建分支：名称 + expectedGeneration（真实状态）；同名冲突错误可见
            onNodeWithText(label).performClick()
            waitForIdle()
            onNodeWithText(branchMenuLabel).performClick()
            waitForIdle()
            onNodeWithContentDescription("branch_name_field").performTextInput("ui-test-branch")
            assertTouchHeightAtLeast44(createBranchLabel)
            assertNoHorizontalOverflow(createBranchLabel, containerWidthPx)
            onNodeWithText(createBranchLabel).performClick()
            waitForIdle()
            waitUntil(null, 30_000) { asm.recording.createBranchCalls.isNotEmpty() }
            val branchCall = asm.recording.createBranchCalls.first()
            assertEquals(asm.workspaceId, branchCall.first, "建分支必须在当前工作区")
            assertEquals("ui-test-branch", branchCall.second.name, "分支名必须是输入值")
            val activeInfo = asm.realCore.getConversationWorkspace(asm.convId).getOrThrow().active
            assertEquals(activeInfo.workspace.generation, branchCall.second.expectedGeneration, "expectedGeneration 必须是当前真实 generation")
            assertEquals(activeInfo.headOid, branchCall.second.expectedHead, "expectedHead 必须是当前真实 HEAD")
            // 同名再建 → 冲突错误在 UI 可见（不自动改名）
            waitUntil(null, 30_000) {
                asm.vm.dialogState.value is WorkspaceViewModel.WorkspaceDialogState.None ||
                    asm.vm.dialogState.value is WorkspaceViewModel.WorkspaceDialogState.CreateBranch
            }
            asm.vm.dismissDialog()
            waitForIdle()
            onNodeWithText(label).performClick()
            waitForIdle()
            onNodeWithText(branchMenuLabel).performClick()
            waitForIdle()
            onNodeWithContentDescription("branch_name_field").performTextInput("ui-test-branch")
            onNodeWithText(createBranchLabel).performClick()
            waitForIdle()
            waitUntil(null, 30_000) {
                (asm.vm.dialogState.value as? WorkspaceViewModel.WorkspaceDialogState.CreateBranch)?.errorMessage != null
            }
            val conflictMessage = (asm.vm.dialogState.value as WorkspaceViewModel.WorkspaceDialogState.CreateBranch).errorMessage!!
            onNodeWithText(conflictMessage).assertExists("同名建分支的冲突错误必须在 UI 可见")

            // e. 恢复：RECOVERY_REQUIRED 操作（预置经真实 store）→ 状态条恢复按钮可见 → 44dp 触达 → 真实点击
            asm.vm.dismissDialog()
            waitForIdle()
            assertTouchHeightAtLeast44(restoreLabel)
            onNodeWithText(restoreLabel).performClick()
            waitForIdle()
            waitUntil(null, 15_000) { asm.recording.recoveryPreviewCalls.isNotEmpty() }
            assertEquals("op_recovery_ui", asm.recording.recoveryPreviewCalls.first(), "恢复点击必须预览预置的 RECOVERY_REQUIRED 操作")
            asm.vm.dismissDialog()
            waitForIdle()

            // ── 在 280.dp 窄屏下重复关键路径（触达≥44dp 且 节点宽度不横向溢出） ──
            currentWidth.value = 280.dp
            waitForIdle()
            assertTouchHeightAtLeast44(restoreLabel)
            assertNoHorizontalOverflow(restoreLabel, 280f)

            // 环境编辑面板关键路径
            onNodeWithText(label).performClick()
            waitForIdle()
            onNodeWithText(editEnvironmentLabel).performClick()
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText(saveEnvironmentLabel).fetchSemanticsNodes().isNotEmpty()
            }
            assertTouchHeightAtLeast44(saveEnvironmentLabel)
            assertNoHorizontalOverflow(saveEnvironmentLabel, 280f)
            onNodeWithContentDescription("Close").performClick()
            waitForIdle()

            // 创建分支面板关键路径
            onNodeWithText(label).performClick()
            waitForIdle()
            onNodeWithText(branchMenuLabel).performClick()
            waitForIdle()
            assertTouchHeightAtLeast44(createBranchLabel)
            assertNoHorizontalOverflow(createBranchLabel, 280f)
            onNodeWithContentDescription("Close").performClick()
            waitForIdle()
        } finally {
            Dispatchers.resetMain()
            asm.close()
        }
    }
}
