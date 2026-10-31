package xyz.mederi.ui

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.*
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.appstate.AppState
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceFeedbackIntegrationTest {
    private fun snapshot(id: String, error: String? = null) = ConversationState(
        Conversation(id, "proj_1", "title", ConversationStatus.Idle, 1, 1), emptyList(), TokenUsage(), cost = CostSummary(),
        errorMessage = error, errorDiagnostic = error?.let { "diagnostic-$it" }, errorId = error?.let { "id-$it" }, errorIsStreamInterrupted = error != null)
    private inner class Fake(val backend: MockAiCore) : AiCore by backend {
        val flows = mutableMapOf<String, MutableSharedFlow<ConversationState>>()
        var send: suspend (String) -> Result<Unit> = { Result.success(Unit) }
        var create: (suspend () -> Result<Conversation>)? = null
        var rollback: suspend () -> Result<Unit> = { Result.success(Unit) }
        override fun observeConversation(conversationId: String) = flows.getOrPut(conversationId) {
            MutableSharedFlow<ConversationState>(replay = 1).apply { tryEmit(snapshot(conversationId)) }
        }
        override suspend fun sendMessage(conversationId: String, input: ChatPromptInput) = send(conversationId)
        override suspend fun createConversation(projectId: String, agent: AgentOption?) = create?.invoke() ?: backend.createConversation(projectId, agent)
        override suspend fun rollbackToMessage(conversationId: String, messageId: String) = rollback()
    }
    private fun exercise(block: suspend TestScope.(WorkspaceViewModel, AppState, Fake) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val core = Fake(MockAiCore()); core.initialize()
        val app = AppState(core, InMemoryPreferencesStore(), backgroundScope); app.hydrate()
        val store = ViewModelStore(); val vm = WorkspaceViewModel(app); store.put("feedback", vm)
        try { runCurrent(); app.selectModel(app.availableModels.value.first()); app.selectConversation("a"); runCurrent(); block(vm, app, core) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun coreDismissalSurvivesSnapshotsAndSwitchesWhileNewIdentityReappears() = exercise { vm, app, core ->
        core.flows.getValue("a").emit(snapshot("a", "first")); runCurrent(); vm.showErrorDetail(); assertTrue(vm.isErrorDetailOpen)
        vm.clearError(); core.flows.getValue("a").emit(snapshot("a", "first").copy(requestCount = 3)); runCurrent(); assertNull(vm.error)
        app.selectConversation("b"); runCurrent(); assertFalse(vm.isStreamInterrupted); assertFalse(vm.isErrorDetailOpen)
        app.selectConversation("a"); runCurrent(); assertNull(vm.error)
        core.flows.getValue("a").emit(snapshot("a", "second")); runCurrent(); assertEquals(listOf("second"), vm.error?.args)
        assertTrue(vm.isStreamInterrupted); assertFalse(vm.isErrorDetailOpen)
    }
    @Test fun lateSendFailureIsStoredForOriginalSessionWithoutLeakingCoreDetails() = exercise { vm, app, core ->
        core.flows.getValue("a").emit(snapshot("a", "old core")); runCurrent()
        val gate = CompletableDeferred<Result<Unit>>(); core.send = { gate.await() }
        vm.send("first"); runCurrent(); app.selectConversation("b"); runCurrent()
        gate.complete(Result.failure(IllegalStateException("send rejected"))); runCurrent(); assertNull(vm.error)
        app.selectConversation("a"); runCurrent(); assertEquals(listOf("send rejected"), vm.error?.args)
        assertNull(vm.errorDiagnostic); assertNull(vm.errorId); assertFalse(vm.isStreamInterrupted)
    }
    @Test fun newerSendFeedbackCannotBeOverwrittenByEarlierResponse() = exercise { vm, _, core ->
        val old = CompletableDeferred<Result<Unit>>(); val fresh = CompletableDeferred<Result<Unit>>(); var calls = 0
        core.send = { if (++calls == 1) old.await() else fresh.await() }
        vm.send("one"); runCurrent(); vm.send("two"); runCurrent()
        fresh.complete(Result.failure(IllegalStateException("new failure"))); runCurrent()
        old.complete(Result.failure(IllegalStateException("old failure"))); runCurrent()
        assertEquals(listOf("new failure"), vm.error?.args)
    }
    @Test fun creationFailureBelongsToOriginalProjectDraft() = exercise { vm, app, core ->
        app.selectConversation(null); app.selectProject("proj_1"); runCurrent()
        val gate = CompletableDeferred<Result<Conversation>>(); core.create = { gate.await() }
        vm.send("draft"); runCurrent(); app.selectProject("proj_2"); runCurrent()
        gate.complete(Result.failure(IllegalStateException("create rejected"))); runCurrent(); assertNull(vm.error)
        app.selectProject("proj_1"); runCurrent(); assertEquals(listOf("create rejected"), vm.error?.args)
        assertFalse(vm.isStreamInterrupted)
    }
    @Test fun firstSendFailureAfterCreationBelongsToNewSession() = exercise { vm, app, core ->
        app.selectConversation(null); app.selectProject("proj_1"); runCurrent()
        val gate = CompletableDeferred<Result<Unit>>(); core.send = { gate.await() }
        vm.send("draft"); runCurrent(); val created = assertNotNull(vm.conversationId)
        app.selectConversation("b"); runCurrent(); gate.complete(Result.failure(IllegalStateException("first send rejected"))); runCurrent()
        assertNull(vm.error); app.selectConversation(created); runCurrent(); assertEquals(listOf("first send rejected"), vm.error?.args)
    }
    @Test fun rollbackFailureUsesOriginalOwnerAndSuccessCannotReplaceAnotherContextOrNewTyping() = exercise { vm, app, core ->
        val results = Channel<Result<Unit>>(Channel.UNLIMITED); core.rollback = { results.receive() }
        vm.rollbackMessage("a", "message", "original text"); runCurrent()
        app.selectConversation("b"); runCurrent(); vm.updateInputDraft(TextFieldValue("b draft"))
        results.send(Result.failure(IllegalStateException("rollback rejected"))); runCurrent()
        assertNull(vm.error); assertEquals("b draft", vm.inputDraft.text)
        app.selectConversation("a"); runCurrent(); assertEquals(listOf("rollback rejected"), vm.error?.args)
        vm.rollbackMessage("a", "message", "restored"); runCurrent()
        app.selectConversation("b"); runCurrent(); vm.updateInputDraft(TextFieldValue("new b"))
        results.send(Result.success(Unit)); runCurrent(); assertEquals("new b", vm.inputDraft.text)
        app.selectConversation("a"); runCurrent(); vm.rollbackMessage("a", "message", "restored"); runCurrent()
        vm.updateInputDraft(TextFieldValue("typed during rollback")); results.send(Result.success(Unit)); runCurrent()
        assertEquals("typed during rollback", vm.inputDraft.text)
        vm.rollbackMessage("a", "message", "restored"); runCurrent(); results.send(Result.success(Unit)); runCurrent()
        assertEquals("restored", vm.inputDraft.text)
    }
}
