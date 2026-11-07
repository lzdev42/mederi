package xyz.mederi.workspace

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*
import xyz.mederi.Mederi
import xyz.mederi.api.CreateProjectRequest
import xyz.mederi.tools.sandbox.SandboxSettings

class WorkspaceFollowupReviewTest {
    private fun fixture(block: suspend (File, Mederi, String, File) -> Unit) = runBlocking {
        val repo = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val parent = File(repo, "temp/worktree-review-round4-followup/fixtures").apply { mkdirs() }
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
    @Test fun elevatedActionWithoutConversationMustFailWithoutOrphanApproval() = fixture { _, mederi, projectId, dir ->
        val manager = mederi.workspaceManager
        val ws = manager.createWorkspace(projectId, dir.path, selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL))
        val env = manager.saveProjectEnvironment(projectId,
            ProjectEnvironment("env", "Review", 0, actions = listOf(EnvironmentAction("noop", "No-op",
                script = PlatformScript(default = "printf harmless", sandbox = "network")))), 0, ws.id)
        coroutineScope {
            val task = async { runCatching { manager.runWorkspaceAction(ws.id, WorkspaceActionInput(env.id, "noop", env.version, "action")) } }
            try {
                withTimeout(5_000) {
                    while (!task.isCompleted && mederi.sandboxManager.pendingApprovals("ws_op_${ws.id}").isEmpty()) delay(10)
                }
                assertTrue(mederi.sandboxManager.pendingApprovals("ws_op_${ws.id}").isEmpty(),
                    "A workspace without a conversation must never create an unreachable approval")
                val result = task.await()
                assertTrue(result.isFailure || result.getOrNull()?.state == WorkspaceOperationState.FAILED)
            } finally { task.cancelAndJoin() }
        }
    }
    @Test fun configuredWorktreeRootMustRejectSourceProjectDirectory() = fixture { _, mederi, _, dir ->
        val manager = mederi.workspaceManager
        val before = manager.getWorktreeSettings()
        val result = runCatching { manager.updateWorktreeSettings(before.copy(rootPath = dir.path)) }
        assertTrue(result.isFailure, "A registered source project cannot itself be the managed worktree root")
        assertEquals(before, manager.getWorktreeSettings())
    }
}
