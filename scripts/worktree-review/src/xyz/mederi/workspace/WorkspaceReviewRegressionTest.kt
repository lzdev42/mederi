package xyz.mederi.workspace

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import xyz.mederi.Mederi
import xyz.mederi.api.CreateProjectRequest
import xyz.mederi.store.*
import xyz.mederi.tools.sandbox.*

class WorkspaceReviewRegressionTest {
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val repo = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val parent = File(repo, "temp/worktree-review-tests/fixtures").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "case-").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=Review", "-c", "user.email=review@example.invalid") + args)
            .directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }
    private suspend fun withMederi(root: File, block: suspend (Mederi, String, Workspace, File) -> Unit) {
        val projectDir = File(root, "repo").apply { mkdirs() }
        git(projectDir, "init")
        val mederi = Mederi.create { configDir = File(root, "runtime").path }
        try {
            val project = mederi.projects.create(CreateProjectRequest("Review fixture", projectDir.path))
            val workspace = mederi.workspaceManager.createWorkspace(project.id, projectDir.path)
            block(mederi, project.id, workspace, projectDir)
        } finally { mederi.sandboxManager.close() }
    }

    @Test fun fileReadMustRejectSiblingDirectoryWithSamePrefix() = fixture { root ->
        withMederi(root) { mederi, _, ws, _ ->
            File(root, "repo-secrets").mkdirs()
            File(root, "repo-secrets/secret.txt").writeText("fixture sibling secret")
            val result = runCatching { mederi.workspaces.readWorkspaceFile(ws.id, "../repo-secrets/secret.txt") }
            assertTrue(result.isFailure, "Sibling path was readable: ${result.getOrNull()?.text}")
        }
    }
    @Test fun fileListingMustRejectSiblingDirectoryWithSamePrefix() = fixture { root ->
        withMederi(root) { mederi, _, ws, _ ->
            File(root, "repo-secrets").mkdirs()
            File(root, "repo-secrets/secret.txt").writeText("fixture")
            val result = runCatching { mederi.workspaces.listWorkspaceFiles(ws.id, "../repo-secrets") }
            assertTrue(result.isFailure, "Sibling directory was listed: ${result.getOrNull()}")
        }
    }
    @Test fun fileReadMustRespectPersistedProjectBlacklist() = fixture { root ->
        withMederi(root) { mederi, projectId, ws, projectDir ->
            val secret = File(projectDir, "secret.txt").apply { writeText("fixture denied secret") }
            mederi.sandbox.updateSettings(projectId, SandboxSettings(deniedPaths = listOf(secret.path)))
            val result = runCatching { mederi.workspaces.readWorkspaceFile(ws.id, "secret.txt") }
            assertTrue(result.isFailure, "Persisted blacklist bypassed: ${result.getOrNull()?.text}")
        }
    }
    @Test fun environmentActionMustNotWriteDeniedOutsideFile() = fixture { root ->
        val projectDir = File(root, "repo").apply { mkdirs() }
        val outside = File(root, "denied.txt").apply { writeText("original") }
        val sessions = InMemorySessionStore()
        val store = InMemoryWorkspaceStore(sessions)
        val manager = SandboxManager()
        try {
            manager.updateSettings("project", SandboxSettings(deniedPaths = listOf(outside.path)))
            val permissions = ExecutionPermissions(manager, listOf(projectDir.path), "conversation", projectId = "project")
            val workspace = Workspace("workspace", "project", WorkspaceKind.LOCAL, projectDir.path, "", state = WorkspaceState.READY,
                generation = 1, createdAt = "2026-11-02T00:00:00Z", lastUsedAt = "2026-11-02T00:00:00Z")
            val environment = ProjectEnvironment("env", "Review", 1, actions = listOf(EnvironmentAction("write", "Write",
                script = PlatformScript(default = "printf overwritten > '${outside.path}'"))))
            WorkspaceEnvironmentService(store).executeAction(workspace, WorkspaceActionInput("env", "write", 1, "review-action"), environment, permissions)
            assertEquals("original", outside.readText(), "Action bypassed supplied execution permissions")
        } finally { manager.close() }
    }
    @Test fun restoringPreimageMustNotWriteThroughDirectorySymlink() = fixture { root ->
        val source = File(root, "source").apply { mkdirs() }
        val outside = File(root, "outside").apply { mkdirs() }
        val secret = File(outside, "secret.txt").apply { writeText("snapshot value") }
        git(source, "init")
        File(source, "README").writeText("fixture")
        Files.createSymbolicLink(File(source, "external-link").toPath(), java.nio.file.Path.of("../outside"))
        git(source, "add", "."); git(source, "commit", "-m", "fixture")
        val service = WorkspaceSnapshotService(File(root, "snapshots"))
        val snapshot = service.captureSnapshot(source.path, "workspace", "review", SnapshotCaptureType.PREIMAGE)
        secret.writeText("external newer value")
        service.restoreToCleanCheckout(snapshot.snapshotId, File(root, "restored").path)
        assertEquals("external newer value", secret.readText(), "Snapshot restore wrote through symlink outside checkout")
    }
    @Test fun rollbackMustKeepExternalPostimageChange() = fixture { root ->
        val source = File(root, "source").apply { mkdirs() }
        git(source, "init")
        val file = File(source, "file.txt").apply { writeText("preimage") }
        git(source, "add", "."); git(source, "commit", "-m", "fixture")
        val service = WorkspaceSnapshotService(File(root, "snapshots"))
        val snapshot = service.captureSnapshot(source.path, "workspace", "review", SnapshotCaptureType.PREIMAGE)
        val operationValue = "operation value"
        val postHash = java.security.MessageDigest.getInstance("SHA-256").digest(operationValue.toByteArray()).joinToString("") { "%02x".format(it) }
        file.writeText("external newer value")
        val journal = listOf(WorkspacePhaseRecord(WorkspacePhase.MATERIALIZE_TARGET, "2026-11-02T00:00:00Z",
            affectedPaths = listOf(PhasePathRecord("file.txt", true, postExists = true, postHash = postHash))))
        runCatching { service.rollbackCheckout(source.path, snapshot.snapshotId, journal) }
        assertEquals("external newer value", file.readText(), "Rollback overwrote external data instead of reporting recovery required")
    }
}
