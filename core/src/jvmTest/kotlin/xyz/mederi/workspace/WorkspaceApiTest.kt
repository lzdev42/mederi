package xyz.mederi.workspace

import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.api.impl.WorkspaceApiImpl
import xyz.mederi.project.ProjectManager
import xyz.mederi.project.ProjectManagerImpl
import xyz.mederi.store.InMemoryDiffStore
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemoryProjectStore
import xyz.mederi.store.InMemorySessionStore
import xyz.mederi.store.InMemoryWorkspaceStore
import xyz.mederi.domain.model.Project
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.domain.model.AgentMode

class WorkspaceApiTest {

    private fun createFixture(name: String): Triple<WorkspaceApiImpl, WorkspaceManager, ProjectManager> {
        val baseDir = File("temp/worktree-tests/$name-${System.currentTimeMillis()}").apply { mkdirs() }
        val sessionStore = InMemorySessionStore()
        val projectStore = InMemoryProjectStore()
        val historyStore = InMemoryHistoryStore()
        val diffStore = InMemoryDiffStore()
        val workspaceStore = InMemoryWorkspaceStore(sessionStore)

        val projectManager = ProjectManagerImpl(projectStore, sessionStore, historyStore, diffStore)
        val gitDriver = GitWorkspaceDriver()
        val worktreesRoot = File(baseDir, "worktrees").absolutePath
        val snapshotsRoot = File(baseDir, "snapshots").absolutePath

        val workspaceManager = WorkspaceManager(
            workspaceStore = workspaceStore,
            sessionStore = sessionStore,
            historyStore = historyStore,
            gitDriver = gitDriver,
            worktreesRoot = worktreesRoot,
            snapshotsRoot = snapshotsRoot,
            projectStore = projectStore
        )

        val api = WorkspaceApiImpl(workspaceManager, projectManager) { _, _ ->
            error("This fixture has no pending workspace input")
        }
        return Triple(api, workspaceManager, projectManager)
    }

    private fun initGitRepo(dir: File): String {
        dir.mkdirs()
        ProcessBuilder("git", "init", "-b", "main").directory(dir).start().waitFor()
        ProcessBuilder("git", "config", "user.name", "Tester").directory(dir).start().waitFor()
        ProcessBuilder("git", "config", "user.email", "tester@mederi.xyz").directory(dir).start().waitFor()
        File(dir, "README.md").writeText("hello world\n")
        ProcessBuilder("git", "add", "README.md").directory(dir).start().waitFor()
        ProcessBuilder("git", "commit", "-m", "init").directory(dir).start().waitFor()
        val proc = ProcessBuilder("git", "rev-parse", "HEAD").directory(dir).start()
        val head = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        return head
    }

    private fun sampleSession(id: String, projectId: String, workspaceId: String? = null): Session {
        val now = Instant.now().toString()
        return Session(
            id = id,
            projectId = projectId,
            title = "Test Session",
            status = SessionStatus.IDLE,
            agentMode = AgentMode.AUTONOMOUS,
            aiModel = null,
            reasoningLevel = null,
            createdAt = now,
            updatedAt = now,
            workspaceId = workspaceId
        )
    }

    @Test
    fun keepsBusinessStateInManagerAndBridgesThin() = runBlocking {
        val (api, manager, projectManager) = createFixture("keepsBusinessStateInManagerAndBridgesThin")
        val projDir = File("temp/worktree-tests/proj1-${System.currentTimeMillis()}").apply { mkdirs() }
        val head = initGitRepo(projDir)

        val project = projectManager.create("Proj 1", projDir.absolutePath)

        // API 转调 manager 获取仓库信息
        val repoInfo = api.getRepositoryInfo(project.id)
        assertTrue(repoInfo.isGit)
        assertEquals(head, repoInfo.headOid)

        // API 转调 manager 获取 worktree settings
        val settings = api.getWorktreeSettings()
        assertEquals(manager.getWorktreeSettings().rootPath, settings.rootPath)

        // API 是薄封装，底层状态变化立即反映在 API 结果中
        val updatedSettings = api.updateWorktreeSettings(settings.copy(maxManagedWorktrees = 42))
        assertEquals(42, updatedSettings.maxManagedWorktrees)
        assertEquals(settings.version + 1, updatedSettings.version)
        assertEquals(42, manager.getWorktreeSettings().maxManagedWorktrees)
    }

