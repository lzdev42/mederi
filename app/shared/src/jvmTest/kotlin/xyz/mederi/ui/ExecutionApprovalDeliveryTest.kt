package xyz.mederi.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.components.ExecutionApprovalCard
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ExecutionApprovalDeliveryTest {
    private fun request(id: String = "request-1", conversation: String = "conversation-1") = ExecutionApprovalRequest(
        id, conversation, "child-1", "bash", command = "curl https://example.com", cwd = "/workspace",
        access = "network",
    )
    private fun snapshot(requests: List<ExecutionApprovalRequest>) = ConversationState(
        conversation = Conversation("conversation-1", "project", "Title", ConversationStatus.WaitingUser, 1, 1),
        messages = emptyList(), tokenUsage = TokenUsage(), cost = CostSummary(), pendingExecutionApprovals = requests,
    )
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5000) { while (!predicate()) delay(10) }

    @Test
    fun allLiveRequestsRemainOutsideWorkTraceAndOtherConversationsAreExcluded() {
        val requests = listOf(request(), request("request-2"), request("foreign", "conversation-2"))
        val state = snapshot(requests)
        val items = computeChatItems(emptyList(), isWorking = false, snapshot = state)
        val cards = items.filterIsInstance<ChatListItem.ExecutionApproval>()
        assertEquals(listOf("request-1", "request-2"), cards.map { it.request.id })
        assertEquals(cards.size, cards.map { it.key }.distinct().size)
        assertTrue(items.none { it is ChatListItem.WorkTraceBlock })
        assertTrue(computeChatItems(emptyList(), false, snapshot(emptyList())).isEmpty())
    }

    @Test
    fun cardDisplaysExactOperationAndOnlyOffersOneShotActions() = runComposeUiTest {
        val actions = mutableListOf<Boolean>()
        var allow = ""; var deny = ""
        val request = request().copy(paths = listOf("/workspace/a file"))
        setContent { AppTheme {
            allow = stringResource(Res.string.execution_approval_allow_once)
            deny = stringResource(Res.string.execution_approval_deny)
            ExecutionApprovalCard(request, false, null, { actions += it }, Modifier.width(460.dp))
        } }
        onNodeWithText(request.command!!).assertIsDisplayed()
        onNodeWithText("/workspace/a file").assertIsDisplayed()
        onNodeWithText(allow).performClick()
        onNodeWithText(deny).performClick()
        runOnIdle { assertEquals(listOf(true, false), actions) }
    }

    @Test
    fun pendingSubmissionDisablesBothActionsAndFailureRemainsVisible() = runComposeUiTest {
        var allow = ""; var deny = ""
        setContent { AppTheme {
            allow = stringResource(Res.string.execution_approval_allow_once)
            deny = stringResource(Res.string.execution_approval_deny)
            ExecutionApprovalCard(request(), true, "submission failed", { error("must stay disabled") })
        } }
        onNodeWithText(allow).assertIsNotEnabled()
        onNodeWithText(deny).assertIsNotEnabled()
        onNodeWithText("submission failed").assertIsDisplayed()
    }

    @Test
    fun submissionUsesRequestOwnerRejectsDoubleClickAndFailureAllowsRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val results = Channel<Result<Unit>>(Channel.UNLIMITED)
        val calls = CopyOnWriteArrayList<Triple<String, String, Boolean>>()
        val api = object : AiCore by MockAiCore() {
            override suspend fun resolveExecutionApproval(conversationId: String, requestId: String, approved: Boolean): Result<Unit> {
                calls += Triple(conversationId, requestId, approved)
                return results.receive()
            }
        }
        val vm = WorkspaceViewModel(AppState(api, InMemoryPreferencesStore(), scope))
        val store = ViewModelStore(); store.put("vm", vm)
        val old = request(conversation = "old-owner")
        try {
            vm.conversationId = "currently-open"
            vm.resolveExecutionApproval(old, true)
            vm.resolveExecutionApproval(old, true)
            until { calls.size == 1 }
            assertEquals(Triple("old-owner", old.id, true), calls.single())
            vm.conversationId = "another-conversation"
            results.send(Result.failure(IllegalStateException("approval-failed")))
            until { !vm.isExecutionApprovalSubmitting(old) }
            assertEquals(listOf("approval-failed"), vm.executionApprovalError(old)?.args)
            assertNull(vm.executionApprovalError(old.copy(conversationId = "another-conversation")))
            vm.resolveExecutionApproval(old, false)
            until { calls.size == 2 }
            assertEquals(Triple("old-owner", old.id, false), calls.last())
            results.send(Result.success(Unit))
            until { vm.executionApprovalError(old) == null }
            assertTrue(vm.isExecutionApprovalSubmitting(old))
        } finally { store.clear(); scope.cancel(); results.close() }
    }
}
