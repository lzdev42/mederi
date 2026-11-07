package xyz.mederi.workspace

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import xyz.mederi.Mederi
import xyz.mederi.api.CreateProjectRequest
import xyz.mederi.tools.sandbox.SandboxSettings

class WorkspaceProductionThirdReviewTest {
    private fun fixture(block: suspend (File, Mederi, String, File) -> Unit) = runBlocking {
        val repo = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val parent = File(repo, "temp/worktree-review-round3/fixtures").apply { mkdirs() }
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
    @Test fun managedActionMustNotRewritePersistedProjectBlacklist() = fixture { _, mederi, projectId, dir ->
        val originalDenied = File(dir, "secret.txt").canonicalPath
        mederi.sandbox.updateSettings(projectId, SandboxSettings(deniedPaths = listOf(originalDenied)))
        val ws = mederi.workspaceManager.createWorkspace(projectId, dir.path,
            selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD"))
        mederi.workspaceManager.prepareWorkspace(ws.id, dir.path, "prepare")
        val env = mederi.workspaces.saveProjectEnvironment(projectId,
            ProjectEnvironment("env", "Review", 0, actions = listOf(EnvironmentAction("noop", "No-op",
                script = PlatformScript(default = "printf harmless")))), 0, ws.id)
        runCatching { mederi.workspaces.runWorkspaceAction(ws.id, WorkspaceActionInput(env.id, "noop", env.version, "action")) }
        val saved = mederi.sandboxManager.getSettings(projectId)
        assertEquals(listOf(originalDenied), saved.deniedPaths, "Running an action must never rewrite the user's persisted project blacklist")
    }
    @Test fun rejectedBackgroundLeaseMustPreventProcessStart() = fixture { root, mederi, projectId, dir ->
        val leases = WorkspaceLeaseManager()
        mederi.sandbox.updateSettings(projectId, SandboxSettings(mode = xyz.mederi.tools.sandbox.ExecutionPermissionMode.FULL_ACCESS))
        val permissions = xyz.mederi.tools.sandbox.ExecutionPermissions(mederi.sandboxManager, listOf(dir.path), "session", projectId = projectId)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val backgrounds = xyz.mederi.tools.background.BackgroundTaskManager(scope)
        val shell = xyz.mederi.tools.ShellTools(listOf(dir.path), backgroundTaskManager = backgrounds,
            sessionId = "session", executionPermissions = permissions, leaseManager = leases)
        val marker = File(dir, "must-not-start")
        try {
            leases.withManagementIntent(File(root, "common").path, listOf(dir.path)) {
                val result = shell.BashTool().execute(xyz.mederi.tools.ShellTools.BashArgs(command = "printf unexpected > '${marker.path}'", background = true))
                assertTrue(result.contains("Error"), "Management intent should reject background execution")
                kotlinx.coroutines.delay(150)
                assertFalse(marker.exists(), "Process must not start before acquiring the execution lease")
            }
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
}