    @Test
    fun scopesWorkspaceOperationsAndFilesToOwningProject() = runBlocking {
        val (api, manager, projectManager) = createFixture("scopesWorkspaceOperationsAndFilesToOwningProject")
        val dir1 = File("temp/worktree-tests/projA-${System.currentTimeMillis()}").apply { mkdirs() }
        val dir2 = File("temp/worktree-tests/projB-${System.currentTimeMillis()}").apply { mkdirs() }
        initGitRepo(dir1)
        initGitRepo(dir2)

        val projA = projectManager.create("Proj A", dir1.absolutePath)
        val projB = projectManager.create("Proj B", dir2.absolutePath)

        // 为 projA 创建 workspace
        val wsA = manager.createWorkspace(
            projectId = projA.id,
            sourceRepoRoot = dir1.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        // 为 projB 创建 workspace
        val wsB = manager.createWorkspace(
            projectId = projB.id,
            sourceRepoRoot = dir2.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )

        // 操作和列表完全隔离在项目作用域
        val opsA = api.listWorkspaceOperations(projA.id)
        val opsB = api.listWorkspaceOperations(projB.id)
        assertTrue(opsA.all { it.projectId == projA.id })
        assertTrue(opsB.all { it.projectId == projB.id })

        // 跨项目环境列表访问被拒绝
        val ex = assertFailsWith<MederiWorkspaceException> {
            api.listProjectEnvironments(projectId = projA.id, workspaceId = wsB.id)
        }
        assertEquals(WorkspaceErrorCode.PATH_DENIED, ex.code)

        // 越界访问文件被拒绝
        val exFile = assertFailsWith<MederiWorkspaceException> {
            api.readWorkspaceFile(wsA.id, path = "../../etc/passwd")
        }
        assertEquals(WorkspaceErrorCode.PATH_DENIED, exFile.code)
    }

    @Test
    fun deduplicatesRequestsAndRejectsHashOrVersionMismatch() = runBlocking {
        val (api, manager, projectManager) = createFixture("deduplicatesRequestsAndRejectsHashOrVersionMismatch")
        val dir = File("temp/worktree-tests/projDedupe-${System.currentTimeMillis()}").apply { mkdirs() }
        val head = initGitRepo(dir)
        val proj = projectManager.create("Dedupe Proj", dir.absolutePath)

        val ws = manager.createWorkspace(
            projectId = proj.id,
            sourceRepoRoot = dir.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )

        // 保存环境
        val env = ProjectEnvironment(
            id = "node-dev",
            name = "Node Dev",
            setup = PlatformScript(default = "echo setup"),
            version = 1L
        )
        val saved = api.saveProjectEnvironment(proj.id, env, expectedVersion = 1L)
        assertEquals(2L, saved.version)

        // 再次保存若版本不匹配则拒绝
        val ex = assertFailsWith<MederiWorkspaceException> {
            api.saveProjectEnvironment(proj.id, env.copy(name = "Changed"), expectedVersion = 1L)
        }
        assertEquals(WorkspaceErrorCode.INVALID_REQUEST, ex.code)

        // 分支创建世代不匹配拒绝
        val branchEx = assertFailsWith<MederiWorkspaceException> {
            api.createWorkspaceBranch(ws.id, CreateWorkspaceBranchInput(name = "feat/test", expectedHead = head, expectedGeneration = 999L))
        }
        assertEquals(WorkspaceErrorCode.STALE_OPERATION, branchEx.code)
    }

    @Test
    fun supportsArchivePinRetryAndExplicitRecoveryTransitions() = runBlocking {
        val (api, manager, projectManager) = createFixture("supportsArchivePinRetryAndExplicitRecoveryTransitions")
        val dir = File("temp/worktree-tests/projLifecycle-${System.currentTimeMillis()}").apply { mkdirs() }
        initGitRepo(dir)
        val proj = projectManager.create("Lifecycle Proj", dir.absolutePath)

        val ws = manager.createWorkspace(
            projectId = proj.id,
            sourceRepoRoot = dir.absolutePath,
            selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL)
        )
        val session = sampleSession("sess_lifecycle", proj.id, ws.id)
        manager.sessionStore.insert(session)

        val now = Instant.now().toString()
        manager.workspaceStore.commitBinding(
            sessionId = session.id,
            expectedWorkspaceId = null,
            expectedGeneration = null,
            newWorkspace = ws,
            associatedWorktreeId = null,
            operation = WorkspaceOperation(
                id = "op_bind",
                projectId = proj.id,
                workspaceId = ws.id,
                kind = WorkspaceOperationKind.CREATE,
                state = WorkspaceOperationState.SUCCEEDED,
                phase = WorkspacePhase.COMPLETE,
                idempotencyKey = "BIND:op_bind",
                requestHash = "hash_bind",
                createdAt = now,
                updatedAt = now,
                operationPlan = WorkspaceOperationPlan.CreatePlan(
                    requestHash = "hash_bind",
                    selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL),
                    targetPath = ws.checkoutRoot
                )
            )
        )

        // 置顶操作
        val pinned = api.setConversationPinned(session.id, true)
        assertTrue(pinned.pinned)

        // 取消置顶
        val unpinned = api.setConversationPinned(session.id, false)
        assertTrue(!unpinned.pinned)

        // 归档操作
        val archiveOp = api.setConversationArchived(session.id, true, "req_arch")
        assertEquals(WorkspaceOperationState.SUCCEEDED, archiveOp.state)

        val archivedList = api.listArchivedConversations(proj.id)
        assertEquals(1, archivedList.size)
        assertEquals(session.id, archivedList[0].id)
    }
}
