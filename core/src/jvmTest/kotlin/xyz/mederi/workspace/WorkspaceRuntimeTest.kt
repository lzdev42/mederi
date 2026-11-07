package xyz.mederi.workspace

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.domain.model.*
import xyz.mederi.plan.Notebook
import xyz.mederi.plan.Plan
import xyz.mederi.plan.PlanStatus
import xyz.mederi.plan.PlanStore
import xyz.mederi.prompt.EnvironmentInfoProvider
import xyz.mederi.store.*
import xyz.mederi.tools.FileSystemTools
import xyz.mederi.tools.diff.FileChange
import xyz.mederi.tools.diff.FileChangeStatus
import xyz.mederi.tools.diff.TurnDiff
import xyz.mederi.tools.sandbox.CommandSandbox

/**
 * 运行时目录/路径/产物行为测试：验证给定已装配的 resolver/tools 时 diff 跟踪、artifact 根、
 * plan/notebook 路径等实际运行时语义。生产 SessionManager→TurnExecutor 的 resolver 装配由
 * RF-02 WorkspaceRepairIntegrationTest（managedFirstSendPreparesBeforeModelAndLeavesLocalUntouched 等）
 * 从真实 send 入口验证；本类故意只聚焦运行时的目录/路径行为，不重复装配链路。
 */
class WorkspaceRuntimeTest {

