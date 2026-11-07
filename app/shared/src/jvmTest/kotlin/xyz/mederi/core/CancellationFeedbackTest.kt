package xyz.mederi.core.bridge

import kotlin.test.*
import xyz.mederi.core.contract.ConversationStateReducer
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.*

class CancellationFeedbackTest {
    private fun snapshot() = ConversationState(
        conversation = Conversation("s", "p", "title", ConversationStatus.Working, 0, 0),
        messages = listOf(ChatMessage(
            id = "response", conversationId = "s", role = ChatRole.Assistant,
            blocks = listOf(ChatBlock.Text("text", "partial content")),
            createdAt = 100, completedAt = null, parentMessageId = null, model = null, agent = null,
            isStreaming = true, turnId = "turn", workStartedAt = 100,
        )),
        tokenUsage = TokenUsage(), cost = CostSummary(),
        errorMessage = "previous warning", errorId = "previous", errorDiagnostic = "old diagnostic",
        errorIsStreamInterrupted = true,
    )
    private fun payload(category: String) = mapOf(
        "errorCategory" to category, "error" to "operation ended", "errorId" to "id",
        "fullDiagnostic" to "diagnostic", "finalStatus" to "IDLE",
        "turnId" to "turn", "workCompletedAt" to "500",
    )

    @Test fun cancellationEndsWorkWithoutErrorOrDiagnosticActions() {
        val result = ConversationStateReducer.apply(snapshot(), CoreEvent(
            type = CoreEventType.MESSAGE_ERROR, sessionId = "s", payload = payload("CANCELLED")))
        assertEquals(ConversationStatus.Idle, result.conversation.status)
        assertNull(result.errorMessage); assertNull(result.errorId); assertNull(result.errorDiagnostic)
        assertFalse(result.errorIsStreamInterrupted)
        assertFalse(result.messages.single().isStreaming)
        assertEquals(500L, result.messages.single().workCompletedAt)
        assertEquals("partial content", (result.messages.single().blocks.single() as ChatBlock.Text).text)
        assertTrue(result.messages.single().isFinalAnswer != true)
    }

    @Test fun cancellationDoesNotReturnAsAnErrorAfterReloadingSnapshot() {
        val result = hydrateLastError(snapshot(), lastErrorFromPayload(payload("CANCELLED")))
        assertNull(result.errorMessage); assertNull(result.errorId); assertNull(result.errorDiagnostic)
        assertFalse(result.errorIsStreamInterrupted)
    }

    @Test fun realNetworkFailureRetainsDiagnosticAndRecoveryAcrossLiveAndReload() {
        val payload = payload("NETWORK") + ("failureMode" to "PREMATURE_CLOSE")
        val live = ConversationStateReducer.apply(snapshot(), CoreEvent(
            type = CoreEventType.MESSAGE_ERROR, sessionId = "s", payload = payload))
        val reload = hydrateLastError(snapshot(), lastErrorFromPayload(payload))
        for (result in listOf(live, reload)) {
            assertEquals("operation ended", result.errorMessage)
            assertEquals("diagnostic", result.errorDiagnostic)
            assertEquals("id", result.errorId)
            assertTrue(result.errorIsStreamInterrupted)
        }
    }
}
