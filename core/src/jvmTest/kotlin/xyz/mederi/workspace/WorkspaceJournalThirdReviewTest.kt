package xyz.mederi.workspace

import kotlinx.coroutines.runBlocking
import xyz.mederi.store.*
import xyz.mederi.domain.model.*
import java.io.File
import java.util.UUID
import kotlin.test.*

class WorkspaceJournalThirdReviewTest {
    private val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it,"gradlew").exists() }
    private fun git(dir:File,vararg args:String):String {
        val p=ProcessBuilder(listOf("git","-c","user.name=Test","-c","user.email=t@test.invalid")+args).directory(dir).redirectErrorStream(true).start()
        val out=p.inputStream.bufferedReader().readText(); check(p.waitFor()==0) { out }; return out.trim()
    }
    @Test fun materializeCrashMustNotMarkUnrolledTargetReady() = runBlocking {
        val root=File(repo,"temp/worktree-review-round3/fixtures/journal-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source=File(root,"source").apply { mkdirs() }
            git(source,"init");git(source,"checkout","-b","main")
            File(source,"base").writeText("base");git(source,"add",".");git(source,"commit","-m","init")
            val sessions=InMemorySessionStore()
            val delegate=InMemoryWorkspaceStore(sessions)
            val store=object:WorkspaceStore by delegate {
                var armed=false
                override suspend fun saveOperation(operation:WorkspaceOperation) {
                    if(armed && operation.kind==WorkspaceOperationKind.HANDOFF && operation.phaseRecords.any { it.phase==WorkspacePhase.MATERIALIZE_TARGET }) {
                        armed=false
                        throw java.io.IOException("crash after materialization but before phase journal write")
                    }
                    delegate.saveOperation(operation)
                }
            }
            val manager=WorkspaceManager(store,sessions,InMemoryHistoryStore(),worktreesRoot=File(root,"wt").path)
            val now=java.time.Instant.now().toString()
            sessions.insert(Session(id="session",projectId="p",title="Review",status=SessionStatus.IDLE,agentMode=AgentMode.AUTONOMOUS,aiModel=null,reasoningLevel=null,createdAt=now,updatedAt=now))
            manager.createWorkspace("p",source.path,selection=WorkspaceSelection(kind=WorkspaceKind.LOCAL),sessionId="session")
            File(source,"feature.txt").writeText("carried-untracked-content")
            val preview=manager.previewHandoff("session",HandoffPreviewInput(targetKind=WorkspaceKind.MANAGED_WORKTREE))
            store.armed=true
            assertFailsWith<java.io.IOException> { manager.executeHandoff("session",HandoffInput(targetKind=WorkspaceKind.MANAGED_WORKTREE,previewToken=preview.token,requestId="handoff")) }
            val targetId=preview.target.workspace.id
            val target=delegate.getWorkspace(targetId)!!
            assertEquals("carried-untracked-content",File(target.checkoutRoot,"feature.txt").readText(),"Fixture must reach actual materialization")
            val restarted=WorkspaceManager(delegate,sessions,InMemoryHistoryStore(),worktreesRoot=File(root,"wt").path)
            restarted.scanAndRecoverPendingOperations("p")
            val after=delegate.getWorkspace(targetId)!!
            assertTrue(after.state!=WorkspaceState.READY || !File(after.checkoutRoot,"feature.txt").exists(),
                "Incomplete phase journal must not claim successful rollback/READY while transferred target files remain")
        } finally { root.deleteRecursively() }
    }
}
