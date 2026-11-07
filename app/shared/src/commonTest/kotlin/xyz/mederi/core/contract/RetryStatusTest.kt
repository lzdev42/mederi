package xyz.mederi.core.contract

import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.*
import xyz.mederi.ui.TurnStatus
import xyz.mederi.ui.deriveTurnStatus
import xyz.mederi.ui.parseRetryHint
import kotlin.test.*

class RetryStatusTest {
    @Test
    fun onlyRetryingDisplaysStatusBar() {
        assertEquals(listOf(TurnStatus.Retrying), TurnStatus.entries.filter { it.shouldDisplayInStatusBar })
    }

    @Test
    fun providerRecoveryClearsHintAndNextEpisodeStartsAtOne() {
        var state = ConversationState(
            conversation = Conversation("s", "p", "test", ConversationStatus.Working, 0, 0),
            messages = emptyList(), tokenUsage = TokenUsage(), cost = CostSummary(),
        )
        fun status(code: String, attempt: String = "1", scope: String = "provider") = CoreEvent(
            CoreEventType.STATUS, "s", payload = mapOf("scope" to scope, "code" to code,
                "attempt" to attempt, "message" to "rate limit", "delayMs" to "0"),
        )
        state = ConversationStateReducer.apply(state, status("RETRYING", "121"))
        assertEquals(TurnStatus.Retrying, deriveTurnStatus(state))
        assertEquals("121", parseRetryHint(state.statusHint)?.attempt)
        assertNotNull(parseRetryHint(state.statusHint)?.retryAtMillis)
        assertEquals(state, ConversationStateReducer.apply(state, status("RETRY_FINISHED", scope = "compaction")))
        state = ConversationStateReducer.apply(state, status("RETRY_FINISHED"))
        assertNull(state.statusHint)
        assertFalse(deriveTurnStatus(state).shouldDisplayInStatusBar)
        state = ConversationStateReducer.apply(state, status("RETRYING"))
        assertEquals("1", parseRetryHint(state.statusHint)?.attempt)
    }
}
