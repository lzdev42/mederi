package xyz.mederi.ui

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.host.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspacePresentationStateTest {
    private fun exercise(block: suspend TestScope.(WorkspaceViewModel, AppState, MutableMap<String, MutableSharedFlow<ConversationState>>) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val flows = mutableMapOf<String, MutableSharedFlow<ConversationState>>()
        val core = object : AiCore by MockAiCore() {
            override fun observeConversation(conversationId: String) = flows.getOrPut(conversationId) { MutableSharedFlow(replay = 1) }
            override suspend fun sendMessage(conversationId: String, input: xyz.mederi.core.contract.dto.ChatPromptInput) = Result.success(Unit)
        }
        val app = AppState(core, InMemoryPreferencesStore(), backgroundScope)
        val store = ViewModelStore(); val vm = WorkspaceViewModel(app); store.put("reader", vm)
        try { runCurrent(); block(vm, app, flows) } finally { store.clear(); Dispatchers.resetMain() }
    }
    private fun text(vm: WorkspaceViewModel, id: String, body: String = id) = vm.openTextInExtension(id, body, id = id)
    private fun snapshot(id: String) = ConversationState(Conversation(id, "project", "title", ConversationStatus.Idle, 1, 1), emptyList(), TokenUsage(), cost = CostSummary())

    @Test fun immutableReaderStateUpsertsInPlaceAndRejectsUnknownSelection() {
        val a = ArtifactItem.Text("a", "A", "old")
        val first = ReaderPresentationState().open(a)
        val second = first.open(ArtifactItem.Image("b", "B", "image")).open(a.copy(content = "new"))
        assertEquals(listOf("a", "b"), second.items.map { it.id }); assertEquals("a", second.activeId)
        assertEquals("old", (first.items.single() as ArtifactItem.Text).content)
        assertSame(second, second.select("missing")); assertEquals("b", second.select("b").activeId)
    }

    @Test fun sameDocumentIdIsIndependentAcrossSessionsAndSelectionRestores() = exercise { vm, _, _ ->
        vm.attach("a"); text(vm, "same", "a text"); text(vm, "other"); vm.openArtifact("same")
        vm.attach("b"); assertTrue(vm.artifactItems.isEmpty()); assertNull(vm.activeArtifactId)
        text(vm, "same", "b text"); vm.openImageInExtension("Image", "image://b")
        vm.attach("a"); assertEquals("same", vm.activeArtifactId)
        assertEquals("a text", (vm.readerPanelState.activeItem as ArtifactItem.Text).content)
        vm.openArtifact("not-in-a"); assertEquals("same", vm.activeArtifactId)
        vm.attach("b"); assertIs<ArtifactItem.Image>(vm.readerPanelState.activeItem)
        vm.openArtifact("same"); assertEquals("b text", (vm.readerPanelState.activeItem as ArtifactItem.Text).content)
    }

    @Test fun newConversationReadersAreScopedByProjectAndSeparateFromRealSession() = exercise { vm, app, _ ->
        app.selectProject("p1"); runCurrent(); text(vm, "same", "draft one")
        app.selectProject("p2"); runCurrent(); assertTrue(vm.artifactItems.isEmpty()); text(vm, "same", "draft two")
        vm.attach("session"); assertTrue(vm.artifactItems.isEmpty()); text(vm, "same", "session")
        vm.attach(null); assertEquals("draft two", (vm.readerPanelState.activeItem as ArtifactItem.Text).content)
        app.selectProject("p1"); runCurrent(); assertEquals("draft one", (vm.readerPanelState.activeItem as ArtifactItem.Text).content)
    }

    @Test fun coreSnapshotsAndExplicitDockCloseDoNotOverwriteReaderSelection() = exercise { vm, _, flows ->
        vm.attach("a"); runCurrent(); flows.getValue("a").emit(snapshot("a")); runCurrent()
        text(vm, "a"); text(vm, "b"); vm.openArtifact("a")
        vm.toggleDockPanel(RightDockPanel.ARTIFACTS); assertNull(vm.activeDockPanel)
        flows.getValue("a").emit(snapshot("a").copy(requestCount = 7)); runCurrent()
        assertEquals("a", vm.activeArtifactId); assertNull(vm.activeDockPanel)
        vm.attach("b"); vm.attach("a"); assertEquals("a", vm.activeArtifactId); assertNull(vm.activeDockPanel)
    }

    @Test fun duplicateImagesReuseOneTabAndSameTitleDifferentImagesHaveDistinctIds() = exercise { vm, _, _ ->
        vm.openImageInExtension("Image", "one"); val one = vm.activeArtifactId
        vm.openImageInExtension("Image", "two"); assertNotEquals(one, vm.activeArtifactId)
        vm.openImageInExtension("Image", "one"); assertEquals(one, vm.activeArtifactId); assertEquals(2, vm.artifactItems.size)
    }

    @Test fun successfulFirstSendMovesDraftReaderToCreatedSessionWithoutLeakingToNextDraft() = exercise { vm, app, _ ->
        app.aiCore.initialize(); app.hydrate(); runCurrent()
        app.selectProject("proj_1"); app.selectConversation(null); runCurrent()
        app.selectModel(app.availableModels.value.first()); runCurrent()
        text(vm, "draft-doc", "opened before sending")
        vm.send("hello"); runCurrent()
        val created = assertNotNull(vm.conversationId)
        assertEquals("draft-doc", vm.activeArtifactId)
        assertEquals("opened before sending", (vm.readerPanelState.activeItem as ArtifactItem.Text).content)
        app.selectConversation(null); runCurrent(); assertTrue(vm.artifactItems.isEmpty())
        app.selectConversation(created); runCurrent(); assertEquals("draft-doc", vm.activeArtifactId)
    }

    @Test fun pendingConfirmationCannotCrossSessionOrSurviveNewDocumentIntent() = exercise { vm, app, _ ->
        app.fileTreeProvider = object : ProjectFileTreeProvider {
            override fun rootNode() = FileNode("root", "/root", true)
            override fun listChildren(dirPath: String) = emptyList<FileNode>()
            override fun readText(path: String): String? = null
            override fun isDirectory(path: String) = true
        }
        vm.attach("a"); vm.openFile("directory"); assertNotNull(vm.pendingFileOpen)
        vm.attach("b"); assertNull(vm.pendingFileOpen)
        vm.attach("a"); assertNull(vm.pendingFileOpen)
        vm.openFile("directory"); text(vm, "text"); assertNull(vm.pendingFileOpen)
    }

    @Test fun lateFileReadKeepsCapturedSourceAndCannotOpenInAnotherProject() = exercise { vm, app, _ ->
        val started = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        var secondReads = 0
        fun source(read: () -> String) = object : ProjectFileTreeProvider {
            override fun rootNode() = FileNode("root", "/root", true)
            override fun listChildren(dirPath: String) = emptyList<FileNode>()
            override fun readText(path: String) = read()
        }
        app.selectProject("p1"); runCurrent()
        app.fileTreeProvider = source { started.countDown(); try { check(release.await(5, TimeUnit.SECONDS)); "old" } finally { done.countDown() } }
        vm.openFile("read.md"); runCurrent()
        try {
            withContext(Dispatchers.Default) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
            app.selectProject("p2"); app.fileTreeProvider = source { secondReads++; "wrong source" }; runCurrent()
            text(vm, "new", "new project")
            release.countDown()
            withContext(Dispatchers.Default) { assertTrue(done.await(5, TimeUnit.SECONDS)); delay(30) }; runCurrent()
            assertEquals(0, secondReads); assertEquals(listOf("new"), vm.artifactItems.map { it.id })
            assertEquals("new project", (vm.readerPanelState.activeItem as ArtifactItem.Text).content)
        } finally { release.countDown() }
    }
}
