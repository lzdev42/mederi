package xyz.mederi.workspace

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.domain.model.*
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemorySessionStore
import xyz.mederi.store.InMemorySettingsStore
import xyz.mederi.store.InMemoryWorkspaceStore
import xyz.mederi.store.WorkspaceStore
import xyz.mederi.tools.sandbox.ExecutionPermissionMode
import xyz.mederi.tools.sandbox.ExecutionPermissions
import xyz.mederi.tools.sandbox.SandboxManager
import xyz.mederi.tools.sandbox.SandboxSettings
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceRecoveryTest {

    private fun fixture(block: suspend (File) -> Unit): Unit = runBlocking {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/workspace-recovery-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun runGit(dir: File, vararg args: String): String {
        val pb = ProcessBuilder("git", *args)
        pb.directory(dir)
        pb.environment()["GIT_AUTHOR_NAME"] = "Test User"
        pb.environment()["GIT_AUTHOR_EMAIL"] = "test@example.com"
        pb.environment()["GIT_COMMITTER_NAME"] = "Test User"
        pb.environment()["GIT_COMMITTER_EMAIL"] = "test@example.com"
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        val exit = p.waitFor()
        if (exit != 0) {
            error("git ${args.joinToString(" ")} failed ($exit): $err")
        }
        return out.trim()
    }

    private fun sampleSession(id: String, projectId: String, wsId: String? = null): Session {
        return Session(
            id = id,
            projectId = projectId,
            title = "Test Session",
            status = SessionStatus.IDLE,
            agentMode = AgentMode.AUTONOMOUS,
            aiModel = null,
            reasoningLevel = null,
            workspaceId = wsId,
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString()
        )
    }

    private fun createManager(
        baseDir: File,
        wsStore: WorkspaceStore? = null,
        sessStore: InMemorySessionStore? = null
    ): Triple<WorkspaceManager, WorkspaceStore, InMemorySessionStore> {
        val sessionStore = sessStore ?: InMemorySessionStore()
        val workspaceStore = wsStore ?: InMemoryWorkspaceStore(sessionStore = sessionStore)
        val historyStore = InMemoryHistoryStore()
        val worktreesRoot = File(baseDir, "worktrees").apply { mkdirs() }.absolutePath
        val snapshotsRoot = File(baseDir, "snapshots").apply { mkdirs() }.absolutePath
        val mgr = WorkspaceManager(
            workspaceStore = workspaceStore,
            sessionStore = sessionStore,
            historyStore = historyStore,
            worktreesRoot = worktreesRoot,
            snapshotsRoot = snapshotsRoot
        )
        return Triple(mgr, workspaceStore, sessionStore)
    }

    /**
     * S2-fix: 为实际执行 setup 脚本的测试装配真实 FULL_ACCESS 权限——
     * setup 不得在 permissions=null 下裸跑，生产路径已强制要求真实权限。
     */
    private suspend fun fullAccessPermissions(tempFolder: File): ExecutionPermissions {
        val settings = SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS)
        val settingsStore = InMemorySettingsStore()
        val manager = SandboxManager(settingsStore = settingsStore)
        manager.updateSettings("proj_test", settings)
        return ExecutionPermissions(
            manager = manager,
            directories = listOf(tempFolder.absolutePath),
            conversationId = "conv_full",
            projectId = "proj_test",
            verifiedWorkspaceRoot = null
        )
    }

    /**
     * RF-04 故障注入 Store（与 WorkspaceRepairIntegrationTest 同一模式）：在指定次数的
     * saveWorkspace 上抛 IOException（默认永不触发）。计数包含所有 saveWorkspace
     * （创建/准备/归档/交接/恢复），测试在关键副作用前 arm 绝对阈值。
     */
    private class FaultInjectingWorkspaceStore(
        private val delegate: WorkspaceStore,
    ) : WorkspaceStore {
        private val saveWorkspaceCount = AtomicInteger(0)
        @Volatile
        var faultOnSaveWorkspaceCount: Int = Int.MAX_VALUE

        val savedWorkspaceCount: Int get() = saveWorkspaceCount.get()

        override suspend fun getWorkspace(id: String) = delegate.getWorkspace(id)
        override suspend fun listWorkspaces(projectId: String) = delegate.listWorkspaces(projectId)
        override suspend fun saveWorkspace(workspace: Workspace) {
            val count = saveWorkspaceCount.incrementAndGet()
            if (count == faultOnSaveWorkspaceCount) {
                throw java.io.IOException("Injected fault: saveWorkspace #$count")
            }
            delegate.saveWorkspace(workspace)
        }
        override suspend fun deleteWorkspace(id: String) = delegate.deleteWorkspace(id)
        override suspend fun getOperation(id: String) = delegate.getOperation(id)
        override suspend fun getOperationByIdempotencyKey(key: String) = delegate.getOperationByIdempotencyKey(key)
        override suspend fun listOperations(projectId: String) = delegate.listOperations(projectId)
        override suspend fun saveOperation(operation: WorkspaceOperation) = delegate.saveOperation(operation)
        override suspend fun deleteOperation(id: String) = delegate.deleteOperation(id)
        override suspend fun commitBinding(
            sessionId: String,
            expectedWorkspaceId: String?,
            expectedGeneration: Long?,
            newWorkspace: Workspace,
            associatedWorktreeId: String?,
            operation: WorkspaceOperation
        ): Boolean = delegate.commitBinding(
            sessionId, expectedWorkspaceId, expectedGeneration, newWorkspace, associatedWorktreeId, operation
        )
    }

    @Test
    fun recoversFromProductionPersistedArchiveOperation(): Unit = fixture { tempFolder ->
        // RF-09：不再手工 new/save 一个生产根本没保存的 RUNNING 操作，而是用真实
        // archiveWorkspace 走完整生产路径，在 remove 之后、ARCHIVED 提交之前注入故障。
        // 重启扫描必须依据生产在副作用前持久化的 RUNNING 日志完成归档。
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Init")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Init")

        val sessionStore = InMemorySessionStore()
        val delegate = InMemoryWorkspaceStore(sessionStore = sessionStore)
        val faultStore = FaultInjectingWorkspaceStore(delegate)
        val (manager1, wsStore, sessStore) = createManager(tempFolder, faultStore, sessionStore)

        // 真实创建 + 准备 managed 工作区（生产路径）
        val created = manager1.createWorkspace(
            projectId = "proj_prod_archive",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD")
        )
        manager1.prepareWorkspace(created.id, srcRepo.absolutePath, "req_prod_prep")
        val ws = wsStore.getWorkspace(created.id)!!
        assertEquals(WorkspaceState.READY, ws.state)
        val checkout = File(ws.checkoutRoot)
        assertTrue(checkout.exists(), "prepared checkout 应存在: $checkout")

        // 注入故障：归档 MANAGED 分支第 2 次 saveWorkspace = ARCHIVED 提交（remove 之后、DB 提交之前）。
        // 第 1 次 saveWorkspace = ARCHIVING 标记；RUNNING 日志/快照/remove 均已在第 2 次之前完成。
        faultStore.faultOnSaveWorkspaceCount = faultStore.savedWorkspaceCount + 2

        val ex = assertFailsWith<java.io.IOException> {
            manager1.archiveWorkspace(ws.id, ArchiveWorkspaceInput(requestId = "prod_archive"))
        }
        assertTrue(ex.message?.contains("saveWorkspace") == true, "故障应来自注入的 saveWorkspace, 实际: ${ex.message}")

        // 1. 生产代码已持久 RUNNING 操作日志（含快照捕获后的 snapshotId），而非测试手工 save
        val runningOp = wsStore.listOperations("proj_prod_archive").find { it.kind == WorkspaceOperationKind.ARCHIVE }
        assertNotNull(runningOp, "生产 archiveWorkspace 必须持久 RUNNING 操作日志")
        assertEquals(WorkspaceOperationState.RUNNING, runningOp.state)
        val plan = runningOp.operationPlan as WorkspaceOperationPlan.ArchivePlan
        assertNotNull(plan.snapshotId, "RUNNING 日志须含 snapshotId")
        assertEquals(plan.snapshotId, runningOp.sourceSnapshotId)

        // 2. 工作区仍为 ARCHIVING（ARCHIVED 数据库提交未发生）
        assertEquals(WorkspaceState.ARCHIVING, wsStore.getWorkspace(ws.id)!!.state)

        // 3. checkout 已被 git worktree remove 移除
        assertFalse(checkout.exists(), "git worktree remove 应已移除 checkout")

        // 重启：同一 Store 新建 Manager（模拟 close/reopen），preview cache 无关
        val (manager2) = createManager(tempFolder, wsStore, sessStore)
        val recovered = manager2.scanAndRecoverPendingOperations("proj_prod_archive")
        assertEquals(1, recovered.size)
        assertEquals(WorkspaceOperationState.SUCCEEDED, recovered[0].state, "恢复应完成归档")
        assertEquals(WorkspaceOperationKind.ARCHIVE, recovered[0].kind)

        // 4. 工作区已 ARCHIVED，snapshotId 落库，generation 递增一次
        val wsFinal = wsStore.getWorkspace(ws.id)!!
        assertEquals(WorkspaceState.ARCHIVED, wsFinal.state)
        assertEquals(plan.snapshotId, wsFinal.lastSnapshotId)
        assertEquals(ws.generation + 1, wsFinal.generation)
    }

    @Test
    fun recoversAfterEveryPersistedMutationBoundary(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Initial")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Initial commit")

        val (manager1, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager1.createWorkspace(
            projectId = "proj_boundary",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_b", "proj_boundary", localWs.id)
        sessStore.insert(session)

        // 在源添加新文件
        File(srcRepo, "new_file.txt").writeText("boundary test")

        val preview = manager1.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))

        // 模拟在 TRANSFERRING 状态中途崩溃。
        // 说明：本测试聚焦“给定一条已持久化的 RUNNING 操作时恢复机制的表现”，操作由测试手工
        // 构造以便精确控制 crash 边界；生产代码会在副作用前持久 RUNNING 操作这一行为由 RF-04
        // 集成测试验证（handoffJournalSurvivesEveryRealMutationBoundary /
        // archiveRecoveryHandlesRemoveBeforeDatabaseCommit），另有
        // recoversFromProductionPersistedArchiveOperation 在此类内走真实生产路径覆盖。
        val targetWs = wsStore.getWorkspace(preview.target.workspace.id)!!
        val crashedOp = WorkspaceOperation(
            id = "op_crashed",
            projectId = "proj_boundary",
            sessionId = session.id,
            workspaceId = targetWs.id,
            kind = WorkspaceOperationKind.HANDOFF,
            state = WorkspaceOperationState.RUNNING,
            phase = WorkspacePhase.MATERIALIZE_TARGET,
            idempotencyKey = "HANDOFF:req_crash",
            requestHash = "hash1",
            sourceWorkspaceId = localWs.id,
            targetWorkspaceId = targetWs.id,
            sourceSnapshotId = null,
            targetSnapshotId = null,
            operationPlan = WorkspaceOperationPlan.HandoffPlan(
                requestHash = "hash1",
                sourceWorkspaceId = localWs.id,
                targetWorkspaceId = targetWs.id,
                targetKind = WorkspaceKind.MANAGED_WORKTREE,
                taskBranchName = preview.taskBranch,
                expectedSourceHeadOid = preview.source.headOid ?: "",
                expectedTargetHeadOid = preview.target.headOid,
                sourceTransferSnapshotId = null,
                sourcePreimageSnapshotId = null,
                targetPreimageSnapshotId = null,
                acceptedTargetSnapshotId = null,
                stopAffectedSessions = false,
                previewToken = preview.token
            ),
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString()
        )
        wsStore.saveOperation(crashedOp)

        // 重启：创建新 Manager
        val (manager2) = createManager(tempFolder, wsStore, sessStore)
        val recovered = manager2.scanAndRecoverPendingOperations("proj_boundary")
        assertEquals(1, recovered.size)
        // 因缺失完整快照且处于未决状态，安全置为 RECOVERY_REQUIRED，不丢失文件
        assertEquals(WorkspaceOperationState.RECOVERY_REQUIRED, recovered[0].state)
        assertTrue(File(srcRepo, "new_file.txt").exists(), "源文件完好保留")
    }

    @Test
    fun commitsBindingOnceWhenBothPostimagesMatch(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Base")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Base")

        val (manager1, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager1.createWorkspace(
            projectId = "proj_postimage",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_pi", "proj_postimage", localWs.id)
        sessStore.insert(session)

        // 正常完成一次 handoff
        val preview = manager1.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val targetWs = wsStore.getWorkspace(preview.target.workspace.id)!!

        val op = manager1.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_pi"))
        assertEquals(WorkspaceOperationState.SUCCEEDED, op.state)

        // 模拟系统在 COMMIT_BINDING 前崩溃（将 op 设为 TRANSFERRING，绑定重置为 localWs）
        val plan = op.operationPlan as WorkspaceOperationPlan.HandoffPlan
        val pendingOp = op.copy(
            state = WorkspaceOperationState.RUNNING,
            phase = WorkspacePhase.COMMIT_BINDING
        )
        wsStore.saveOperation(pendingOp)
        sessStore.updateWorkspaceBinding(session.id, workspaceId = localWs.id, associatedWorktreeId = targetWs.id)

        // 重启并扫描：此时 target 精确等于 acceptedTargetSnapshot 且 source 已 clean
        val (manager2) = createManager(tempFolder, wsStore, sessStore)
        val recovered = manager2.scanAndRecoverPendingOperations("proj_postimage")
        assertEquals(1, recovered.size)
        assertEquals(WorkspaceOperationState.SUCCEEDED, recovered[0].state, "幂等完成 COMMIT_BINDING")

        val sessAfter = sessStore.get(session.id)!!
        assertEquals(targetWs.id, sessAfter.workspaceId, "会话正确绑定到目标工作区")
    }

    @Test
    fun preservesUnknownExternalChangesForExplicitRecovery(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Base")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Base")

        val (manager1, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager1.createWorkspace(
            projectId = "proj_external",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_ext", "proj_external", localWs.id)
        sessStore.insert(session)

        val preview = manager1.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val targetWs = wsStore.getWorkspace(preview.target.workspace.id)!!

        // 构造一个处于 TRANSFERRING 且中途崩溃的操作
        val op = WorkspaceOperation(
            id = "op_unclean",
            projectId = "proj_external",
            sessionId = session.id,
            workspaceId = targetWs.id,
            kind = WorkspaceOperationKind.HANDOFF,
            state = WorkspaceOperationState.RUNNING,
            phase = WorkspacePhase.CLEAN_SOURCE,
            idempotencyKey = "HANDOFF:req_ext",
            requestHash = "hash_ext",
            sourceWorkspaceId = localWs.id,
            targetWorkspaceId = targetWs.id,
            operationPlan = WorkspaceOperationPlan.HandoffPlan(
                requestHash = "hash_ext",
                sourceWorkspaceId = localWs.id,
                targetWorkspaceId = targetWs.id,
                targetKind = WorkspaceKind.MANAGED_WORKTREE,
                taskBranchName = preview.taskBranch,
                expectedSourceHeadOid = preview.source.headOid ?: "",
                expectedTargetHeadOid = preview.target.headOid,
                sourceTransferSnapshotId = null,
                sourcePreimageSnapshotId = null,
                targetPreimageSnapshotId = null,
                acceptedTargetSnapshotId = null,
                stopAffectedSessions = false,
                previewToken = preview.token
            ),
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString()
        )
        wsStore.saveOperation(op)

        // 外部在 source 添加新改动
        File(srcRepo, "external_user_work.txt").writeText("Must not be deleted or rolled back automatically")

        // 重启扫描
        val (manager2) = createManager(tempFolder, wsStore, sessStore)
        val recovered = manager2.scanAndRecoverPendingOperations("proj_external")
        assertEquals(1, recovered.size)
        assertEquals(WorkspaceOperationState.RECOVERY_REQUIRED, recovered[0].state, "检测到未知外部修改，安全停止为 RECOVERY_REQUIRED")
        assertTrue(File(srcRepo, "external_user_work.txt").exists(), "外部用户修改完好保留")
    }

    @Test
    fun cancelsBeforeDuringAndAfterCommitSafely(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Init")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Init")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_cancel",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_cancel", "proj_cancel", localWs.id)
        sessStore.insert(session)

        // 1. 写副作用前（VALIDATE）取消：直接返回 CANCELLED
        val opPre = WorkspaceOperation(
            id = "op_pre",
            projectId = "proj_cancel",
            sessionId = session.id,
            workspaceId = localWs.id,
            kind = WorkspaceOperationKind.HANDOFF,
            state = WorkspaceOperationState.RUNNING,
            phase = WorkspacePhase.VALIDATE,
            idempotencyKey = "HANDOFF:req_pre",
            requestHash = "h1",
            sourceWorkspaceId = localWs.id,
            operationPlan = WorkspaceOperationPlan.HandoffPlan(
                requestHash = "h1",
                sourceWorkspaceId = localWs.id,
                targetWorkspaceId = "tgt_dummy",
                targetKind = WorkspaceKind.MANAGED_WORKTREE,
                taskBranchName = "task/dummy",
                expectedSourceHeadOid = "oid",
                expectedTargetHeadOid = null,
                sourceTransferSnapshotId = null,
                sourcePreimageSnapshotId = null,
                targetPreimageSnapshotId = null,
                acceptedTargetSnapshotId = null,
                stopAffectedSessions = false,
                previewToken = "tok"
            ),
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString()
        )
        wsStore.saveOperation(opPre)

        val cancelledPre = manager.cancelOperation(opPre.id)
        assertEquals(WorkspaceOperationState.CANCELLED, cancelledPre.state)

        // 2. 正常完成一个 Handoff
        val preview = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val completedOp = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_comp"))
        assertEquals(WorkspaceOperationState.SUCCEEDED, completedOp.state)

        // 3. COMMIT_BINDING 之后取消：返回 SUCCEEDED（已太晚）
        val cancelAfter = manager.cancelOperation(completedOp.id)
        assertEquals(WorkspaceOperationState.SUCCEEDED, cancelAfter.state, "已提交完成的操作不可取消")
    }

    @Test
    fun validatesRecoverySnapshotOwnershipAndPreviewVersion(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Init")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Init")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_owner",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_owner", "proj_owner", localWs.id)
        sessStore.insert(session)

        val preview = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_owner"))

        // 模拟该操作处于 RECOVERY_REQUIRED
        val recovOp = op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = 5L)
        wsStore.saveOperation(recovOp)

        val recovPreview = manager.previewRecovery(recovOp.id)
        assertNotNull(recovPreview.token)
        assertEquals(5L, recovPreview.operationVersion)
        assertTrue(recovPreview.eligibleSnapshots.isNotEmpty(), "可恢复快照必须属于该操作")

        // 1. 尝试使用不属于该操作的外来快照执行恢复：必须硬拒绝
        val exForeign = assertFailsWith<MederiWorkspaceException> {
            manager.executeRecovery(
                WorkspaceRecoveryInput(
                    strategy = RecoveryStrategy.RESTORE_TO_NEW_MANAGED,
                    snapshotId = "foreign_snapshot_123",
                    expectedOperationVersion = 5L,
                    previewToken = recovPreview.token,
                    requestId = "req_rf"
                )
            )
        }
        assertEquals(WorkspaceErrorCode.INVALID_REQUEST, exForeign.code)

        // 2. 尝试使用错误的操作版本：必须硬拒绝
        val exVersion = assertFailsWith<MederiWorkspaceException> {
            manager.executeRecovery(
                WorkspaceRecoveryInput(
                    strategy = RecoveryStrategy.RESTORE_TO_NEW_MANAGED,
                    snapshotId = recovPreview.eligibleSnapshots[0].snapshotId,
                    expectedOperationVersion = 1L, // 预期为 5L
                    previewToken = recovPreview.token,
                    requestId = "req_ver"
                )
            )
        }
        assertEquals(WorkspaceErrorCode.STALE_PREVIEW, exVersion.code)
    }

    @Test
    fun restoresIntoNewManagedCheckoutWithoutOverwritingOldOnes(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Base content")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Base commit")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_restore_new",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_rn", "proj_restore_new", localWs.id)
        sessStore.insert(session)

        val preview = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_rn"))
        val managedWs = wsStore.getWorkspace(op.targetWorkspaceId!!)!!

        val recovOp = op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = 2L)
        wsStore.saveOperation(recovOp)

        val recovPreview = manager.previewRecovery(recovOp.id)
        val validSnapshotId = recovPreview.eligibleSnapshots[0].snapshotId

        val recoveryResult = manager.executeRecovery(
            WorkspaceRecoveryInput(
                strategy = RecoveryStrategy.RESTORE_TO_NEW_MANAGED,
                snapshotId = validSnapshotId,
                expectedOperationVersion = 2L,
                previewToken = recovPreview.token,
                requestId = "req_exec_rn"
            )
        )
        assertEquals(WorkspaceOperationState.SUCCEEDED, recoveryResult.state)

        val sessAfter = sessStore.get(session.id)!!
        val newWsId = sessAfter.workspaceId!!
        assertNotEquals(managedWs.id, newWsId, "创建了独立的新 Managed 工作区，而非覆盖旧工作区")

        val newWs = wsStore.getWorkspace(newWsId)!!
        val driver = GitWorkspaceDriver()
        assertEquals(localWs.gitCommonDirectory, driver.getGitCommonDir(newWs.checkoutRoot))
        assertTrue(driver.runGit(srcRepo.path, listOf("worktree", "list", "--porcelain")).stdoutString.contains(newWs.checkoutRoot))
        assertTrue(File(newWs.checkoutRoot, "README.md").exists(), "快照内容完整还原到新目录")
        assertTrue(File(managedWs.checkoutRoot).exists(), "旧 Managed 副本完整保留未被覆盖")
    }

    @Test
    fun keepsCurrentOnlyAfterIdentityAndSnapshotVerification(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Base content")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Base commit")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_keep",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_keep", "proj_keep", localWs.id)
        sessStore.insert(session)

        val preview = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_keep"))

        val recovOp = op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = 3L)
        wsStore.saveOperation(recovOp)

        val recovPreview = manager.previewRecovery(recovOp.id)

        // 1. 指定不属于本操作的 keepWorkspaceId：拒绝
        val exForeign = assertFailsWith<MederiWorkspaceException> {
            manager.executeRecovery(
                WorkspaceRecoveryInput(
                    strategy = RecoveryStrategy.KEEP_VERIFIED_CURRENT,
                    keepWorkspaceId = "ws_foreign_random",
                    expectedOperationVersion = 3L,
                    previewToken = recovPreview.token,
                    requestId = "req_keep_bad"
                )
            )
        }
        assertEquals(WorkspaceErrorCode.INVALID_REQUEST, exForeign.code)

        // 2. 指定合法的 targetWorkspaceId：验证通过并捕获基准快照
        val res = manager.executeRecovery(
            WorkspaceRecoveryInput(
                strategy = RecoveryStrategy.KEEP_VERIFIED_CURRENT,
                keepWorkspaceId = op.targetWorkspaceId,
                expectedOperationVersion = 3L,
                previewToken = recovPreview.token,
                requestId = "req_keep_ok"
            )
        )
        assertEquals(WorkspaceOperationState.SUCCEEDED, res.state)
        val keptWs = wsStore.getWorkspace(op.targetWorkspaceId!!)!!
        assertEquals(WorkspaceState.READY, keptWs.state, "工作区状态被置为 READY")
    }

    @Test
    fun retriesPreparationAndSetupWithoutResendingMessage(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Init")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Init")

        // 准备环境定义
        val envDir = File(srcRepo, ".mederi/environments").apply { mkdirs() }
        val envJson = """
            {
              "id": "retry_env",
              "name": "Retry Env",
              "version": 1,
              "setup": { "default": "echo RETRY_SUCCESS > retry.log" }
            }
        """.trimIndent()
        File(envDir, "retry_env.json").writeText(envJson)
        runGit(srcRepo, "add", ".")
        runGit(srcRepo, "commit", "-m", "Add retry env")

        val (manager, wsStore, sessStore) = createManager(tempFolder)
        val permissions = fullAccessPermissions(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_retry",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL, environmentId = "retry_env")
        )
        val session = sampleSession("sess_retry", "proj_retry", localWs.id)
        sessStore.insert(session)

        // 1. 测试 retrySetup
        val setupOp = manager.retrySetup(localWs.id, "retry_env", "req_retry_setup", permissions = permissions)
        assertEquals(WorkspaceOperationState.SUCCEEDED, setupOp.state)
        assertTrue(File(srcRepo, "retry.log").exists(), "Setup 成功执行")

        // 2. 测试 retryPreparation
        val prepWs = localWs.copy(state = WorkspaceState.REQUESTED)
        wsStore.saveWorkspace(prepWs)

        val retriedOp = manager.retryPreparation(prepWs.id, srcRepo.absolutePath, "req_retry_prep", permissions = permissions)
        assertEquals(WorkspaceOperationState.SUCCEEDED, retriedOp.state, "准备重试成功收敛至 SUCCEEDED")
    }
}
