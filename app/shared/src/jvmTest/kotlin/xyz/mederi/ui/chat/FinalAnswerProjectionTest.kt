package xyz.mederi.ui.chat

import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.ToolCallUi
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.computeChatItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinalAnswerProjectionTest {
    private fun assistant(
        id: String,
        blocks: List<ChatBlock>,
        final: Boolean? = false,
        streaming: Boolean = false,
        turnId: String? = "turn_1",
    ) = ChatMessage(
        id = id, conversationId = "session", role = ChatRole.Assistant, blocks = blocks,
        createdAt = 1L, completedAt = if (streaming) null else 2L,
        parentMessageId = null, model = null, agent = null,
        isStreaming = streaming, turnId = turnId, isFinalAnswer = final,
    )

    private fun text(id: String, value: String) = ChatBlock.Text(id, value)
    private fun tool(id: String = "tool") = ChatBlock.ToolCall(
        id = id, name = "read_file", state = ToolCallState.Completed(emptyMap(), "source"),
    )
    private fun topTexts(items: List<ChatListItem>) = items.filterIsInstance<ChatListItem.TextMessage>().filter { !it.isUser }
    private fun traces(items: List<ChatListItem>) = items.filterIsInstance<ChatListItem.WorkTraceBlock>()

    @Test
    fun `working reasoning tools and all messages stay inside one collapsed trace`() {
        val messages = listOf(
            assistant("read", listOf(ChatBlock.Reasoning("r", "inspect sources"), text("t", "checking"), tool())),
            assistant("candidate", listOf(text("analysis", "complete analysis")), streaming = true),
        )
        val items = computeChatItems(messages, isWorking = true, snapshot = null)
        assertTrue(topTexts(items).isEmpty())
        val trace = traces(items).single()
        assertTrue(trace.isRunning)
        assertEquals(1, trace.totalToolsCount)
        assertEquals(listOf("checking", "complete analysis"), trace.items.filterIsInstance<ChatListItem.TextMessage>().map { it.text })
        assertTrue(trace.items.any { it is ChatListItem.Reasoning })
    }

    @Test
    fun `final response retains analysis plan and last sentence while reasoning stays folded`() {
        val messages = listOf(
            assistant("read", listOf(text("pre", "progress"), tool())),
            assistant("answer", listOf(ChatBlock.Reasoning("r", "internal summary"), text("analysis", "full analysis"), text("plan", "full plan"), text("tail", "done")), final = true),
        )
        val items = computeChatItems(messages, isWorking = false, snapshot = null)
        assertEquals(listOf("full analysis", "full plan", "done"), topTexts(items).map { it.text })
        assertEquals("full analysis\n\nfull plan\n\ndone", items.filterIsInstance<ChatListItem.Footer>().single().lastMessageText)
        assertTrue(traces(items).single().items.any { it is ChatListItem.Reasoning })
        assertFalse(traces(items).single().isRunning)
    }

    @Test
    fun `later process message does not hide explicitly delivered answer`() {
        val items = computeChatItems(listOf(
            assistant("answer", listOf(text("analysis", "complete report")), final = true),
            assistant("tail", listOf(text("tail", "extra status"))),
        ), isWorking = false, snapshot = null)
        assertEquals(listOf("complete report"), topTexts(items).map { it.text })
        assertEquals(listOf("extra status"), traces(items).single().items.filterIsInstance<ChatListItem.TextMessage>().map { it.text })
    }

    @Test
    fun `idle or interrupted process is never promoted into final answer`() {
        val items = computeChatItems(listOf(
            assistant("partial", listOf(text("t", "partial result"))),
        ), isWorking = false, snapshot = null)
        assertTrue(topTexts(items).isEmpty())
        assertTrue(items.none { it is ChatListItem.Footer })
        assertEquals("partial result", traces(items).single().items.filterIsInstance<ChatListItem.TextMessage>().single().text)
    }

    @Test
    fun `legacy history keeps every block in its last complete response`() {
        val items = computeChatItems(listOf(
            assistant("read", listOf(text("pre", "progress"), tool()), final = null, turnId = null),
            assistant("answer", listOf(text("analysis", "full report"), text("tail", "done")), final = null, turnId = null),
        ), isWorking = false, snapshot = null)
        assertEquals(listOf("full report", "done"), topTexts(items).map { it.text })
    }

    @Test
    fun `steering preserves user boundary even when core work continues`() {
        val inserted = assistant("input", listOf(text("u", "also inspect tests"))).copy(role = ChatRole.User, pendingSteering = true)
        val items = computeChatItems(listOf(
            assistant("before", listOf(text("before", "reading source"), tool("a"))),
            inserted,
            assistant("after", listOf(text("after", "reading tests"), tool("b")), streaming = true),
        ), isWorking = true, snapshot = null)
        assertEquals(2, traces(items).size)
        assertEquals(2, traces(items).sumOf { it.totalToolsCount })
        assertFalse(traces(items).first().isRunning)
        assertTrue(traces(items).last().isRunning)
        assertTrue(topTexts(items).isEmpty())
        val user = items.filterIsInstance<ChatListItem.TextMessage>().single { it.isUser }
        assertEquals("also inspect tests", user.text)
        assertTrue(items.indexOf(traces(items).first()) < items.indexOf(user))
        assertTrue(items.indexOf(user) < items.indexOf(traces(items).last()))
    }

    @Test
    fun `artifact and surrounding text follow ownership of complete response`() {
        val content = "Introduction\n<artifact identifier=\"report\" title=\"Report\"># Analysis\nFindings</artifact>\nClosing note"
        val process = assistant("process", listOf(text("t", content)))
        val working = computeChatItems(listOf(process), isWorking = true, snapshot = null)
        assertTrue(working.none { it is ChatListItem.DocumentCard })
        assertTrue(traces(working).single().items.any { it is ChatListItem.DocumentCard })
        val completed = computeChatItems(listOf(process.copy(isFinalAnswer = true)), isWorking = false, snapshot = null)
        assertEquals(1, completed.filterIsInstance<ChatListItem.DocumentCard>().size)
        assertEquals(listOf("Introduction", "Closing note"), topTexts(completed).map { it.text.trim() })
    }

    @Test
    fun `image deliverable appears only after final confirmation`() {
        val image = ChatBlock.File("image", "result.png", "https://example.com/result.png", "image/png")
        val process = assistant("image_answer", listOf(text("t", "Image result"), image))
        assertTrue(topTexts(computeChatItems(listOf(process), false, null)).isEmpty())
        val final = topTexts(computeChatItems(listOf(process.copy(isFinalAnswer = true)), false, null)).single()
        assertEquals(listOf("https://example.com/result.png"), final.images)
    }
    @Test
    fun `pending questions stay actionable even before a tool block arrives`() {
        val question = xyz.mederi.core.contract.models.QuestionRequest(id = "q", conversationId = "session")
        val snapshot = xyz.mederi.core.contract.dto.ConversationState(
            conversation = xyz.mederi.core.contract.models.Conversation(
                id = "session", projectId = null, title = "Question",
                status = xyz.mederi.core.contract.models.ConversationStatus.WaitingUser,
                createdAt = 1L, updatedAt = 2L,
            ),
            messages = emptyList(),
            tokenUsage = xyz.mederi.core.contract.models.TokenUsage(),
            cost = xyz.mederi.core.contract.models.CostSummary(),
            pendingQuestion = question,
        )
        val items = computeChatItems(emptyList(), false, snapshot)
        assertEquals("q", items.filterIsInstance<ChatListItem.QuestionCard>().single().request.id)
    }

    @Test
    fun `child work keeps trace running after main response completes`() {
        val messages = listOf(assistant("waiting", listOf(text("t", "Waiting for the researcher"))))
        val snapshot = xyz.mederi.core.contract.dto.ConversationState(
            conversation = xyz.mederi.core.contract.models.Conversation(
                id = "session", projectId = null, title = "Research", status = xyz.mederi.core.contract.models.ConversationStatus.Idle,
                createdAt = 1L, updatedAt = 2L),
            messages = messages, tokenUsage = xyz.mederi.core.contract.models.TokenUsage(),
            cost = xyz.mederi.core.contract.models.CostSummary(),
            subagents = listOf(xyz.mederi.core.contract.models.SubagentState(
                agentId = "child", parentSessionId = "session", role = "RESEARCHER", modelId = "model", modelName = "Model",
                reasoningLevel = null, task = "Research", briefing = null, status = "RUNNING", startedAt = "2026-10-27T08:00:00Z")))
        val items = computeChatItems(messages, false, snapshot)
        assertTrue(traces(items).single().isRunning)
        assertTrue(topTexts(items).isEmpty())
    }

    @Test
    fun `dispatch stage reply and task strip stay visible before next user input`() {
        val dispatched = ToolCallUi("spawn", "subagent", ToolCallState.Completed(mapOf("action" to "SPAWN_RESEARCHER"), "dispatched"))
        val first = assistant("dispatch", listOf(ChatBlock.Reasoning("r", "inspect"),
            ChatBlock.ToolCall(dispatched.id, dispatched.name, dispatched.state)))
        val reply = assistant("stage", listOf(text("stage_text", "Researcher dispatched; waiting for the report.")), final = true)
        val input = assistant("user", listOf(text("user_text", "Also fix this UI issue"))).copy(role = ChatRole.User)
        val later = assistant("later", listOf(ChatBlock.Reasoning("r2", "new instruction")), streaming = true)
        val items = computeChatItems(listOf(first, reply, input, later), true, null)
        assertEquals("Researcher dispatched; waiting for the report.", topTexts(items).single().text)
        assertEquals(1, items.filterIsInstance<ChatListItem.SubagentCalls>().size)
        val userIndex = items.indexOfFirst { it is ChatListItem.TextMessage && it.isUser }
        assertTrue(items.indexOf(topTexts(items).single()) < userIndex)
        assertTrue(items.indexOf(traces(items).last()) > userIndex)
        assertEquals("new instruction", traces(items).last().items.filterIsInstance<ChatListItem.Reasoning>().single().text)
    }

}
