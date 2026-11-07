package xyz.mederi.core.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.dto.MessagesPage
import xyz.mederi.core.contract.models.*

/** 实时流、插话刷新和最终交付必须共享 Core 的响应消息边界。 */
class ResponseBoundaryReducerTest {
    private fun state(messages: List<ChatMessage> = emptyList()) = ConversationState(
        conversation = Conversation("s", "p", "test", ConversationStatus.Working, 0L, 0L),
        messages = messages,
        tokenUsage = TokenUsage(),
        cost = CostSummary(),
    )

    private fun delta(id: String, type: String, content: String = "") = CoreEvent(
        type = CoreEventType.MESSAGE_DELTA,
        sessionId = "s",
        messageId = id,
        payload = mapOf("type" to type, "content" to content, "turnId" to "turn"),
    )

    private fun ChatMessage.text() = blocks.filterIsInstance<ChatBlock.Text>().joinToString("") { it.text }

    @Test
    fun missingInputNotificationIsRecoveredBeforeFirstDeltaWithoutImportingAheadOfStreamText() = runTest {
        val input = ChatMessage("u", "s", ChatRole.User, listOf(ChatBlock.Text("ut", "request")),
            0, 0, null, null, null, turnId = "turn")
        val ahead = input.copy(id = "r1", role = ChatRole.Assistant,
            blocks = listOf(ChatBlock.Text("rt", "first second")), isFinalAnswer = false)
        for (firstType in listOf("response_started", "text", "reasoning")) {
            var reads = 0
            var current = ConversationStateReducer.applyWithRefresh(state(), delta("r1", firstType, "first")) {
                reads++
                MessagesPage(listOf(input, ahead), TokenUsage())
            }.single()
            assertEquals(listOf("u", "r1"), current.messages.map { it.id })
            assertEquals(if (firstType == "text") "first" else "", current.messages.last().text())
            current = ConversationStateReducer.applyWithRefresh(current, delta("r1", "text", " second")) {
                error("Existing response must not reread history on every delta")
            }.single()
            assertEquals(1, reads)
            assertEquals((if (firstType == "text") "first" else "") + " second", current.messages.last().text())
        }
    }

    @Test
    fun missedConsumedInterjectionIsInsertedAfterLiveResponseWithoutOverwritingItsContent() = runTest {
        val original = ChatMessage("u", "s", ChatRole.User, listOf(ChatBlock.Text("ut", "request")),
            0, 0, null, null, null, turnId = "turn")
        val live = ConversationStateReducer.apply(state(listOf(original)), delta("r1", "text", "live content"))
        val interjection = original.copy(id = "u2", blocks = listOf(ChatBlock.Text("it", "follow up")))
        val ahead = original.copy(id = "r2", role = ChatRole.Assistant,
            blocks = listOf(ChatBlock.Text("rt", "ahead content")))
        val pending = original.copy(id = "u3", pendingSteering = true)
        val refreshed = ConversationStateReducer.applyWithRefresh(live, delta("r2", "response_started")) {
            MessagesPage(listOf(original, live.messages.last().copy(blocks = emptyList()), interjection, ahead, pending), TokenUsage())
        }.single()
        assertEquals(listOf("u", "r1", "u2", "r2"), refreshed.messages.map { it.id })
        assertEquals("live content", refreshed.messages[1].text())
        assertTrue(refreshed.messages.last().blocks.isEmpty())
    }

    @Test
    fun firstResponseStillStreamsWhenInputRefreshFails() = runTest {
        val result = ConversationStateReducer.applyWithRefresh(state(), delta("r1", "text", "first")) {
            error("temporarily unavailable")
        }.single()
        assertEquals("first", result.messages.single().text())
    }

    @Test
    fun toolFirstResponseAlsoRestoresMissingInput() = runTest {
        val input = ChatMessage("u", "s", ChatRole.User, listOf(ChatBlock.Text("ut", "request")),
            0, 0, null, null, null, turnId = "turn")
        val event = CoreEvent(CoreEventType.TOOL_CALLED, "s", "r1",
            mapOf("toolCallId" to "tool", "tool" to "bash", "args" to "{}", "turnId" to "turn"))
        val result = ConversationStateReducer.applyWithRefresh(state(), event) {
            MessagesPage(listOf(input), TokenUsage())
        }.single()
        assertEquals(listOf("u", "r1"), result.messages.map { it.id })
        assertEquals("bash", result.messages.last().blocks.filterIsInstance<ChatBlock.ToolCall>().single().name)
    }

