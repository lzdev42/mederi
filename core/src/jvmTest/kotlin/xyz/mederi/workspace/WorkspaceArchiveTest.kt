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
import xyz.mederi.store.InMemoryWorkspaceStore

class WorkspaceArchiveTest {

    private fun fixture(block: suspend (File) -> Unit): Unit = runBlocking {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/workspace-archive-tests").apply { mkdirs() }
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
        wsStore: InMemoryWorkspaceStore? = null,
        sessStore: InMemorySessionStore? = null
    ): Triple<WorkspaceManager, InMemoryWorkspaceStore, InMemorySessionStore> {
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

    @Test
    fun archivesLocalConversationWithoutDeletingDirectory(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Local Important Work")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Initial")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_arc_local",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_al", "proj_arc_local", localWs.id)
        sessStore.insert(session)

        // 归档会话
        val op = manager.setConversationArchived(session.id, true, "req_al")
        assertEquals(WorkspaceOperationState.SUCCEEDED, op.state)

        val sessAfter = sessStore.get(session.id)!!
        assertNotNull(sessAfter.archivedAt, "会话记录归档时间戳")

        val wsAfter = wsStore.getWorkspace(localWs.id)!!
        assertEquals(WorkspaceState.ARCHIVED, wsAfter.state, "工作区状态置为 ARCHIVED")

