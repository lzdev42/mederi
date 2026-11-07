package xyz.mederi.workspace

import kotlinx.coroutines.*
import xyz.mederi.store.*
import xyz.mederi.domain.model.*
import xyz.mederi.tools.sandbox.*
import java.io.File
import java.util.UUID
import kotlin.test.*

class WorkspaceLifecycleThirdReviewTest {
    private val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "gradlew").exists() }
    private fun root() = File(repo, "temp/worktree-review-round3/fixtures/${UUID.randomUUID()}").apply { mkdirs() }
    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=t@test.invalid") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { out }; return out.trim()
    }
    @Test fun startupMustNotReplayOlderSucceededHandoffOverLatestBinding() = runBlocking {
        val root = root()
        try {
            val source = File(root, "source").apply { mkdirs() }
            git(source, "init"); git(source, "checkout", "-b", "main")
            File(source, "base").writeText("base"); git(source, "add", "."); git(source, "commit", "-m", "init")
            val sessions = InMemorySessionStore()
            val store = InMemoryWorkspaceStore(sessions)
            val mgr = WorkspaceManager(store, sessions, InMemoryHistoryStore(), worktreesRoot = File(root, "wt").absolutePath)
            val now = java.time.Instant.now().toString()
            sessions.insert(Session(id="session", projectId="p", title="Review", status=SessionStatus.IDLE,
                agentMode=AgentMode.AUTONOMOUS, aiModel=null, reasoningLevel=null, createdAt=now, updatedAt=now))
            val local = mgr.createWorkspace("p", source.absolutePath, selection=WorkspaceSelection(kind=WorkspaceKind.LOCAL), sessionId="session")
            val out = mgr.previewHandoff("session", HandoffPreviewInput(targetKind=WorkspaceKind.MANAGED_WORKTREE))
            mgr.executeHandoff("session", HandoffInput(targetKind=WorkspaceKind.MANAGED_WORKTREE, previewToken=out.token, requestId="out"))
            // Give operations an unambiguous timestamp order, as SQLite queries newest first.
            delay(10)
            val back = mgr.previewHandoff("session", HandoffPreviewInput(targetKind=WorkspaceKind.LOCAL))
            mgr.executeHandoff("session", HandoffInput(targetKind=WorkspaceKind.LOCAL, previewToken=back.token, requestId="back"))
            assertEquals(local.id, sessions.get("session")!!.workspaceId, "Actual return handoff must bind Local before restart")
            val restarted = WorkspaceManager(store, sessions, InMemoryHistoryStore(), worktreesRoot=File(root,"wt").absolutePath)
            restarted.scanAndRecoverPendingOperations("p")
            assertEquals(local.id, sessions.get("session")!!.workspaceId, "Startup must retain the latest completed return to Local, not reapply older successful outbound handoff")
        } finally { root.deleteRecursively() }
    }

    @Test fun environmentActionMustNotBypassAnotherManagementIntent() = runBlocking {
        val root = root()
        val sandbox = SandboxManager()
        try {
            val checkout = File(root,"checkout").apply { mkdirs() }
            val marker = File(checkout,"marker")
            val leases = WorkspaceLeaseManager()
            val ws = Workspace("ws","p",WorkspaceKind.LOCAL,checkout.absolutePath,"",state=WorkspaceState.READY,generation=1,createdAt="now",lastUsedAt="now")
            val store = InMemoryWorkspaceStore()
            store.saveWorkspace(ws)
            sandbox.updateSettings("p",SandboxSettings(mode=ExecutionPermissionMode.FULL_ACCESS))
            val permissions = ExecutionPermissions(sandbox,listOf(checkout.absolutePath),"session",projectId="p")
            val service = WorkspaceEnvironmentService(store, leaseManager=leases)
            val env = ProjectEnvironment("env","Review",1,actions=listOf(EnvironmentAction("write","Write",script=PlatformScript(default="printf unexpected > '${marker.path}'"))))
            leases.withManagementIntent(File(root,"common").path,listOf(checkout.absolutePath)) {
                // Independent caller attempts an action while archive/handoff owns exclusive intent.
                withContext(Dispatchers.Default) {
                    runCatching { service.executeAction(ws,WorkspaceActionInput("env","write",1,"action"),env,permissions) }
                }
                assertFalse(marker.exists(), "WORKSPACE_BUSY must stop the action; it is not proof this caller owns the management intent")
            }
        } finally { sandbox.close(); root.deleteRecursively() }
    }

    @Test fun archiveRecoveryMustPreserveIgnoredChangesAfterSnapshot() = runBlocking {
        val root = root()
        try {
            val source = File(root,"source").apply { mkdirs() }
            git(source,"init")
            File(source,"base").writeText("base")
            File(source,".gitignore").writeText("ignored.log\n")
            git(source,"add","."); git(source,"commit","-m","init")
            val delegate = InMemoryWorkspaceStore()
            val store = object : WorkspaceStore by delegate {
                var armed = false
                override suspend fun saveOperation(operation: WorkspaceOperation) {
                    delegate.saveOperation(operation)
                    if (armed && operation.kind==WorkspaceOperationKind.ARCHIVE && operation.state==WorkspaceOperationState.RUNNING && operation.sourceSnapshotId!=null) {
                        armed=false
                        throw java.io.IOException("simulate crash after snapshot journal")
                    }
                }
            }
            val manager = WorkspaceManager(store,InMemorySessionStore(),InMemoryHistoryStore(),worktreesRoot=File(root,"wt").absolutePath)
            val requested = manager.createWorkspace("p",source.absolutePath,selection=WorkspaceSelection(kind=WorkspaceKind.MANAGED_WORKTREE))
            manager.prepareWorkspace(requested.id,source.absolutePath,"prepare")
            val ws = delegate.getWorkspace(requested.id)!!
            File(ws.checkoutRoot,"ignored.log").writeText("snapshot-value")
            store.armed=true
            assertFailsWith<java.io.IOException> { manager.archiveWorkspace(ws.id,ArchiveWorkspaceInput(requestId="archive")) }
            val late = File(ws.checkoutRoot,"ignored.log").apply { writeText("external-newer-value") }
            val restarted = WorkspaceManager(delegate,InMemorySessionStore(),InMemoryHistoryStore(),worktreesRoot=File(root,"wt").absolutePath)
            restarted.scanAndRecoverPendingOperations("p")
            assertTrue(late.exists(),"Recovery must not force-remove an ignored file changed after its archive snapshot")
            assertEquals("external-newer-value",late.readText())
        } finally { root.deleteRecursively() }
    }
}
