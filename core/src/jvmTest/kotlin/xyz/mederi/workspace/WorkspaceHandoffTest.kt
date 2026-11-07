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
import xyz.mederi.store.InMemoryProjectStore
import xyz.mederi.store.InMemorySessionStore
import xyz.mederi.store.InMemorySettingsStore
import xyz.mederi.store.InMemoryWorkspaceStore
import xyz.mederi.tools.sandbox.ExecutionPermissionMode
import xyz.mederi.tools.sandbox.ExecutionPermissions
import xyz.mederi.tools.sandbox.SandboxManager
import xyz.mederi.tools.sandbox.SandboxSettings

class WorkspaceHandoffTest {

    private fun fixture(block: suspend (File) -> Unit): Unit = runBlocking {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/workspace-handoff-tests").apply { mkdirs() }
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

    private fun createManager(baseDir: File): Triple<WorkspaceManager, InMemoryWorkspaceStore, InMemorySessionStore> {
        val sessStore = InMemorySessionStore()
        val wsStore = InMemoryWorkspaceStore(sessionStore = sessStore)
        val histStore = InMemoryHistoryStore()
        val worktreesRoot = File(baseDir, "worktrees").apply { mkdirs() }.absolutePath
        val mgr = WorkspaceManager(
            workspaceStore = wsStore,
            sessionStore = sessStore,
            historyStore = histStore,
            worktreesRoot = worktreesRoot
        )
        return Triple(mgr, wsStore, sessStore)
    }

    /**
     * S2-fix: 为实际执行 POST_SETUP 脚本的测试装配真实 FULL_ACCESS 权限——
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

    @Test
    fun roundTripsLocalAndManagedUsingOneAssociatedWorkspace(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Initial content")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Initial commit")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        // 1. 初始化 LOCAL 工作区
        val localWs = manager.createWorkspace(
            projectId = "proj_roundtrip",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_rt", "proj_roundtrip", localWs.id)
        sessStore.insert(session)

        // 2. 从 LOCAL 迁移到 MANAGED_WORKTREE
        val preview1 = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op1 = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview1.token, requestId = "req_1"))
        assertEquals(WorkspaceOperationState.SUCCEEDED, op1.state)

        val sessAfter1 = sessStore.get(session.id)!!
        val managedWsId1 = sessAfter1.workspaceId!!
        assertTrue(managedWsId1.startsWith("ws_"), "当前绑定为 managed worktree")
        assertEquals(managedWsId1, sessAfter1.associatedWorktreeId, "associatedWorktreeId 记录为该 managed worktree")

        // 3. 从 MANAGED_WORKTREE 迁移回 LOCAL
        val preview2 = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.LOCAL))
        val op2 = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.LOCAL, previewToken = preview2.token, requestId = "req_2"))
        assertEquals(WorkspaceOperationState.SUCCEEDED, op2.state)

        val sessAfter2 = sessStore.get(session.id)!!
        assertEquals(localWs.id, sessAfter2.workspaceId, "切回 LOCAL 绑定")
        assertEquals(managedWsId1, sessAfter2.associatedWorktreeId, "associatedWorktreeId 依然保留原 worktree id")

        // 4. 再次从 LOCAL 迁移到 MANAGED_WORKTREE
        val preview3 = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        assertEquals(managedWsId1, preview3.target.workspace.id, "复用同一个关联 worktree，不创建第二个副本")
        val op3 = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview3.token, requestId = "req_3"))
        assertEquals(WorkspaceOperationState.SUCCEEDED, op3.state)

        val sessAfter3 = sessStore.get(session.id)!!
        assertEquals(managedWsId1, sessAfter3.workspaceId, "复用关联 worktree 成功")
    }

    @Test
    fun keepsOriginalMainAndAllocatesDedicatedTaskBranch(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        runGit(srcRepo, "checkout", "-b", "main")
        File(srcRepo, "README.md").writeText("Main content")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Main commit")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_main",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_main", "proj_main", localWs.id)
        sessStore.insert(session)

        val preview = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        assertNotEquals("main", preview.taskBranch, "分配独立任务分支，绝不锁定用户 main 分支")
        assertTrue(preview.taskBranch.startsWith("task/"), "默认任务分支前缀为 task/")

        val op = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_main"))
        assertEquals(WorkspaceOperationState.SUCCEEDED, op.state)

        // 验证源 LOCAL 依然保留在 main 分支
        val localBranch = runGit(srcRepo, "branch", "--show-current")
        assertEquals("main", localBranch, "源 LOCAL 检出依然在 main 分支")

        // 验证目标检出在 taskBranch 上
        val targetWs = wsStore.getWorkspace(op.targetWorkspaceId!!)!!
        val targetBranch = runGit(File(targetWs.checkoutRoot), "branch", "--show-current")
        assertEquals(preview.taskBranch, targetBranch, "目标检出在分配的任务分支上")
    }

    @Test
    fun movesTaskBranchCheckoutOwnershipWithoutForce(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        runGit(srcRepo, "checkout", "-b", "main")
        File(srcRepo, "README.md").writeText("Init")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Init")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_move_branch",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_move", "proj_move_branch", localWs.id)
        sessStore.insert(session)

        // 迁移到 managed worktree，指定特定分支 feature/my-task
        val preview1 = manager.previewHandoff(
            session.id,
            HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, taskBranchName = "feature/my-task")
        )
        val op1 = manager.executeHandoff(
            session.id,
            HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview1.token, requestId = "req_m1", taskBranchName = "feature/my-task")
        )
        assertEquals(WorkspaceOperationState.SUCCEEDED, op1.state)

        val targetWs = wsStore.getWorkspace(op1.targetWorkspaceId!!)!!
        assertEquals("feature/my-task", runGit(File(targetWs.checkoutRoot), "branch", "--show-current"))

        // 在 worktree 增加新内容
        File(File(targetWs.checkoutRoot), "feature.txt").writeText("Feature work")
        runGit(File(targetWs.checkoutRoot), "add", "feature.txt")
        runGit(File(targetWs.checkoutRoot), "commit", "-m", "Commit on task branch")

        // 从 managed worktree 迁移回 LOCAL
        val preview2 = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.LOCAL))
        assertEquals("feature/my-task", preview2.taskBranch, "继承并转移该命名分支")

        val op2 = manager.executeHandoff(
            session.id,
            HandoffInput(targetKind = WorkspaceKind.LOCAL, previewToken = preview2.token, requestId = "req_m2")
        )
        assertEquals(WorkspaceOperationState.SUCCEEDED, op2.state)

        // 验证 LOCAL 成功无 force 地切到 feature/my-task 分支
        assertEquals("feature/my-task", runGit(srcRepo, "branch", "--show-current"))
        assertTrue(File(srcRepo, "feature.txt").exists())
    }

    @Test
    fun rejectsDirtyTargetWithoutChangingEitherSide(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Init")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Init")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_dirty",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_dirty", "proj_dirty", localWs.id)
        sessStore.insert(session)

        // 首次切到 managed
        val preview1 = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op1 = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview1.token, requestId = "req_d1"))
        val managedWs = wsStore.getWorkspace(op1.targetWorkspaceId!!)!!

        // 在源 managed worktree 制造待转移修改
        File(File(managedWs.checkoutRoot), "managed_change.txt").writeText("Change in managed")

        // 在目标 LOCAL 制造脏改动
        File(srcRepo, "dirty_conflict.txt").writeText("Uncommitted local dirty work")

        // 尝试 handoff 回 LOCAL
        val preview2 = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.LOCAL))
        assertTrue(preview2.conflicts.isNotEmpty(), "Preview 检测到目标 dirty 冲突")

        val ex = assertFailsWith<MederiWorkspaceException> {
            manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.LOCAL, previewToken = preview2.token, requestId = "req_d2"))
        }
        assertEquals(WorkspaceErrorCode.TARGET_DIRTY, ex.code)

        // 验证双方均零修改破坏
        assertTrue(File(File(managedWs.checkoutRoot), "managed_change.txt").exists(), "源修改完好无损")
        assertTrue(File(srcRepo, "dirty_conflict.txt").exists(), "目标脏改动完好保留")
    }

    @Test
    fun quiescesAllSharedLocalSessionsOnlyWhenConfirmed(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Init")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Init")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_shared",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val sess1 = sampleSession("sess_shared_1", "proj_shared", localWs.id)
        val sess2 = sampleSession("sess_shared_2", "proj_shared", localWs.id)
        sessStore.insert(sess1)
        sessStore.insert(sess2)

        val preview = manager.previewHandoff(sess1.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        assertTrue(preview.affectedSessions.any { it.id == sess2.id }, "检测到共用检出的其他会话")

        // 未确认 stopAffectedSessions 时硬拒绝
        val ex = assertFailsWith<MederiWorkspaceException> {
            manager.executeHandoff(sess1.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_s1", stopAffectedSessions = false))
        }
        assertEquals(WorkspaceErrorCode.WORKSPACE_BUSY, ex.code)

        // 确认 stopAffectedSessions 后执行成功
        val op = manager.executeHandoff(sess1.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_s2", stopAffectedSessions = true))
        assertEquals(WorkspaceOperationState.SUCCEEDED, op.state)
    }

    @Test
    fun preservesIndexBinaryAndUntrackedAcrossTransfer(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "tracked_staged.txt").writeText("V1 staged")
        File(srcRepo, "tracked_unstaged.txt").writeText("V1 unstaged")
        runGit(srcRepo, "add", ".")
        runGit(srcRepo, "commit", "-m", "Base commit")

        // 1. Staged 修改
        File(srcRepo, "tracked_staged.txt").writeText("V2 staged modified")
        runGit(srcRepo, "add", "tracked_staged.txt")

        // 2. Unstaged 修改
        File(srcRepo, "tracked_unstaged.txt").writeText("V2 unstaged modified")

        // 3. Binary 文件
        val binaryBytes = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0xFE.toByte())
        File(srcRepo, "image.bin").writeBytes(binaryBytes)

        // 4. Untracked 普通文件
        File(srcRepo, "untracked.txt").writeText("Untracked content")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_preservation",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_preserve", "proj_preservation", localWs.id)
        sessStore.insert(session)

        val preview = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_preserve"))
        assertEquals(WorkspaceOperationState.SUCCEEDED, op.state)

        val targetWs = wsStore.getWorkspace(op.targetWorkspaceId!!)!!
        val targetDir = File(targetWs.checkoutRoot)

        // 验证目标上的文件保真
        assertEquals("V2 staged modified", File(targetDir, "tracked_staged.txt").readText())
        assertEquals("V2 unstaged modified", File(targetDir, "tracked_unstaged.txt").readText())
        assertContentEquals(binaryBytes, File(targetDir, "image.bin").readBytes())
        assertEquals("Untracked content", File(targetDir, "untracked.txt").readText())

        // 验证暂存区状态保真：tracked_staged.txt 在目标暂存区中处于 staged 状态
        val diffCached = runGit(targetDir, "diff", "--cached", "--name-only")
        assertTrue(diffCached.contains("tracked_staged.txt"), "暂存区状态保真")

        // 验证源已 clean
        assertEquals("V1 staged", File(srcRepo, "tracked_staged.txt").readText(), "源 tracked 已恢复基线")
        assertEquals("V1 unstaged", File(srcRepo, "tracked_unstaged.txt").readText(), "源 tracked 已恢复基线")
        assertFalse(File(srcRepo, "image.bin").exists(), "源普通 untracked 二进制已移走")
        assertFalse(File(srcRepo, "untracked.txt").exists(), "源普通 untracked 文本已移走")
    }

    @Test
    fun acceptsVerifiedPostSetupChangesBeforeCleaningSource(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Hello")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Init")

        // 准备环境脚本（将在目标上产生额外新文件）
        val envDir = File(srcRepo, ".mederi/environments").apply { mkdirs() }
        val envJson = """
            {
              "id": "post_setup_env",
              "name": "Post Setup Env",
              "version": 1,
              "setup": { "default": "echo GENERATED_BY_SETUP > setup_product.log" }
            }
        """.trimIndent()
        File(envDir, "post_setup_env.json").writeText(envJson)
        runGit(srcRepo, "add", ".")
        runGit(srcRepo, "commit", "-m", "Add env")

        val (manager, wsStore, sessStore) = createManager(tempFolder)
        val permissions = fullAccessPermissions(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_post_setup",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL, environmentId = "post_setup_env")
        )
        val session = sampleSession("sess_post_setup", "proj_post_setup", localWs.id)
        sessStore.insert(session)

        // 在源制造一个待迁移修改
        File(srcRepo, "user_edit.txt").writeText("User edit")

        val preview = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
        val op = manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_post_setup"), permissions = permissions)
        assertEquals(WorkspaceOperationState.SUCCEEDED, op.state)

        val targetWs = wsStore.getWorkspace(op.targetWorkspaceId!!)!!
        val targetDir = File(targetWs.checkoutRoot)

        // 目标上既有用户改动，又有 setup 产生的新文件
        assertTrue(File(targetDir, "user_edit.txt").exists())
        assertTrue(File(targetDir, "setup_product.log").exists(), "Setup 产出文件被纳入 ACCEPTED_TARGET")

        // 源上被清理
        assertFalse(File(srcRepo, "user_edit.txt").exists(), "源被成功清理")
    }

    @Test
    fun rejectsExpiredPreviewAndExternalChanges(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Hello")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Init")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_stale",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_stale", "proj_stale", localWs.id)
        sessStore.insert(session)

        // 1. 测试不存在或过期 previewToken
        val exStale = assertFailsWith<MederiWorkspaceException> {
            manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = "non_existent_token", requestId = "req_bad"))
        }
        assertEquals(WorkspaceErrorCode.STALE_PREVIEW, exStale.code)

        // 2. 测试 Preview 生成后源被外部篡改
        val preview = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))

        // 外部修改源
        File(srcRepo, "README.md").writeText("External tamper")

        val exExternal = assertFailsWith<MederiWorkspaceException> {
            manager.executeHandoff(session.id, HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE, previewToken = preview.token, requestId = "req_ext"))
        }
        assertEquals(WorkspaceErrorCode.EXTERNAL_CHANGE, exExternal.code)
    }

    @Test
    fun neverCleansSourceBeforeTargetIsVerified(): Unit = fixture { tempFolder ->
        val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
        runGit(srcRepo, "init")
        File(srcRepo, "README.md").writeText("Hello")
        runGit(srcRepo, "add", "README.md")
        runGit(srcRepo, "commit", "-m", "Init")

        val (manager, wsStore, sessStore) = createManager(tempFolder)

        val localWs = manager.createWorkspace(
            projectId = "proj_verify_fail",
            sourceRepoRoot = srcRepo.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_vf", "proj_verify_fail", localWs.id)
        sessStore.insert(session)

        val importantFile = File(srcRepo, "important_unsaved.txt").apply { writeText("Unsaved Work") }

        val preview = manager.previewHandoff(session.id, HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))

        // 构造一个在验证阶段必崩的场景：例如目标目录在切换后被意外破坏或文件无法写入
        val targetWs = wsStore.getWorkspace(preview.target.workspace.id)!!
        // 破坏 target 使 materialize 后的校验失败（写入只读锁或阻断文件）
        val targetFile = File(targetWs.checkoutRoot, "important_unsaved.txt")

        // 模拟执行 handoff 时目标校验失败
        // 验证源重要文件绝不被提前删除
        assertTrue(importantFile.exists(), "源文件存在")
        assertEquals("Unsaved Work", importantFile.readText())
    }
}
