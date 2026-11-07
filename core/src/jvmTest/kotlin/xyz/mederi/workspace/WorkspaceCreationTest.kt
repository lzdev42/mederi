package xyz.mederi.workspace

import kotlinx.coroutines.runBlocking
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemorySessionStore
import xyz.mederi.store.InMemorySettingsStore
import xyz.mederi.store.InMemoryWorkspaceStore
import xyz.mederi.tools.sandbox.ExecutionPermissionMode
import xyz.mederi.tools.sandbox.ExecutionPermissions
import xyz.mederi.tools.sandbox.SandboxManager
import xyz.mederi.tools.sandbox.SandboxSettings
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class WorkspaceCreationTest {

    private suspend fun fixture(block: suspend (File) -> Unit) {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/workspace-creation-tests").apply { mkdirs() }
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

    private fun sampleSession(id: String, projectId: String): Session {
        return Session(
            id = id,
            projectId = projectId,
            title = "Test Session",
            status = SessionStatus.IDLE,
            agentMode = AgentMode.AUTONOMOUS,
            aiModel = null,
            reasoningLevel = null,
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString()
        )
    }

    @Test
    fun startsDetachedAtSelectedCommitWithoutChangingSource(): Unit = runBlocking {
        fixture { tempFolder ->
            val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
            runGit(srcRepo, "init")
            File(srcRepo, "README.md").writeText("Init")
            runGit(srcRepo, "add", "README.md")
            runGit(srcRepo, "commit", "-m", "Initial commit")
            val headOid = runGit(srcRepo, "rev-parse", "HEAD")

            val (manager, wsStore, sessStore) = createManager(tempFolder)

            val session = sampleSession("sess_1", "proj_1")
            sessStore.insert(session)

            val ws = manager.createWorkspace(
                projectId = "proj_1",
                sourceRepoRoot = srcRepo.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD"),
                sessionId = session.id
            )
            assertEquals(WorkspaceState.REQUESTED, ws.state)

            // 准备工作区
            val op = manager.prepareWorkspace(ws.id, srcRepo.absolutePath, "req_create")
            assertEquals(WorkspaceOperationState.SUCCEEDED, op.state)

            // 1. 验证目标工作区检出在指定的 baseOid，处于 Detached HEAD 状态
            val targetDir = File(ws.checkoutRoot)
            val targetHead = runGit(targetDir, "rev-parse", "HEAD")
            assertEquals(headOid, targetHead)

            val pbSymbolic = ProcessBuilder("git", "symbolic-ref", "-q", "HEAD").directory(targetDir).start()
            val symExit = pbSymbolic.waitFor()
            assertNotEquals(0, symExit, "目标工作区必须处于 Detached HEAD 状态")

            // 2. 验证源仓库依然在原分支，完全未变动
            val srcBranch = runGit(srcRepo, "rev-parse", "--abbrev-ref", "HEAD")
            assertTrue(srcBranch.isNotEmpty())
            val srcHead = runGit(srcRepo, "rev-parse", "HEAD")
            assertEquals(headOid, srcHead)
        }
    }

    @Test
    fun carriesStagedUnstagedAndUntrackedOnlyWhenSelected(): Unit = runBlocking {
        fixture { tempFolder ->
            val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
            runGit(srcRepo, "init")
            File(srcRepo, "committed.txt").writeText("initial committed")
            runGit(srcRepo, "add", "committed.txt")
            runGit(srcRepo, "commit", "-m", "Init")

            // 产生暂存、未暂存、未跟踪改动
            File(srcRepo, "committed.txt").writeText("unstaged modification")
            File(srcRepo, "staged.txt").writeText("staged content")
            runGit(srcRepo, "add", "staged.txt")
            File(srcRepo, "untracked.txt").writeText("untracked content")

            val (manager, wsStore, sessStore) = createManager(tempFolder)

            // Case A: carryLocalChanges = false
            val wsNoCarry = manager.createWorkspace(
                projectId = "proj_1",
                sourceRepoRoot = srcRepo.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, carryLocalChanges = false)
            )
            manager.prepareWorkspace(wsNoCarry.id, srcRepo.absolutePath, "req_no_carry")
            val tgtNoCarry = File(wsNoCarry.checkoutRoot)
            assertEquals("initial committed", File(tgtNoCarry, "committed.txt").readText())
            assertFalse(File(tgtNoCarry, "staged.txt").exists(), "未选择 carry 时不转移暂存文件")
            assertFalse(File(tgtNoCarry, "untracked.txt").exists(), "未选择 carry 时不转移未跟踪文件")

            // Case B: carryLocalChanges = true
            val wsCarry = manager.createWorkspace(
                projectId = "proj_1",
                sourceRepoRoot = srcRepo.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, carryLocalChanges = true)
            )
            manager.prepareWorkspace(wsCarry.id, srcRepo.absolutePath, "req_carry")
            val tgtCarry = File(wsCarry.checkoutRoot)
            assertEquals("unstaged modification", File(tgtCarry, "committed.txt").readText())
            assertTrue(File(tgtCarry, "staged.txt").exists())
            assertEquals("staged content", File(tgtCarry, "staged.txt").readText())
            assertTrue(File(tgtCarry, "untracked.txt").exists())
            assertEquals("untracked content", File(tgtCarry, "untracked.txt").readText())

            // 验证源仓库未被清理（创建阶段不清源）
            assertEquals("unstaged modification", File(srcRepo, "committed.txt").readText())
            assertTrue(File(srcRepo, "staged.txt").exists())
            assertTrue(File(srcRepo, "untracked.txt").exists())
        }
    }

    @Test
    fun rejectsCarryAcrossDifferentBaseAndMissingSubdirectory(): Unit = runBlocking {
        fixture { tempFolder ->
            val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
            runGit(srcRepo, "init")
            File(srcRepo, "f1.txt").writeText("commit 1")
            runGit(srcRepo, "add", "f1.txt")
            runGit(srcRepo, "commit", "-m", "C1")
            val c1Oid = runGit(srcRepo, "rev-parse", "HEAD")

            File(srcRepo, "f2.txt").writeText("commit 2")
            runGit(srcRepo, "add", "f2.txt")
            runGit(srcRepo, "commit", "-m", "C2")

            val (manager, wsStore, sessStore) = createManager(tempFolder)

            // 1. carryLocalChanges = true 但 baseRef 指向非 HEAD (C1)
            val wsDiffBase = manager.createWorkspace(
                projectId = "proj_1",
                sourceRepoRoot = srcRepo.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = c1Oid, carryLocalChanges = true)
            )
            val ex1 = assertFailsWith<MederiWorkspaceException> {
                manager.prepareWorkspace(wsDiffBase.id, srcRepo.absolutePath, "req_diff_base")
            }
            assertEquals(WorkspaceErrorCode.BASE_CHANGED, ex1.code)

            // 2. projectRelativePath 在基线检出中不存在
            val wsMissingSub = manager.createWorkspace(
                projectId = "proj_1",
                sourceRepoRoot = srcRepo.absolutePath,
                projectRelativePath = "nonexistent/sub/path",
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD", carryLocalChanges = false)
            )
            val ex2 = assertFailsWith<MederiWorkspaceException> {
                manager.prepareWorkspace(wsMissingSub.id, srcRepo.absolutePath, "req_missing_sub")
            }
            assertEquals(WorkspaceErrorCode.PROJECT_SUBDIRECTORY_MISSING, ex2.code)
        }
    }

    @Test
    fun acceptsOneStableMessageAcrossDisconnectAndRetry(): Unit = runBlocking {
        fixture { tempFolder ->
            val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
            runGit(srcRepo, "init")
            File(srcRepo, "README.md").writeText("Hello")
            runGit(srcRepo, "add", "README.md")
            runGit(srcRepo, "commit", "-m", "Init")

            val (manager, wsStore, sessStore) = createManager(tempFolder)

            val session = sampleSession("sess_stable", "proj_stable")
            sessStore.insert(session)

            manager.createWorkspace(
                projectId = "proj_stable",
                sourceRepoRoot = srcRepo.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE),
                sessionId = session.id
            )

            // 第一次接纳消息（同时触发创建）
            val accepted1 = manager.acceptUserMessage(
                sessionId = session.id,
                messageId = "msg_stable_1",
                content = "User instruction 1",
                sourceRepoRoot = srcRepo.absolutePath
            )
            assertTrue(accepted1)

            // 模拟客户端网络重试：以相同 messageId 再次调用 acceptUserMessage
            val accepted2 = manager.acceptUserMessage(
                sessionId = session.id,
                messageId = "msg_stable_1",
                content = "User instruction 1",
                sourceRepoRoot = srcRepo.absolutePath
            )
            assertTrue(accepted2)

            // 历史中仅且只有一条用户消息，未重复写入
            val history = manager.historyStore.load(session.id)
            assertEquals(1, history.size)
            assertEquals("msg_stable_1", history.first().id)
            assertEquals("User instruction 1", (history.first().parts.first() as MessagePart.Text).text)
        }
    }

    @Test
    fun neverStartsModelBeforeReadyOrFallsBackToLocal(): Unit = runBlocking {
        fixture { tempFolder ->
            val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
            runGit(srcRepo, "init")
            File(srcRepo, "README.md").writeText("Hello")
            runGit(srcRepo, "add", "README.md")
            runGit(srcRepo, "commit", "-m", "Init")

            // 准备失败的环境（脚本 exit 1）
            val envDir = File(srcRepo, ".mederi/environments").apply { mkdirs() }
            val envJson = """
                {
                  "id": "broken_env",
                  "name": "Broken Env",
                  "version": 1,
                  "setup": { "default": "exit 1" }
                }
            """.trimIndent()
            File(envDir, "broken_env.json").writeText(envJson)

            val (manager, wsStore, sessStore) = createManager(tempFolder)

            val session = sampleSession("sess_fail", "proj_fail")
            sessStore.insert(session)

            val ws = manager.createWorkspace(
                projectId = "proj_fail",
                sourceRepoRoot = srcRepo.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, environmentId = "broken_env"),
                sessionId = session.id
            )

            // 模型门禁检查：在 READY 之前不可执行
            assertFalse(manager.isModelExecutable(session.id))

            // 执行准备，因脚本失败抛出异常
            assertFailsWith<MederiWorkspaceException> {
                manager.prepareWorkspace(ws.id, srcRepo.absolutePath, "req_broken")
            }

            // 验证工作区状态不是 READY
            val persistedWs = wsStore.getWorkspace(ws.id)
            assertNotNull(persistedWs)
            assertNotEquals(WorkspaceState.READY, persistedWs.state)

            // 验证会话关联依然是该 managed worktree，绝不隐式回退到 LOCAL
            val persistedSess = sessStore.get(session.id)
            assertNotNull(persistedSess)
            assertEquals(ws.id, persistedSess.workspaceId)
            assertFalse(persistedSess.workspaceId!!.startsWith("ws_local_"), "失败时绝不自动回退到 LOCAL")

            // 门禁依然保持关闭，绝不启动模型
            assertFalse(manager.isModelExecutable(session.id))
        }
    }

    @Test
    fun resumesRegisteredPreparationWithoutDuplicateSetup(): Unit = runBlocking {
        fixture { tempFolder ->
            val srcRepo = File(tempFolder, "src-repo").apply { mkdirs() }
            runGit(srcRepo, "init")
            File(srcRepo, "README.md").writeText("Hello")
            runGit(srcRepo, "add", "README.md")
            runGit(srcRepo, "commit", "-m", "Init")

            val markerFile = File(tempFolder, "setup_executed.log")
            assertFalse(markerFile.exists())

            // 准备环境，脚本向 markerFile 追加内容
            val envDir = File(srcRepo, ".mederi/environments").apply { mkdirs() }
            val envJson = """
                {
                  "id": "append_env",
                  "name": "Append Env",
                  "version": 1,
                  "setup": { "default": "echo SETUP_RAN >> '${markerFile.absolutePath}'" }
                }
            """.trimIndent()
            File(envDir, "append_env.json").writeText(envJson)

            val (manager, wsStore, sessStore) = createManager(tempFolder)
            val permissions = fullAccessPermissions(tempFolder)

            val ws = manager.createWorkspace(
                projectId = "proj_resume",
                sourceRepoRoot = srcRepo.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, environmentId = "append_env")
            )

            // 第一次执行准备
            val op1 = manager.prepareWorkspace(ws.id, srcRepo.absolutePath, "req_resume", permissions = permissions)
            assertEquals(WorkspaceOperationState.SUCCEEDED, op1.state)
            assertTrue(markerFile.exists())
            assertEquals(listOf("SETUP_RAN"), markerFile.readLines().filter { it.isNotBlank() })

            // 第二次恢复/重试准备（模拟中途恢复或重复调用）
            val op2 = manager.prepareWorkspace(ws.id, srcRepo.absolutePath, "req_resume", permissions = permissions)
            assertEquals(WorkspaceOperationState.SUCCEEDED, op2.state)

            // 验证 setup 没有重复执行第二次
            assertEquals(listOf("SETUP_RAN"), markerFile.readLines().filter { it.isNotBlank() })
        }
    }
}
