package xyz.mederi.workspace

import kotlinx.coroutines.runBlocking
import xyz.mederi.store.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/**
 * Promoted regression tests from the 2026-11-03 second review.
 * R05/R06/R07 reproduction tests with adjusted R07 fixture (uses corrupt blob
 * instead of dangling symlink to simulate materialization interruption,
 * since R05 correctly prevents the symlink write-through that the original fixture relied on).
 */
class WorkspaceLifecycleSecondReviewTest {
    private val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "gradlew").exists() }
    private fun root() = File(repo, "temp/lifecycle-second-${UUID.randomUUID()}").apply { mkdirs() }
    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=t@test.invalid") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { out }
        return out.trim()
    }
    private fun init(root: File): File = File(root, "source").apply {
        mkdirs(); git(this, "init"); File(this, "base").writeText("base"); git(this, "add", "."); git(this, "commit", "-m", "init")
    }
    private fun manager(root: File, store: WorkspaceStore) = WorkspaceManager(store, InMemorySessionStore(), InMemoryHistoryStore(), worktreesRoot = File(root, "wt").absolutePath, snapshotsRoot = File(root, "snaps").absolutePath)

    @Test fun linkedRestoreMustNotWriteThroughHeadLeafSymlink() = runBlocking {
        val root = root()
        try {
            val source = init(root)
            val external = File(root, "external").apply { writeText("external-newer") }
            val node = File(source, "node")
            Files.createSymbolicLink(node.toPath(), external.toPath())
            git(source, "add", "node"); git(source, "commit", "-m", "symlink")
            node.delete(); node.writeText("snapshot-regular-file")
            val service = WorkspaceSnapshotService(File(root, "snaps"))
            val snap = service.captureSnapshot(source.absolutePath, "ws", "test", SnapshotCaptureType.PREIMAGE)
            val common = git(source, "rev-parse", "--path-format=absolute", "--git-common-dir")
            service.restoreToLinkedWorktree(snap.snapshotId, File(root, "restored").absolutePath, common)
            assertEquals("external-newer", external.readText(), "Restoring FILE over HEAD symlink must never overwrite external target")
            assertFalse(Files.isSymbolicLink(File(root, "restored/node").toPath()))
        } finally { root.deleteRecursively() }
    }

    @Test fun archiveRecoveryMustPreserveChangesAfterSnapshot() = runBlocking {
        val root = root()
        try {
            val source = init(root)
            val delegate = InMemoryWorkspaceStore()
            val store = object : WorkspaceStore by delegate {
                var armed = false
                override suspend fun saveOperation(operation: WorkspaceOperation) {
                    delegate.saveOperation(operation)
                    if (armed && operation.kind == WorkspaceOperationKind.ARCHIVE && operation.state == WorkspaceOperationState.RUNNING && operation.sourceSnapshotId != null) {
                        armed = false
                        throw java.io.IOException("simulated stop after archive snapshot journal")
                    }
                }
            }
            val mgr = manager(root, store)
            val created = mgr.createWorkspace("p", source.absolutePath, selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE))
            mgr.prepareWorkspace(created.id, source.absolutePath, "prep")
            val ws = delegate.getWorkspace(created.id)!!
            store.armed = true
            assertFailsWith<java.io.IOException> { mgr.archiveWorkspace(ws.id, ArchiveWorkspaceInput(requestId = "archive")) }
            val external = File(ws.checkoutRoot, "after-snapshot").apply { writeText("user-downtime-edit") }
            manager(root, delegate).scanAndRecoverPendingOperations("p")
            assertTrue(external.exists(), "Recovery must not force-remove edits absent from durable snapshot")
            assertEquals("user-downtime-edit", external.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedRestoreMustNotDeclareIncompleteCheckoutReady() = runBlocking {
        // R07: If restore materialization fails (e.g., missing blob), recovery scanner must NOT
        // mark the checkout READY just because it's a registered git worktree.
        // Fixture: capture snapshot, corrupt a blob so materializeSnapshotIntoCheckout throws,
        // then run recovery — it must detect incomplete content and mark RECOVERY_REQUIRED.
        val root = root()
        try {
            val source = init(root)
            File(source, "important.txt").writeText("must-be-restored")
            git(source, "add", "important.txt"); git(source, "commit", "-m", "important")

            val store = InMemoryWorkspaceStore()
            val snapsRoot = File(root, "snaps").apply { mkdirs() }
            val mgr = WorkspaceManager(store, InMemorySessionStore(), InMemoryHistoryStore(),
                worktreesRoot = File(root, "wt").absolutePath, snapshotsRoot = snapsRoot.absolutePath)

            val created = mgr.createWorkspace("p", source.absolutePath, selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE))
            mgr.prepareWorkspace(created.id, source.absolutePath, "prep")
            val ws = store.getWorkspace(created.id)!!

            // Archive to capture snapshot
            mgr.archiveWorkspace(ws.id, ArchiveWorkspaceInput(requestId = "archive"))
            val archivedWs = store.getWorkspace(ws.id)!!
            assertEquals(WorkspaceState.ARCHIVED, archivedWs.state)
            val snapshotId = archivedWs.lastSnapshotId!!

            // Corrupt the snapshot: delete the blob for "important.txt" so materialization fails
            val manifest = mgr.snapshotService.loadManifest(snapshotId)
            val importantEntry = manifest.entries.find { it.repoRelativePath == "important.txt" }!!
            val blobFile = File(snapsRoot, "$snapshotId/blobs/${importantEntry.sha256}")
            assertTrue(blobFile.exists(), "Blob should exist before corruption")
            blobFile.delete()

            // Attempt restore — materialization should fail because the blob is missing
            val restoreResult = runCatching { mgr.restoreWorkspace(ws.id, RestoreWorkspaceInput(requestId = "restore")) }
            assertTrue(restoreResult.isFailure, "Restore should fail when blob is missing")

            // Workspace should be in RESTORING state (not READY)
            val restoringWs = store.getWorkspace(ws.id)!!
            assertTrue(restoringWs.state != WorkspaceState.READY,
                "Workspace must not be READY after failed restore, actual: ${restoringWs.state}")

            // Run recovery — must NOT mark incomplete checkout as READY
            manager(root, store).scanAndRecoverPendingOperations("p")
            val recovered = store.getWorkspace(ws.id)!!
            assertTrue(recovered.state != WorkspaceState.READY,
                "Recovery must not label incomplete checkout READY, actual: ${recovered.state}")
        } finally { root.deleteRecursively() }
    }
}
