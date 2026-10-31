package xyz.mederi.ui

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.appstate.AppState
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DiffWorkspaceIntegrationTest {
    private class Fake : AiCore by MockAiCore() {
        val reads = mutableListOf<Pair<String, String?>>()
        var read: suspend (String, String?) -> Result<List<FileDiff>> = { _, _ -> Result.success(emptyList()) }
        override fun observeConversation(conversationId: String) = flowOf(ConversationState(
            Conversation(conversationId, "project", "title", ConversationStatus.Idle, 1, 1), emptyList(), TokenUsage(), cost = CostSummary()))
        override suspend fun getFileDiffs(conversationId: String, messageId: String?): Result<List<FileDiff>> {
            reads += conversationId to messageId; return read(conversationId, messageId)
        }
    }
    private fun exercise(block: suspend TestScope.(WorkspaceViewModel, Fake) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val core = Fake(); val app = AppState(core, InMemoryPreferencesStore(), backgroundScope)
        val store = ViewModelStore(); val vm = WorkspaceViewModel(app); store.put("diff", vm)
        try { runCurrent(); vm.attach("a"); runCurrent(); block(vm, core) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun closedPanelCannotBeReopenedByLateResultAndReaderNavigationSupersedesQuery() = exercise { vm, core ->
        val first = CompletableDeferred<Result<List<FileDiff>>>(); val second = CompletableDeferred<Result<List<FileDiff>>>()
        core.read = { _, _ -> withContext(NonCancellable) { if (core.reads.size == 1) first.await() else second.await() } }
        vm.openDiff("turn", null); assertTrue(vm.showDiffPanel); runCurrent(); vm.closeDiff()
        first.complete(Result.success(listOf(FileDiff("old", "a", "b", 1, 1)))); runCurrent()
        assertNull(vm.activeDockPanel); assertTrue(vm.diffItems.isEmpty())
        vm.openDockPanel(RightDockPanel.DIFF); runCurrent()
        vm.openTextInExtension("Text", "body", id = "text")
        second.complete(Result.success(listOf(FileDiff("old2", "a", "b", 1, 1)))); runCurrent()
        assertEquals(RightDockPanel.ARTIFACTS, vm.activeDockPanel); assertEquals("text", vm.activeArtifactId)
        vm.attach(null); vm.openDockPanel(RightDockPanel.DIFF)
        assertTrue(vm.showDiffPanel); assertFalse(vm.diffPanelState.requested)
        val reads = core.reads.size; vm.refreshDiff(); runCurrent(); assertEquals(reads, core.reads.size)
    }
    @Test fun perTurnRefreshAndCachedSessionRestoreNeverChangeScopeOrSelection() = exercise { vm, core ->
        core.read = { id, _ -> Result.success(listOf(FileDiff("$id/one", "a", "b", 1, 1), FileDiff("$id/two", "a", "c", 1, 1))) }
        vm.openDiff("turn-a", "a/two"); runCurrent(); vm.refreshDiff(); runCurrent()
        assertEquals("a/two", vm.selectedDiffFilePath); assertEquals(listOf<Pair<String, String?>>("a" to "turn-a", "a" to "turn-a"), core.reads)
        vm.attach("b"); runCurrent(); assertTrue(vm.diffItems.isEmpty()); vm.openDiff("turn-b"); runCurrent()
        vm.attach("a"); runCurrent(); assertEquals("a/two", vm.selectedDiffFilePath)
        assertEquals("turn-a", vm.diffPanelState.messageId); assertEquals(3, core.reads.size)
        vm.refreshDiff(); runCurrent(); assertEquals("a" to "turn-a", core.reads.last())
    }
}
