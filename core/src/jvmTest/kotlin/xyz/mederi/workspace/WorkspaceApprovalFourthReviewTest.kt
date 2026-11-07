package xyz.mederi.workspace

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*
import xyz.mederi.Mederi
import xyz.mederi.api.CreateProjectRequest
import xyz.mederi.tools.sandbox.SandboxSettings

class WorkspaceApprovalFourthReviewTest {
    private fun fixture(block: suspend (File, Mederi, String, File) -> Unit) = runBlocking {
        val repo = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val parent = File(repo, "temp/worktree-review-round4/fixtures").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "case-").toFile().canonicalFile
        val dir = File(root, "repo").apply { mkdirs() }
        git(dir, "init")
        File(dir, "secret.txt").writeText("fake protected content")
        git(dir, "add", ".")
        git(dir, "commit", "-m", "fixture")
        val mederi = Mederi.create { configDir = File(root, "runtime").path }
        try {
            val project = mederi.projects.create(CreateProjectRequest("Review", dir.path))
            block(root, mederi, project.id, dir)
        } finally { mederi.sandboxManager.close(); root.deleteRecursively() }
    }
    private fun git(dir: File, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=Review", "-c", "user.email=review@example.invalid") + args)
            .directory(dir).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { output }
    }
    private suspend fun addSession(mederi: Mederi, projectId: String, id: String) {
        val now = java.time.Instant.now().toString()
        mederi.workspaceManager.sessionStore.insert(xyz.mederi.domain.model.Session(
            id = id, projectId = projectId, title = "Review", status = xyz.mederi.domain.model.SessionStatus.IDLE,
            agentMode = xyz.mederi.domain.model.AgentMode.AUTONOMOUS, aiModel = null, reasoningLevel = null,
            env = emptyMap(), createdAt = now, updatedAt = now
        ))
    }
    @Test fun elevatedActionApprovalMustReachItsRealConversation() = fixture { _, mederi, projectId, dir ->
        val sessionId = "real-conversation"
        addSession(mederi, projectId, sessionId)
        val manager = mederi.workspaceManager
        val ws = manager.createWorkspace(projectId, dir.path, selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL), sessionId = sessionId)
        val env = manager.saveProjectEnvironment(projectId,
            ProjectEnvironment("env", "Review", 0, actions = listOf(EnvironmentAction("noop", "No-op",
                script = PlatformScript(default = "printf harmless", sandbox = "network")))), 0, ws.id)
        coroutineScope {
            val task = async { manager.runWorkspaceAction(ws.id, WorkspaceActionInput(env.id, "noop", env.version, "action")) }
            try {
                withTimeout(5_000) {
                    while (mederi.sandboxManager.pendingApprovals(sessionId).isEmpty() &&
                        mederi.sandboxManager.pendingApprovals("ws_op_${ws.id}").isEmpty() && !task.isCompleted) delay(10)
                }
                assertTrue(mederi.sandboxManager.pendingApprovals(sessionId).isNotEmpty(),
                    "Expected an approval in the real conversation; synthetic owner requests=" +
                        mederi.sandboxManager.pendingApprovals("ws_op_${ws.id}").size)
            } finally { task.cancelAndJoin() }
        }
    }
    @Test fun preparationRetryMustUseConversationAndFailedOperationIds() = fixture { _, mederi, projectId, dir ->
        val sessionId = "retry-conversation"
        addSession(mederi, projectId, sessionId)
        val manager = mederi.workspaceManager
        mederi.sandbox.updateSettings(projectId, SandboxSettings(mode = xyz.mederi.tools.sandbox.ExecutionPermissionMode.FULL_ACCESS))
        manager.saveProjectEnvironment(projectId, ProjectEnvironment("retry-env", "Review", 0, setup = PlatformScript(default = "exit 1")), 0)
        val ws = manager.createWorkspace(projectId, dir.path, selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE,
            baseRef = "HEAD", environmentId = "retry-env"), sessionId = sessionId)
        runCatching { manager.prepareWorkspace(ws.id, dir.path, "failed-preparation") }
        val failed = manager.workspaceStore.listOperations(projectId).single { it.kind == WorkspaceOperationKind.CREATE && it.state == WorkspaceOperationState.FAILED }
        val retry = runCatching { manager.retryPreparation(sessionId, failed.id, "retry-from-ui") }
        assertTrue(retry.isSuccess, "The bridge passes (sessionId, failedOperationId, requestId); retry returned ${retry.exceptionOrNull()}")
        assertEquals(ws.id, retry.getOrThrow().workspaceId)
    }
}
