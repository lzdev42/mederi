package xyz.mederi.core.contract

import kotlinx.serialization.json.Json
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionApprovalReducerTest {
    private fun state() = ConversationState(
        conversation = Conversation("s", "p", "test", ConversationStatus.Working, 0, 0),
        messages = emptyList(), tokenUsage = TokenUsage(), cost = CostSummary(),
    )

    private fun requested(id: String, executionSessionId: String = "s") = CoreEvent(
        CoreEventType.EXECUTION_APPROVAL_REQUESTED, "s", payload = mapOf("request" to Json.encodeToString(
            ExecutionApprovalRequest.serializer(), ExecutionApprovalRequest(id, "s", executionSessionId,
                "bash", command = "git status", cwd = "/project", access = "network")
        )),
    )

    private fun resolved(id: String) = CoreEvent(CoreEventType.EXECUTION_APPROVAL_RESOLVED, "s",
        payload = mapOf("requestId" to id, "status" to "APPROVED"))

    @Test
    fun parallelRequestsSurviveAnotherResolutionAndParentCompletion() {
        var snapshot = ConversationStateReducer.apply(state(), requested("a"))
        snapshot = ConversationStateReducer.apply(snapshot, requested("b", "sub"))
        snapshot = ConversationStateReducer.apply(snapshot, requested("b", "sub"))
        assertEquals(listOf("a", "b"), snapshot.pendingExecutionApprovals.map { it.id })
        snapshot = ConversationStateReducer.apply(snapshot, resolved("a"))
        assertEquals(ConversationStatus.WaitingUser, snapshot.conversation.status)
        snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(CoreEventType.MESSAGE_COMPLETED, "s"))
        assertEquals(listOf("b"), snapshot.pendingExecutionApprovals.map { it.id })
        assertEquals(ConversationStatus.WaitingUser, snapshot.conversation.status)
        snapshot = ConversationStateReducer.apply(snapshot, resolved("b"))
        assertTrue(snapshot.pendingExecutionApprovals.isEmpty())
        assertEquals(ConversationStatus.Working, snapshot.conversation.status)
    }

    @Test
    fun questionAndPlanResolutionDoNotDismissExecutionApproval() {
        var snapshot = ConversationStateReducer.apply(state(), requested("a"))
        snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(CoreEventType.QUESTION_REQUESTED, "s",
            payload = mapOf("questionId" to "q", "questions" to "[]")))
        snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(CoreEventType.QUESTION_RESOLVED, "s"))
        assertEquals(ConversationStatus.WaitingUser, snapshot.conversation.status)
        snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(CoreEventType.PLAN_APPROVAL_REQUESTED, "s",
            payload = mapOf("planId" to "p")))
        snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(CoreEventType.PLAN_APPROVAL_RESOLVED, "s",
            payload = mapOf("planId" to "p", "approved" to "true")))
        assertEquals(ConversationStatus.WaitingUser, snapshot.conversation.status)
        assertEquals(1, snapshot.pendingExecutionApprovals.size)
    }

    @Test
    fun malformedOrForeignRequestsAndStaleResolutionAreIgnored() {
        val initial = state()
        assertEquals(initial, ConversationStateReducer.apply(initial, CoreEvent(
            CoreEventType.EXECUTION_APPROVAL_REQUESTED, "s", payload = mapOf("request" to "invalid"))))
        assertEquals(initial, ConversationStateReducer.apply(initial, resolved("old")))
        val raw = Json.encodeToString(ExecutionApprovalRequest.serializer(),
            ExecutionApprovalRequest("foreign", "another", "another", "bash", access = "network"))
        assertEquals(initial, ConversationStateReducer.apply(initial,
            CoreEvent(CoreEventType.EXECUTION_APPROVAL_REQUESTED, "s", payload = mapOf("request" to raw))))
    }

    @Test
    fun sidebarTrackerKeepsParallelAndOtherInteractionWaits() {
        val tracker = PendingInteractionTracker()
        tracker.accept(requested("a"))
        tracker.accept(requested("b", "sub"))
        tracker.accept(CoreEvent(CoreEventType.MESSAGE_COMPLETED, "s"))
        tracker.accept(resolved("a"))
        assertTrue(tracker.isWaiting("s"))
        tracker.accept(CoreEvent(CoreEventType.QUESTION_REQUESTED, "s", payload = mapOf("questionId" to "q")))
        tracker.accept(resolved("b"))
        assertTrue(tracker.isWaiting("s"))
        tracker.accept(CoreEvent(CoreEventType.QUESTION_RESOLVED, "s"))
        assertFalse(tracker.isWaiting("s"))
    }

    @Test
    fun oldSnapshotDefaultsToNoLiveApprovalsAndCurrentSnapshotRoundTrips() {
        val json = Json { encodeDefaults = false }
        val raw = json.encodeToString(ConversationState.serializer(), state())
        assertFalse(raw.contains("pendingExecutionApprovals"))
        assertTrue(json.decodeFromString<ConversationState>(raw).pendingExecutionApprovals.isEmpty())
        val pending = ConversationStateReducer.apply(state(), requested("a", "sub"))
        assertEquals(pending, json.decodeFromString<ConversationState>(json.encodeToString(ConversationState.serializer(), pending)))
    }

    @Test
    fun mainCancellationClearsOwnQuestionButKeepsChildApprovalWaiting() {
        val question = CoreEvent(CoreEventType.QUESTION_REQUESTED, "s", payload = mapOf("questionId" to "q", "questions" to "[]"))
        val tracker = PendingInteractionTracker()
        tracker.accept(question)
        tracker.accept(CoreEvent(CoreEventType.MESSAGE_ERROR, "s"))
        assertFalse(tracker.isWaiting("s"))
        tracker.accept(requested("child", "sub"))
        tracker.accept(question)
        tracker.accept(CoreEvent(CoreEventType.MESSAGE_ERROR, "s"))
        assertTrue(tracker.isWaiting("s"))
        var snapshot = ConversationStateReducer.apply(state(), question)
        snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(CoreEventType.MESSAGE_ERROR, "s"))
        assertTrue(snapshot.pendingQuestion == null)
        assertEquals(ConversationStatus.Error, snapshot.conversation.status)
        snapshot = ConversationStateReducer.apply(snapshot, requested("child", "sub"))
        snapshot = ConversationStateReducer.apply(snapshot, question)
        snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(CoreEventType.MESSAGE_ERROR, "s"))
        assertTrue(snapshot.pendingQuestion == null)
        assertEquals(ConversationStatus.WaitingUser, snapshot.conversation.status)
        assertEquals(listOf("child"), snapshot.pendingExecutionApprovals.map { it.id })
    }
}