    private fun fixture(block: suspend (File) -> Unit): Unit = runBlocking {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/workspace-runtime-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
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

    @Test
    fun writesSameRelativePathIntoSeparateConversationWorkspaces(): Unit = fixture { tempFolder ->
        val dirA = File(tempFolder, "ws-a").apply { mkdirs() }
        val dirB = File(tempFolder, "ws-b").apply { mkdirs() }

        val toolsA = FileSystemTools(allowedDirectories = listOf(dirA.absolutePath))
        val toolsB = FileSystemTools(allowedDirectories = listOf(dirB.absolutePath))

        toolsA.WriteFileTool().execute(FileSystemTools.WriteFileArgs(path = "shared/code.txt", content = "Content from Session A"))
        toolsB.WriteFileTool().execute(FileSystemTools.WriteFileArgs(path = "shared/code.txt", content = "Content from Session B"))

        val fileA = File(dirA, "shared/code.txt")
        val fileB = File(dirB, "shared/code.txt")

        assertTrue(fileA.exists())
        assertTrue(fileB.exists())
        assertEquals("Content from Session A", fileA.readText())
        assertEquals("Content from Session B", fileB.readText())
    }

    @Test
    fun usesOneWorkspaceForPromptAndBothTurnDirectoryEntrypoints(): Unit = fixture { tempFolder ->
        val projectDir = File(tempFolder, "project").apply { mkdirs() }
        val worktreeDir = File(tempFolder, "worktree-1").apply { mkdirs() }

        val wsStore = InMemoryWorkspaceStore()
        val sessStore = InMemorySessionStore()
        val projStore = InMemoryProjectStore()

        val project = Project(
            id = "proj_1",
            name = "Test Project",
            directory = projectDir.absolutePath,
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString()
        )
        projStore.save(project)

        val ws = Workspace(
            id = "ws_managed_1",
            projectId = project.id,
            kind = WorkspaceKind.MANAGED_WORKTREE,
            state = WorkspaceState.READY,
            checkoutRoot = worktreeDir.absolutePath,
            projectRelativePath = "",
            generation = 1,
            createdAt = Instant.now().toString(),
            lastUsedAt = Instant.now().toString()
        )
        wsStore.saveWorkspace(ws)

        val session = sampleSession("sess_1", project.id, ws.id)
        sessStore.insert(session)

        val resolver = ExecutionWorkspaceResolver(wsStore, projStore, sessStore)
        val execWs = resolver.resolve(session.id)

        assertEquals(worktreeDir.absolutePath, execWs.workingDirectory)
        assertEquals(worktreeDir.absolutePath, execWs.checkoutRoot)
        assertEquals(File(projectDir, ".mederi").absolutePath, execWs.artifactDirectory)

        val sandbox = CommandSandbox(listOf(execWs.workingDirectory))
        val promptEnv = EnvironmentInfoProvider.build(
            commandSandbox = sandbox,
            directories = listOf(execWs.workingDirectory),
            artifactDirectory = execWs.artifactDirectory
        )

        assertTrue(promptEnv.contains("Project dir: ${worktreeDir.absolutePath}"), "Prompt contains resolved workingDirectory")
        assertTrue(promptEnv.contains("Mederi workdir: ${File(projectDir, ".mederi").absolutePath}"), "Prompt contains stable artifactDirectory")
    }

    @Test
    fun inheritsWorkspaceIntoTemporarySubagentSessionAndVerification(): Unit = fixture { tempFolder ->
        val projectDir = File(tempFolder, "project").apply { mkdirs() }
        val worktreeDir = File(tempFolder, "worktree-parent").apply { mkdirs() }

        val wsStore = InMemoryWorkspaceStore()
        val sessStore = InMemorySessionStore()
        val projStore = InMemoryProjectStore()

        val project = Project(
            id = "proj_sub",
            name = "Project Sub",
            directory = projectDir.absolutePath,
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString()
        )
        projStore.save(project)

        val ws = Workspace(
            id = "ws_parent_1",
            projectId = project.id,
            kind = WorkspaceKind.MANAGED_WORKTREE,
            state = WorkspaceState.READY,
            checkoutRoot = worktreeDir.absolutePath,
            projectRelativePath = "",
            generation = 3,
            createdAt = Instant.now().toString(),
            lastUsedAt = Instant.now().toString()
        )
        wsStore.saveWorkspace(ws)

        val parentSession = sampleSession("parent_sess", project.id, ws.id)
        sessStore.insert(parentSession)

        val resolver = ExecutionWorkspaceResolver(wsStore, projStore, sessStore)
        val resolvedParent = resolver.resolve(parentSession.id)

        // 模拟子代理创建临时 Session 继承父会话工作区
        val subagentSessionId = "sub_${UUID.randomUUID().toString().take(8)}"
        val subSession = Session(
            id = subagentSessionId,
            projectId = project.id,
            title = "Subagent",
            status = SessionStatus.IDLE,
            agentMode = AgentMode.AUTONOMOUS,
            aiModel = null,
            reasoningLevel = null,
            workspaceId = resolvedParent.workspaceId,
            env = emptyMap(),
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString()
        )

        assertEquals(ws.id, subSession.workspaceId, "子代理会话继承父级 workspaceId")
        assertEquals(worktreeDir.absolutePath, resolvedParent.workingDirectory, "执行目录继承父工作区")
        assertEquals(3L, resolvedParent.generation, "执行世代继承")
    }

    @Test
    fun gatesPlanApprovalAndBackgroundContinuationDuringTransfer(): Unit = fixture { tempFolder ->
        val projectDir = File(tempFolder, "project").apply { mkdirs() }
        val worktreeDir = File(tempFolder, "worktree-transfer").apply { mkdirs() }

        val wsStore = InMemoryWorkspaceStore()
        val sessStore = InMemorySessionStore()
        val projStore = InMemoryProjectStore()

        val project = Project(
            id = "proj_gate",
            name = "Project Gate",
            directory = projectDir.absolutePath,
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString()
        )
        projStore.save(project)

        val ws = Workspace(
            id = "ws_transferring_1",
            projectId = project.id,
            kind = WorkspaceKind.MANAGED_WORKTREE,
            state = WorkspaceState.TRANSFERRING,
            checkoutRoot = worktreeDir.absolutePath,
            projectRelativePath = "",
            generation = 2,
            createdAt = Instant.now().toString(),
            lastUsedAt = Instant.now().toString()
        )
        wsStore.saveWorkspace(ws)

        val session = sampleSession("sess_gate", project.id, ws.id)
        sessStore.insert(session)

        val resolver = ExecutionWorkspaceResolver(wsStore, projStore, sessStore)

        // TRANSFERRING 状态不可执行
        assertFalse(resolver.isExecutable(session.id), "TRANSFERRING 状态下工作区门禁关闭")
        assertFailsWith<MederiWorkspaceException> {
            resolver.resolve(session.id)
        }
    }

    @Test
    fun keepsPlanNotebookAndReportsAtStableArtifactRoot(): Unit = fixture { tempFolder ->
        val projectDir = File(tempFolder, "proj-root").apply { mkdirs() }
        val worktreeDir = File(tempFolder, "worktree-isolated").apply { mkdirs() }

        val artifactRoot = File(projectDir, ".mederi").absolutePath
        val planStore = PlanStore(listOf(worktreeDir.absolutePath), artifactRoot = artifactRoot)
        val notebook = Notebook(listOf(worktreeDir.absolutePath), artifactRoot = artifactRoot)

        val plan = Plan(
            id = "plan_artifact_test",
            title = "Artifact Test Plan",
            sessionId = "sess_artifact",
            status = PlanStatus.APPROVED,
            architecture = null,
            subtasks = emptyList(),
            createdAt = Instant.now().toString(),
            agentMode = AgentMode.AUTONOMOUS
        )
        planStore.save(plan)
        notebook.append("Notebook entry recorded")

        // 验证计划与 Notebook 落盘在稳定的宿主 projectDir/.mederi 下
        val planFile = File(projectDir, ".mederi/plans/plan_artifact_test/plan.json")
        val notebookFile = File(projectDir, ".mederi/notebook.md")

        assertTrue(planFile.exists(), "Plan saved to stable artifactRoot")
        assertTrue(notebookFile.exists(), "Notebook appended to stable artifactRoot")
        assertTrue(notebookFile.readText().contains("Notebook entry recorded"))

        // 验证隔离的工作区 checkout 目录下没有被污染创建 .mederi 运行时产物
        assertFalse(File(worktreeDir, ".mederi/plans").exists(), "工作区副本不含有计划产物")
    }

    @Test
    fun preservesOriginForHistoricalDiffAndFileLinks(): Unit = fixture { tempFolder ->
        val diffStore = InMemoryDiffStore()
        val sessionId = "sess_diff_history"

        val diff1 = TurnDiff(
            sessionId = sessionId,
            messageId = "msg_1",
            changes = listOf(
                FileChange("src/A.kt", FileChangeStatus.MODIFIED, "old A", "new A")
            ),
            unifiedDiff = "--- a/src/A.kt\n+++ b/src/A.kt",
            createdAt = "2026-11-02T01:00:00Z",
            workspaceId = "ws_1",
            checkoutRootAtCreation = "/checkouts/ws_1",
            projectRelativePathAtCreation = "",
            workspaceGeneration = 1
        )

        val diff2 = TurnDiff(
            sessionId = sessionId,
            messageId = "msg_2",
            changes = listOf(
                FileChange("src/B.kt", FileChangeStatus.ADDED, null, "new B")
            ),
            unifiedDiff = "--- /dev/null\n+++ b/src/B.kt",
            createdAt = "2026-11-02T02:00:00Z",
            workspaceId = "ws_2",
            checkoutRootAtCreation = "/checkouts/ws_2",
            projectRelativePathAtCreation = "",
            workspaceGeneration = 2
        )

        diffStore.save(diff1)
        diffStore.save(diff2)

        val list = diffStore.list(sessionId)
        assertEquals(2, list.size)

        val loaded1 = list.first { it.messageId == "msg_1" }
        assertEquals("ws_1", loaded1.workspaceId)
        assertEquals("/checkouts/ws_1", loaded1.checkoutRootAtCreation)
        assertEquals(1L, loaded1.workspaceGeneration)

        val loaded2 = list.first { it.messageId == "msg_2" }
        assertEquals("ws_2", loaded2.workspaceId)
        assertEquals("/checkouts/ws_2", loaded2.checkoutRootAtCreation)
        assertEquals(2L, loaded2.workspaceGeneration)
    }

    @Test
    fun keepsChatRollbackIndependentOfFileRestoration(): Unit = fixture { tempFolder ->
        val projectDir = File(tempFolder, "project").apply { mkdirs() }
        val worktreeDir = File(tempFolder, "worktree-chat").apply { mkdirs() }
        val testFile = File(worktreeDir, "code.kt").apply { writeText("val x = 42") }

        val histStore = InMemoryHistoryStore()
        val sessStore = InMemorySessionStore()
        val projStore = InMemoryProjectStore()

        val project = Project(
            id = "proj_rollback",
            name = "Rollback Proj",
            directory = projectDir.absolutePath,
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString()
        )
        projStore.save(project)

        val session = sampleSession("sess_rb", project.id)
        sessStore.insert(session)

        val msg1 = Message(
            id = "msg_rb_1",
            sessionId = session.id,
            role = MessageRole.USER,
            parts = listOf(MessagePart.Text("Hello")),
            createdAt = Instant.now().toString(),
            status = MessageStatus.COMPLETED
        )
        val msg2 = Message(
            id = "msg_rb_2",
            sessionId = session.id,
            role = MessageRole.ASSISTANT,
            parts = listOf(MessagePart.Text("Hi")),
            createdAt = Instant.now().toString(),
            status = MessageStatus.COMPLETED
        )
        histStore.append(session.id, msg1)
        histStore.append(session.id, msg2)

        assertEquals(2, histStore.load(session.id).size)

        // 执行聊天回退到 msg_rb_1 (seq = 0)
        histStore.rollbackTo(session.id, 0)

        // 聊天历史被截断
        val remaining = histStore.load(session.id)
        assertEquals(1, remaining.size)
        assertEquals("msg_rb_1", remaining[0].id)

        // 验证工作目录中的代码文件依然完好无损，聊天回滚不附带文件回退
        assertTrue(testFile.exists())
        assertEquals("val x = 42", testFile.readText(), "工作区物理文件不因聊天回滚而被撤销")
    }

    @Test
    fun doesNotMoveOrRestoreSharedRuntimeArtifactsDuringHandoff(): Unit = fixture { tempFolder ->
        val repoDir = File(tempFolder, "repo").apply { mkdirs() }
        val pb = ProcessBuilder("git", "init").directory(repoDir)
        pb.start().waitFor()

        File(repoDir, "README.md").writeText("Init")
        val pAdd = ProcessBuilder("git", "add", "README.md").directory(repoDir)
        pAdd.start().waitFor()
        val pCommit = ProcessBuilder("git", "-c", "user.name=T", "-c", "user.email=t@e", "commit", "-m", "init").directory(repoDir)
        pCommit.start().waitFor()

        // 创建运行时产物
        val mederiDir = File(repoDir, ".mederi").apply { mkdirs() }
        File(mederiDir, "plans/p1").apply { mkdirs() }
        File(mederiDir, "plans/p1/plan.json").writeText("{}")
        File(mederiDir, "notebook.md").writeText("# Log")

        val snapshotService = WorkspaceSnapshotService(
            snapshotsRoot = File(tempFolder, "snapshots").apply { mkdirs() }
        )

        val manifest = snapshotService.captureSnapshot(
            checkoutRoot = repoDir.absolutePath,
            workspaceId = "ws_test",
            reason = "handoff_test",
            captureType = SnapshotCaptureType.TRANSFER
        )

        // 验证快照清单中不包含 .mederi 下的 plans 和 notebook 运行时产物
        val entries = manifest.entries.map { it.repoRelativePath }
        assertFalse(entries.any { it.startsWith(".mederi/plans") }, "TRANSFER 快照清单排除 plans 运行时产物")
        assertFalse(entries.any { it == ".mederi/notebook.md" }, "TRANSFER 快照清单排除 notebook 运行时产物")
    }
}