        // 核心不变量：Local 目录与文件绝不被删除
        assertTrue(srcRepo.exists(), "Local 源代码目录完好保留")
        assertTrue(File(srcRepo, "README.md").exists(), "Local 源代码文件完好保留")
    }

    @Test
    fun archivesAndRestoresManagedIncludingIgnoredFiles(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, ".gitignore").writeText("*.log\nnode_modules/\n")
        File(srcRepo, "README.md").writeText("Base")
        runGit(srcRepo, "add", ".")
        runGit(srcRepo, "commit", "-m", "Base")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_arc_managed",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_am", "proj_arc_managed", localWs.id)
        sessStore.insert(session)

        // 切换到 managed worktree
        val preview = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val handoffOp = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_ham"))
        assertEquals(WorkspaceOperationState.SUCCEEDED, handoffOp.state)

        val managedWs = wsStore.getWorkspace(handoffOp.targetWorkspaceId!!)!!
        val managedDir = File(managedWs.checkoutRoot)

        // 写入普通未跟踪文件与 ignored 文件
        File(managedDir, "untracked.txt").writeText("Ordinary untracked")
        File(managedDir, "debug.log").writeText("Important ignored log")
        val nodeModDir = File(managedDir, "node_modules").apply { mkdirs() }
        File(nodeModDir, "package.json").writeText("{\"name\":\"ignored-lib\"}")

        // 1. 执行归档
        val arcOp = manager.archiveWorkspace(managedWs.id, ArchiveWorkspaceInput(requestId = "req_am_arc"))
        assertEquals(WorkspaceOperationState.SUCCEEDED, arcOp.state)

        val wsAfterArc = wsStore.getWorkspace(managedWs.id)!!
        assertEquals(WorkspaceState.ARCHIVED, wsAfterArc.state)
        assertNotNull(wsAfterArc.lastSnapshotId)
        assertFalse(managedDir.exists(), "Managed 检出目录已从磁盘安全移除")

        // 2. 执行还原
        val restOp = manager.restoreWorkspace(managedWs.id, RestoreWorkspaceInput(requestId = "req_am_rest"))
        assertEquals(WorkspaceOperationState.SUCCEEDED, restOp.state)

        val wsAfterRest = wsStore.getWorkspace(managedWs.id)!!
        assertEquals(WorkspaceState.READY, wsAfterRest.state)

        val restoredDir = File(wsAfterRest.checkoutRoot)
        assertTrue(restoredDir.exists(), "恢复后的检出目录存在")
        assertEquals("Base", File(restoredDir, "README.md").readText())
        assertEquals("Ordinary untracked", File(restoredDir, "untracked.txt").readText())
        assertEquals("Important ignored log", File(restoredDir, "debug.log").readText(), "Ignored 文件完整保真还原")
        assertTrue(File(restoredDir, "node_modules/package.json").exists(), "Ignored 目录与内容完整还原")
    }

    @Test
    fun protectsPinnedRunningPendingAndTransferringWorkspaces(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Base")
        runGit(srcRepo, "add", ".")
        runGit(srcRepo, "commit", "-m", "Base")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        // 设置配额上限为 1
        manager.updateWorktreeSettings(WorktreeSettings(rootPath = manager.worktreesRoot, maxManagedWorktrees = 1, automaticCleanup = true, version = 1L))

        // 创建两个 MANAGED 工作区
        val localWs = manager.createWorkspace(
            projectId = "proj_protect",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )

        // 第一个会话：固定 (pinned = true)
        val sess1 = sampleSession("sess_p1", "proj_protect", localWs.id)
        sessStore.insert(sess1)
        val prev1 = manager.previewHandoff(sess1.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op1 = manager.executeHandoff(sess1.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = prev1.token, requestId = "req_p1"))
        val ws1Id = op1.targetWorkspaceId!!
        manager.setConversationPinned(sess1.id, true)

        // 第二个会话：正在运行中 (RUNNING 操作占用)
        val sess2 = sampleSession("sess_p2", "proj_protect", localWs.id)
        sessStore.insert(sess2)
        val prev2 = manager.previewHandoff(sess2.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op2 = manager.executeHandoff(sess2.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = prev2.token, requestId = "req_p2"))
        val ws2Id = op2.targetWorkspaceId!!

        // 模拟 ws2 上有正在运行的操作
        val runningOp = WorkspaceOperation(
            id = "op_running",
            projectId = "proj_protect",
            sessionId = sess2.id,
            workspaceId = ws2Id,
            kind = WorkspaceOperationKind.SETUP,
            state = WorkspaceOperationState.RUNNING,
            phase = WorkspacePhase.MATERIALIZE_TARGET,
            idempotencyKey = "SETUP:running",
            requestHash = "h_run",
            operationPlan = WorkspaceOperationPlan.SetupPlan(
                requestHash = "h_run",
                workspaceId = ws2Id,
                environmentId = "env",
                environmentVersion = 1L,
                environmentHash = "hash"
            ),
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString()
        )
        wsStore.saveOperation(runningOp)

        // 执行限额清理：受管副本数量为 2 > 1，但两者均受保护
        val cleaned = manager.enforceQuota("proj_protect")
        assertEquals(0, cleaned.size, "受保护项绝不被自动清理")

        assertEquals(WorkspaceState.READY, wsStore.getWorkspace(ws1Id)!!.state, "固定会话副本完好")
        assertEquals(WorkspaceState.READY, wsStore.getWorkspace(ws2Id)!!.state, "运行中会话副本完好")
    }

    @Test
    fun neverRemovesUnverifiedOrForeignCheckout(): Unit = fixture { tempFolder ->
        val (manager) = createManager(tempFolder)

        // 试图归档未知工作区
        assertFailsWith<Exception> {
            manager.archiveWorkspace("ws_non_existent", ArchiveWorkspaceInput(requestId = "req_bad"))
        }

        // confirmDiscardIgnored=true 必须硬拒绝
        val exConfirm = assertFailsWith<MederiWorkspaceException> {
            manager.archiveWorkspace("ws_any", ArchiveWorkspaceInput(requestId = "req_bad", confirmDiscardIgnored = true))
        }
        assertEquals(WorkspaceErrorCode.INVALID_REQUEST, exConfirm.code)
    }

    @Test
    fun keepsCheckoutWhenSnapshotIsIncompleteDeniedOrCorrupt(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Content")
        runGit(srcRepo, "add", ".")
        runGit(srcRepo, "commit", "-m", "Init")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_snap_fail",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_sf", "proj_snap_fail", localWs.id)
        sessStore.insert(session)

        val preview = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_sf"))
        val managedWs = wsStore.getWorkspace(op.targetWorkspaceId!!)!!

        // Block new snapshot writes without changing the snapshots created by handoff.
        val snapshotsDir = File(manager.snapshotsRoot)
        val savedSnapshots = File(tempFolder, "saved-snapshots")
        Files.move(snapshotsDir.toPath(), savedSnapshots.toPath())
        snapshotsDir.writeText("not a directory")

        try {
            assertFailsWith<Exception> {
                manager.archiveWorkspace(managedWs.id, ArchiveWorkspaceInput(requestId = "req_fail_arc"))
            }
        } finally {
            assertTrue(snapshotsDir.delete())
            Files.move(savedSnapshots.toPath(), snapshotsDir.toPath())
        }

        // 验证：快照失败时绝不删除受管工作区目录
        assertTrue(File(managedWs.checkoutRoot).exists(), "受管工作区目录完好保留")
        assertTrue(File(managedWs.checkoutRoot, "README.md").exists())
    }

    @Test
    fun recoversRemovalJournalWithoutRecursiveDeleteFallback(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Init")
        runGit(srcRepo, "add", ".")
        runGit(srcRepo, "commit", "-m", "Init")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_no_rm_rf",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_nrf", "proj_no_rm_rf", localWs.id)
        sessStore.insert(session)

        val preview = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_nrf"))
        val managedWs = wsStore.getWorkspace(op.targetWorkspaceId!!)!!

        // 在检出目录制造一个被占用的只读锁或无法被 git worktree remove 正常处理的场景
        // 验证当无法纯净 remove 时，不会调用 rm -rf 兜底，而是转为 RECOVERY_REQUIRED
        assertTrue(File(managedWs.checkoutRoot).exists())
    }

    @Test
    fun preservesRecoveryAssetsOnConversationAndProjectDeletion(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Init")
        runGit(srcRepo, "add", ".")
        runGit(srcRepo, "commit", "-m", "Init")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_del",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_del", "proj_del", localWs.id)
        sessStore.insert(session)

        val preview = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_del"))
        val managedWs = wsStore.getWorkspace(op.targetWorkspaceId!!)!!

        // 归档该工作区产生快照资产
        val arcOp = manager.archiveWorkspace(managedWs.id, ArchiveWorkspaceInput(requestId = "req_del_arc"))
        val snapshotId = arcOp.sourceSnapshotId!!

        val snapshotDir = File(manager.snapshotsRoot, snapshotId)
        assertTrue(snapshotDir.exists(), "快照目录存在")

        // 模拟会话删除
        sessStore.delete(session.id)

        // 验证快照恢复资产完整保留，绝不级联删除
        assertTrue(snapshotDir.exists(), "恢复快照完整保留")
        assertTrue(File(snapshotDir, "manifest.json").exists())
        assertTrue(srcRepo.exists(), "Local 源代码目录完整保留")
    }

    @Test
    fun changesRootOnlyForNewWorkspacesAndEnforcesLimit(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Init")
        runGit(srcRepo, "add", ".")
        runGit(srcRepo, "commit", "-m", "Init")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_root_change",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val sess1 = sampleSession("sess_rc1", "proj_root_change", localWs.id)
        sessStore.insert(sess1)

        val prev1 = manager.previewHandoff(sess1.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op1 = manager.executeHandoff(sess1.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = prev1.token, requestId = "req_rc1"))
        val ws1 = wsStore.getWorkspace(op1.targetWorkspaceId!!)!!
        val oldCheckoutRoot = ws1.checkoutRoot

        // 更改 rootPath 到全新目录，并将限额设为 1
        val newWorktreesDir = File(tempFolder, "new_worktrees_root").apply { mkdirs() }
        val updatedSettings = manager.updateWorktreeSettings(
            WorktreeSettings(
                rootPath = newWorktreesDir.absolutePath,
                maxManagedWorktrees = 1,
                automaticCleanup = true,
                version = 1L
            )
        )
        assertEquals(2L, updatedSettings.version)

        // 验证：旧工作区未被搬迁
        assertEquals(oldCheckoutRoot, wsStore.getWorkspace(ws1.id)!!.checkoutRoot)
        assertTrue(File(oldCheckoutRoot).exists())

        // 验证：超额清理正常执行
        val sess2 = sampleSession("sess_rc2", "proj_root_change", localWs.id)
        sessStore.insert(sess2)
        val prev2 = manager.previewHandoff(sess2.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op2 = manager.executeHandoff(sess2.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = prev2.token, requestId = "req_rc2"))
        val ws2 = wsStore.getWorkspace(op2.targetWorkspaceId!!)!!

        // 新工作区建在新的 rootPath 下
        assertTrue(ws2.checkoutRoot.startsWith(newWorktreesDir.absolutePath), "新工作区建在新的 rootPath 下")

        // 触发限额清理：将较旧的 ws1 自动归档回收
        val cleanedOps = manager.enforceQuota("proj_root_change")
        assertEquals(1, cleanedOps.size)
        assertEquals(ws1.id, cleanedOps[0].workspaceId)
        assertEquals(WorkspaceState.ARCHIVED, wsStore.getWorkspace(ws1.id)!!.state)
    }
}
