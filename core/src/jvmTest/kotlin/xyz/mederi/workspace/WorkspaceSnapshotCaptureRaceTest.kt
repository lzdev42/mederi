package xyz.mederi.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemorySessionStore
import xyz.mederi.store.InMemoryWorkspaceStore
import xyz.mederi.store.WorkspaceStore
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceSnapshotCaptureRaceTest {
    private val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "gradlew").exists() }

    private fun fixtureRoot(): File =
        File(repo, "temp/worktree-snapshot-capture/fixtures/archive-${UUID.randomUUID()}").apply { mkdirs() }

    private fun git(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=Review", "-c", "user.email=review@test.invalid") + args)
            .directory(directory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }

    @Test
    fun archiveMustPreserveEditBetweenCapturingBlobAndFingerprint() = runBlocking {
        val root = fixtureRoot()
        try {
            val source = File(root, "source").apply { mkdirs() }
            git(source, "init")
            File(source, "base").writeText("base")
            git(source, "add", ".")
            git(source, "commit", "-m", "Initial")
            val driver = GitWorkspaceDriver()
            var injectAtFingerprint = false
            var injected = false
            val snapshotService = object : WorkspaceSnapshotService(File(root, "snapshots"), driver) {
                override suspend fun computeFingerprint(checkoutRoot: String): String {
                    if (injectAtFingerprint) {
                        injectAtFingerprint = false
                        File(checkoutRoot, "late.txt").writeText("newer-external-content")
                        injected = true
                    }
                    return super.computeFingerprint(checkoutRoot)
                }
            }
            val store = InMemoryWorkspaceStore()
            val manager = WorkspaceManager(store, InMemorySessionStore(), InMemoryHistoryStore(), gitDriver = driver,
                worktreesRoot = File(root, "wt").path, snapshotService = snapshotService)
            val requested = manager.createWorkspace("p", source.path,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE))
            manager.prepareWorkspace(requested.id, source.path, "prepare")
            val checkout = store.getWorkspace(requested.id)!!.checkoutRoot
            val late = File(checkout, "late.txt").apply { writeText("before-capture") }
            injectAtFingerprint = true
            val error = assertFailsWith<MederiWorkspaceException> {
                manager.archiveWorkspace(requested.id, ArchiveWorkspaceInput(requestId = "archive"))
            }
            assertEquals(WorkspaceErrorCode.EXTERNAL_CHANGE, error.code)
            assertTrue(injected, "Fixture must edit after snapshot blob collection, as fingerprint collection starts")
            assertTrue(late.isFile, "Archive must not delete an edit absent from snapshot blobs")
            assertEquals("newer-external-content", late.readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun preimageRecordsMissingTrackedFileHiddenByIndexFlag() = runBlocking {
        val root = fixtureRoot()
        try {
            val source = File(root, "source").apply { mkdirs() }
            git(source, "init")
            val hidden = File(source, "hidden.txt").apply { writeText("tracked") }
            git(source, "add", ".")
            git(source, "commit", "-m", "Initial")
            git(source, "update-index", "--assume-unchanged", "hidden.txt")
            assertTrue(hidden.delete())
            val snapshot = WorkspaceSnapshotService(File(root, "snapshots")).captureSnapshot(
                source.path, "workspace", "preimage", SnapshotCaptureType.PREIMAGE
            )
            assertEquals(false, snapshot.entries.single { it.repoRelativePath == "hidden.txt" }.exists)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellingArchiveWaitsForActiveCaptureAndPreservesCheckout() = runBlocking {
        val root = fixtureRoot()
        try {
            val source = File(root, "source").apply { mkdirs() }
            git(source, "init")
            File(source, "base").writeText("base")
            git(source, "add", ".")
            git(source, "commit", "-m", "Initial")
            val delegate = InMemoryWorkspaceStore()
            val snapshotJournalSaved = CompletableDeferred<String>()
            val store = object : WorkspaceStore by delegate {
                override suspend fun saveOperation(operation: WorkspaceOperation) {
                    delegate.saveOperation(operation)
                    if (operation.kind == WorkspaceOperationKind.ARCHIVE &&
                        operation.state == WorkspaceOperationState.RUNNING && operation.sourceSnapshotId != null
                    ) {
                        snapshotJournalSaved.complete(operation.id)
                        awaitCancellation()
                    }
                }
            }
            val manager = WorkspaceManager(store, InMemorySessionStore(), InMemoryHistoryStore(),
                worktreesRoot = File(root, "wt").path)
            val requested = manager.createWorkspace("p", source.path,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE))
            manager.prepareWorkspace(requested.id, source.path, "prepare")
            val checkout = File(delegate.getWorkspace(requested.id)!!.checkoutRoot)
            val archive = async { manager.archiveWorkspace(requested.id, ArchiveWorkspaceInput(requestId = "cancel-active")) }
            withTimeout(10_000) {
                val cancelled = manager.cancelOperation(snapshotJournalSaved.await())
                archive.join()
                assertTrue(archive.isCancelled)
                assertEquals(WorkspaceOperationState.CANCELLED, cancelled.state)
                assertEquals(WorkspaceState.READY, delegate.getWorkspace(requested.id)?.state)
                assertEquals("base", File(checkout, "base").readText())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
