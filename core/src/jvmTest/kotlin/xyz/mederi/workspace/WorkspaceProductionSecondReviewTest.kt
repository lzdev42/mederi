package xyz.mederi.workspace

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import xyz.mederi.Mederi
import xyz.mederi.api.CreateProjectRequest
import xyz.mederi.tools.sandbox.SandboxSettings

class WorkspaceProductionSecondReviewTest {
    private fun fixture(block: suspend (File, Mederi, String, File) -> Unit) = runBlocking {
        val repo = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val parent = File(repo, "temp/worktree-review-2026-11-03/fixtures").apply { mkdirs() }
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
    @Test fun productionActionMustEnforceProjectDeniedPaths() = fixture { root, mederi, projectId, dir ->
        val denied = File(root, "denied.txt").apply { writeText("original") }
        val ws = mederi.workspaceManager.createWorkspace(projectId, dir.path)
        mederi.sandbox.updateSettings(projectId, SandboxSettings(deniedPaths = listOf(denied.path)))
        val env = mederi.workspaces.saveProjectEnvironment(projectId,
            ProjectEnvironment("env", "Review", 0, actions = listOf(EnvironmentAction("write", "Write",
                script = PlatformScript(default = "printf overwritten > '${denied.path}'")))), 0, ws.id)
        runCatching { mederi.workspaces.runWorkspaceAction(ws.id, WorkspaceActionInput(env.id, "write", env.version, "action")) }
        assertEquals("original", denied.readText(), "Production API must pass real permissions to script execution")
    }
    @Test fun managedFileApiMustRemapProjectBlacklist() = fixture { _, mederi, projectId, dir ->
        mederi.sandbox.updateSettings(projectId, SandboxSettings(deniedPaths = listOf(File(dir, "secret.txt").path)))
        val ws = mederi.workspaceManager.createWorkspace(projectId, dir.path,
            selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE, baseRef = "HEAD"))
        mederi.workspaceManager.prepareWorkspace(ws.id, dir.path, "prepare")
        val result = runCatching { mederi.workspaces.readWorkspaceFile(ws.id, "secret.txt") }
        assertTrue(result.isFailure, "Managed checkout leaked project-blacklisted file: ${result.getOrNull()?.text}")
    }
}
