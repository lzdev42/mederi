package xyz.mederi.core.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.CostSummary
import xyz.mederi.core.contract.models.TokenUsage

/**
 * BROWSER_TASK_REASONING_DELTA 聚合 + 新步/终态复位 的状态机测试。
 */
class BrowserReasoningReducerTest {

    private fun createInitialState(): ConversationState {
        val conv = Conversation(
            id = "conv-1",
            projectId = "p-1",
            title = "Test Conversation",
            status = ConversationStatus.Idle,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        return ConversationState(
            conversation = conv,
            messages = emptyList(),
            tokenUsage = TokenUsage(),
            cost = CostSummary()
        )
    }

    private fun deltaEvent(
        kind: String,
        content: String,
    ): CoreEvent = CoreEvent(
        type = CoreEventType.BROWSER_TASK_REASONING_DELTA,
        sessionId = "conv-1",
        payload = mapOf("kind" to kind, "content" to content)
    )

    @Test
    fun `delta accumulates into liveReasoning`() {
        val initial = createInitialState()
        val afterFoo = ConversationStateReducer.apply(initial, deltaEvent("reasoning", "foo"))
        val afterBar = ConversationStateReducer.apply(afterFoo, deltaEvent("reasoning", "bar"))
        assertEquals("foobar", afterBar.browserAutomation?.liveReasoning)
        assertEquals("", afterBar.browserAutomation?.liveText)
    }

    @Test
    fun `delta accumulates into liveText`() {
        val initial = createInitialState()
        val afterA = ConversationStateReducer.apply(initial, deltaEvent("text", "a"))
        val afterB = ConversationStateReducer.apply(afterA, deltaEvent("text", "b"))
        assertEquals("ab", afterB.browserAutomation?.liveText)
        assertEquals("", afterB.browserAutomation?.liveReasoning)
    }

    @Test
    fun `BROWSER_TASK_STEP resets live fields`() {
        val initial = createInitialState()
        val withDelta = ConversationStateReducer.apply(initial, deltaEvent("reasoning", "x"))
        assertEquals("x", withDelta.browserAutomation?.liveReasoning)

        val stepEvent = CoreEvent(
            type = CoreEventType.BROWSER_TASK_STEP,
            sessionId = "conv-1",
            payload = mapOf("taskId" to "bt-1", "step" to "1")
        )
        val afterStep = ConversationStateReducer.apply(withDelta, stepEvent)
        assertEquals("", afterStep.browserAutomation?.liveReasoning)
        assertEquals("", afterStep.browserAutomation?.liveText)
    }

    @Test
    fun `terminal event resets live fields`() {
        val initial = createInitialState()
        val withDelta = ConversationStateReducer.apply(initial, deltaEvent("reasoning", "x"))
        assertEquals("x", withDelta.browserAutomation?.liveReasoning)

        val completedEvent = CoreEvent(
            type = CoreEventType.BROWSER_TASK_COMPLETED,
            sessionId = "conv-1",
            payload = mapOf("taskId" to "bt-1")
        )
        val afterCompleted = ConversationStateReducer.apply(withDelta, completedEvent)
        assertEquals("", afterCompleted.browserAutomation?.liveReasoning)
        assertEquals("", afterCompleted.browserAutomation?.liveText)
    }
}
