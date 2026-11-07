package xyz.mederi.workspace

import kotlinx.coroutines.runBlocking
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemorySessionStore
import xyz.mederi.store.InMemoryWorkspaceStore
import xyz.mederi.store.WorkspaceStore
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceLifecycleFourthReviewTest {
    private val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "gradlew").exists() }

    private fun fixtureRoot(): File =
        File(repo, "temp/worktree-review-round4/fixtures/lifecycle-${UUID.randomUUID()}").apply { mkdirs() }

    private fun git(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=Review", "-c", "user.email=review@test.invalid") + args)
            .directory(directory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }

    @Test
    fun handoffMustReplaceHeadSymlinkWithoutWritingThroughIt() = runBlocking {
        val root = fixtureRoot()
        try {
            val outside = File(root, "external").apply { writeText("external-original") }
            val source = File(root, "source").apply { mkdirs() }
            git(source, "init")
            val leaf = File(source, "node")
            Files.createSymbolicLink(leaf.toPath(), outside.toPath())
            git(source, "add", ".")
            git(source, "commit", "-m", "Track a symlink")
            Files.delete(leaf.toPath())
            leaf.writeText("carried regular file")

            val sessions = InMemorySessionStore()
            val now = Instant.now().toString()
            sessions.insert(Session(id = "session", projectId = "p", title = "Review", status = SessionStatus.IDLE,
                agentMode = AgentMode.AUTONOMOUS, aiModel = null, reasoningLevel = null, createdAt = now, updatedAt = now))
            val manager = WorkspaceManager(InMemoryWorkspaceStore(sessions), sessions, InMemoryHistoryStore(),
                worktreesRoot = File(root, "wt").absolutePath)
            manager.createWorkspace("p", source.absolutePath, selection = WorkspaceSelection(kind = WorkspaceKind.LOCAL),
                sessionId = "session")
            val preview = manager.previewHandoff("session", HandoffPreviewInput(targetKind = WorkspaceKind.MANAGED_WORKTREE))
            val targetLeaf = File(preview.target.workspace.checkoutRoot, "node")
            assertTrue(Files.isSymbolicLink(targetLeaf.toPath()), "The managed checkout must start from HEAD's symlink")
            val result = runCatching {
                manager.executeHandoff("session", HandoffInput(targetKind = WorkspaceKind.MANAGED_WORKTREE,
                    previewToken = preview.token, requestId = "handoff"))
            }
            assertEquals("external-original", outside.readText(), "Handoff must never write through the target symlink")
            assertEquals(WorkspaceOperationState.SUCCEEDED, result.getOrThrow().state)
            assertTrue(!Files.isSymbolicLink(targetLeaf.toPath()))
            assertEquals("carried regular file", targetLeaf.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun liveArchiveMustNotRemoveAnEditAfterSnapshotJournal() = runBlocking {
        val root = fixtureRoot()
        try {
            val source = File(root, "source").apply { mkdirs() }
            git(source, "init")
            File(source, "base").writeText("base")
            git(source, "add", ".")
            git(source, "commit", "-m", "Initial")
            val delegate = InMemoryWorkspaceStore()
            lateinit var lateFile: File
            var injected = false
            val store = object : WorkspaceStore by delegate {
                override suspend fun saveOperation(operation: WorkspaceOperation) {
                    delegate.saveOperation(operation)
                    if (!injected && operation.kind == WorkspaceOperationKind.ARCHIVE &&
                        operation.state == WorkspaceOperationState.RUNNING && operation.sourceSnapshotId != null) {
                        injected = true
                        lateFile.writeText("external-newer-value")
                    }
                }
            }
            val manager = WorkspaceManager(store, InMemorySessionStore(), InMemoryHistoryStore(),
                worktreesRoot = File(root, "wt").absolutePath)
            val requested = manager.createWorkspace("p", source.absolutePath,
                selection = WorkspaceSelection(kind = WorkspaceKind.MANAGED_WORKTREE))
            manager.prepareWorkspace(requested.id, source.absolutePath, "prepare")
            val workspace = delegate.getWorkspace(requested.id)!!
            lateFile = File(workspace.checkoutRoot, "late.txt").apply { writeText("snapshot-value") }
            runCatching { manager.archiveWorkspace(workspace.id, ArchiveWorkspaceInput(requestId = "archive")) }
            assertTrue(injected, "Fixture must write after the real archive snapshot journal")
            assertTrue(lateFile.exists(), "Archive must preserve an external edit after snapshot journal, before force remove")
            assertEquals("external-newer-value", lateFile.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