    @Test
    fun separateResponsesKeepTheirOwnTextAndOnlyCoreConfirmsDelivery() {
        var snapshot = state()
        snapshot = ConversationStateReducer.apply(snapshot, delta("r1", "response_started"))
        snapshot = ConversationStateReducer.apply(snapshot, delta("r1", "text", "正在读取"))
        snapshot = ConversationStateReducer.apply(snapshot, delta("r1", "response_completed"))
        snapshot = ConversationStateReducer.apply(snapshot, delta("r2", "response_started"))
        snapshot = ConversationStateReducer.apply(snapshot, delta("r2", "text", "完整分析"))
        snapshot = ConversationStateReducer.apply(snapshot, delta("r2", "reasoning", "摘要"))
        snapshot = ConversationStateReducer.apply(snapshot, delta("r2", "text", "补充说明"))
        snapshot = ConversationStateReducer.apply(snapshot, delta("r2", "response_completed"))
        assertEquals(listOf("r1", "r2"), snapshot.messages.map { it.id })
        assertEquals("正在读取", snapshot.messages[0].text())
        assertEquals("完整分析补充说明", snapshot.messages[1].text())
        assertTrue(snapshot.messages.all { it.isFinalAnswer == false })
        snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(
            type = CoreEventType.MESSAGE_COMPLETED,
            sessionId = "s",
            payload = mapOf("turnId" to "turn", "finalMessageId" to "r2"),
        ))
        assertFalse(snapshot.messages[0].isFinalAnswer!!)
        assertTrue(snapshot.messages[1].isFinalAnswer!!)
        assertEquals("完整分析补充说明", snapshot.messages[1].text())
    }

    @Test
    fun laterReplyPreservesEarlierDeliveredStageReplyInSameWork() {
        var snapshot = state()
        for (id in listOf("dispatch", "result")) {
            snapshot = ConversationStateReducer.apply(snapshot, delta(id, "response_started"))
            snapshot = ConversationStateReducer.apply(snapshot, delta(id, "text", id))
            snapshot = ConversationStateReducer.apply(snapshot, delta(id, "response_completed"))
            snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(
                type = CoreEventType.MESSAGE_COMPLETED, sessionId = "s",
                payload = mapOf("turnId" to "turn", "finalMessageId" to id),
            ))
        }
        assertTrue(snapshot.messages.all { it.isFinalAnswer == true })
    }

    @Test
    fun acceptedInputRefreshPreservesUnpersistedResponseAndWaitingInteraction() = runTest {
        val user = ChatMessage(
            id = "u1", conversationId = "s", role = ChatRole.User,
            blocks = listOf(ChatBlock.Text("u1_text", "原请求")), createdAt = 0L,
            completedAt = 0L, parentMessageId = null, model = null, agent = null,
        )
        val accepted = user.copy(id = "u2", createdAt = 2L, pendingSteering = true)
        var snapshot = ConversationStateReducer.apply(state(listOf(user)), delta("r1", "text", "正在工作"))
        val question = QuestionRequest("q", "s", emptyList())
        snapshot = snapshot.copy(
            conversation = snapshot.conversation.copy(status = ConversationStatus.WaitingUser),
            pendingQuestion = question,
        )
        val refreshed = ConversationStateReducer.applyWithRefresh(snapshot, CoreEvent(
            type = CoreEventType.SESSION_UPDATED, sessionId = "s",
            payload = mapOf("inputAccepted" to "true"),
        )) {
            MessagesPage(listOf(user, accepted), TokenUsage())
        }.single()
        assertEquals(listOf("u1", "r1", "u2"), refreshed.messages.map { it.id })
        assertEquals("正在工作", refreshed.messages[1].text())
        assertTrue(refreshed.messages[1].isStreaming)
        assertTrue(refreshed.messages[2].pendingSteering)
        assertEquals(question, refreshed.pendingQuestion)
        assertEquals(ConversationStatus.WaitingUser, refreshed.conversation.status)
    }

    @Test
    fun toolEventsUpdateTheOwningResponseWithoutMovingItAfterAnInterjection() {
        var snapshot = ConversationStateReducer.apply(state(), delta("r1", "response_started"))
        snapshot = ConversationStateReducer.apply(snapshot, delta("r1", "response_completed"))
        val user = ChatMessage(
            id = "u2", conversationId = "s", role = ChatRole.User,
            blocks = listOf(ChatBlock.Text("u2_text", "补充")), createdAt = 1L,
            completedAt = 1L, parentMessageId = null, model = null, agent = null,
        )
        snapshot = snapshot.copy(messages = snapshot.messages + user)
        snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(
            type = CoreEventType.TOOL_CALLED, sessionId = "s", messageId = "r1",
            payload = mapOf("turnId" to "turn", "toolCallId" to "t", "tool" to "read_file", "args" to "{}"),
        ))
        assertEquals(listOf("r1", "u2"), snapshot.messages.map { it.id })
        assertEquals("read_file", snapshot.messages[0].blocks.filterIsInstance<ChatBlock.ToolCall>().single().name)
        assertFalse(snapshot.messages[0].isStreaming)
    }

    @Test
    fun interruptedResponseCannotBecomeFinalEvenWithACandidateId() {
        var snapshot = ConversationStateReducer.apply(state(), delta("r1", "text", "半截"))
        snapshot = ConversationStateReducer.apply(snapshot, delta("r1", "response_completed"))
        snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(
            type = CoreEventType.MESSAGE_COMPLETED, sessionId = "s",
            payload = mapOf("turnId" to "turn", "finalMessageId" to "r1", "failureMode" to "PREMATURE_CLOSE"),
        ))
        assertFalse(snapshot.messages.single().isFinalAnswer!!)
        assertFalse(snapshot.messages.single().isStreaming)
    }
    @Test
    fun workClockSurvivesResponseBoundariesAndFreezesOnlyOnCoreTerminal() {
        var snapshot = state()
        fun timed(id: String, type: String) = delta(id, type).let { it.copy(payload = it.payload + ("workStartedAt" to "1000")) }
        snapshot = ConversationStateReducer.apply(snapshot, timed("r1", "response_started"))
        snapshot = ConversationStateReducer.apply(snapshot, timed("r1", "response_completed"))
        snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(type = CoreEventType.MESSAGE_COMPLETED, sessionId = "s",
            payload = mapOf("turnId" to "turn", "workStartedAt" to "1000")))
        assertEquals(1000L, snapshot.messages.single().workStartedAt)
        assertEquals(null, snapshot.messages.single().workCompletedAt, "子代理等待阶段没有终点")
        snapshot = ConversationStateReducer.apply(snapshot, timed("r2", "response_started"))
        snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(type = CoreEventType.MESSAGE_COMPLETED, sessionId = "s",
            payload = mapOf("turnId" to "turn", "workStartedAt" to "1000", "workCompletedAt" to "9000", "finalMessageId" to "r2")))
        assertTrue(snapshot.messages.all { it.workStartedAt == 1000L && it.workCompletedAt == 9000L })
    }

    @Test
    fun errorClockFreezesOnlyTheMatchingWork() {
        val old = ChatMessage(id = "old", conversationId = "s", role = ChatRole.Assistant,
            blocks = emptyList(), createdAt = 1L, completedAt = 2L, parentMessageId = null, model = null, agent = null,
            turnId = "old-turn", workStartedAt = 10L, workCompletedAt = 20L)
        var snapshot = ConversationStateReducer.apply(state(listOf(old)), delta("new", "response_started"))
        snapshot = ConversationStateReducer.apply(snapshot, CoreEvent(type = CoreEventType.MESSAGE_ERROR, sessionId = "s",
            payload = mapOf("turnId" to "turn", "workStartedAt" to "1000", "workCompletedAt" to "2000", "finalStatus" to "IDLE")))
        assertEquals(20L, snapshot.messages.first().workCompletedAt)
        assertEquals(2000L, snapshot.messages.last().workCompletedAt)
    }

}
