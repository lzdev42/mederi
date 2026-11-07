package xyz.mederi.workspace

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import xyz.mederi.Mederi
import xyz.mederi.api.CreateProjectRequest
import xyz.mederi.tools.sandbox.SandboxSettings

class WorkspaceRollbackThirdReviewTest {
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
    @Test fun rollbackMustNotWriteThroughMatchingPostimageSymlink() = fixture { root, _, _, dir ->
        val path = File(dir, "secret.txt")
        val external = File(root, "external").apply { writeText("external-original") }
        val service = WorkspaceSnapshotService(File(root, "snapshots"))
        val snapshot = service.captureSnapshot(dir.path, "ws", "review", SnapshotCaptureType.PREIMAGE)
        path.delete()
        Files.createSymbolicLink(path.toPath(), external.toPath())
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(external.path.toByteArray()).joinToString("") { "%02x".format(it) }
        val journal = listOf(WorkspacePhaseRecord(WorkspacePhase.MATERIALIZE_TARGET, "now",
            affectedPaths = listOf(PhasePathRecord("secret.txt", true, postExists = true, postType = "symlink", postHash = hash))))
        service.rollbackCheckout(dir.path, snapshot.snapshotId, journal)
        assertEquals("external-original", external.readText(), "Rollback must replace the link itself, never write through a matching postimage symlink")
    }
}
