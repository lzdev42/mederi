package xyz.mederi.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.WorkTraceEntry
import xyz.mederi.ui.computeChatItems

class WorkTraceDetailsProjectionTest {
    @Test
    fun `progress and reasoning preserve order around mixed groups`() {
        val items = computeChatItems(
            listOf(
                response("inspect", tool("read", "read_file"), note("note", "Check the existing tests."), tool("search")),
                response("verify", ChatBlock.Text("progress", "The first check passed."), tool("test"),
                    ChatBlock.Reasoning("reasoning", "Try the second check."), tool("retry")),
                response("final", ChatBlock.Text("answer", "Both checks passed."), final = true),
            ),
            isWorking = false,
            snapshot = null,
        )

        val work = items.filterIsInstance<ChatListItem.WorkTraceBlock>().single()
        assertTrue(work.defaultExpanded)
        assertEquals(4, work.totalToolsCount)
        val entries = work.entries
        assertEquals(listOf("inspect_read", "verify_progress", "verify_test", "verify_reasoning", "verify_retry"),
            entries.map { it.key })
        val first = assertIs<WorkTraceEntry.Details>(entries.first())
        assertEquals(listOf("inspect_read", "inspect_note", "inspect_search"), first.items.map { it.key })
        assertEquals("Check the existing tests.", assertIs<ChatListItem.TextMessage>(first.items[1]).text)
        assertEquals("The first check passed.",
            assertIs<ChatListItem.TextMessage>(assertIs<WorkTraceEntry.Step>(entries[1]).item).text)
        assertIs<ChatListItem.Reasoning>(assertIs<WorkTraceEntry.Step>(entries[3]).item)
        assertEquals("Both checks passed.", items.filterIsInstance<ChatListItem.TextMessage>().single().text)
    }

    @Test
    fun `dispatch and work boundary keep tool groups separate`() {
        val dispatch = ChatBlock.ToolCall("dispatch", "subagent",
            ToolCallState.Completed(mapOf("action" to "SPAWN", "task" to "inspect"), "dispatched"))
        val items = computeChatItems(
            listOf(response("one", tool("a"), dispatch, tool("b")), response("two", tool("c"), turn = "next")),
            isWorking = false,
            snapshot = null,
        )
        val work = items.filterIsInstance<ChatListItem.WorkTraceBlock>().single()
        assertEquals(1, items.filterIsInstance<ChatListItem.SubagentCalls>().size)
        assertEquals(listOf(listOf("one_a"), listOf("one_b"), listOf("two_c")),
            work.entries.map { entry ->
                assertIs<WorkTraceEntry.Details>(entry).items.map { it.key }
            })
    }

    @Test
    fun `streamed explicit note preserves group key without showing the marker`() {
        val partial = response("call", ChatBlock.Text("note", "<!-- mederi:work-"), tool("search"))
            .copy(isStreaming = true)
        val complete = partial.copy(blocks = listOf(note("note", "Check the tests."), tool("search")))

        val groups = listOf(partial, complete).map { message ->
            val work = computeChatItems(listOf(message), true, null)
                .filterIsInstance<ChatListItem.WorkTraceBlock>().single()
            assertIs<WorkTraceEntry.Details>(work.entries.single())
        }
        assertEquals(listOf("call_note", "call_note"), groups.map { it.key })
        assertEquals(listOf("call_search"), groups.first().items.map { it.key })
        assertEquals("Check the tests.", assertIs<ChatListItem.TextMessage>(groups.last().items.first()).text)
    }

    @Test
    fun `only explicit notes can hide and attached images stay visible`() {
        val final = response("final", ChatBlock.Text("answer", "Done."), final = true)
        fun workWith(first: ChatMessage) = computeChatItems(listOf(first, final), false, null)
            .filterIsInstance<ChatListItem.WorkTraceBlock>().single()

        assertFalse(workWith(response("notes", note("note", "Check the tests."))).defaultExpanded)
        assertTrue(workWith(response("notes", ChatBlock.Text("note", "Check the tests."))).defaultExpanded)
        val imageWork = workWith(response("notes", note("note", "See screenshot."),
            ChatBlock.File("image", "test.png", "https://example.invalid/test.png", "image/png")))
        assertTrue(imageWork.defaultExpanded)
        val step = assertIs<WorkTraceEntry.Step>(imageWork.entries.single())
        val message = assertIs<ChatListItem.TextMessage>(step.item)
        assertEquals("See screenshot.", message.text)
        assertEquals(listOf("https://example.invalid/test.png"), message.images)
    }

    private fun response(id: String, vararg blocks: ChatBlock, turn: String = "work", final: Boolean = false) =
        ChatMessage(id, "s", ChatRole.Assistant, blocks.toList(), 10, null, null, null, null,
            turnId = turn, isFinalAnswer = final)

    private fun note(id: String, body: String) = ChatBlock.Text(id, "<!-- mederi:work-note -->\n$body")

    private fun tool(id: String, name: String = "bash") =
        ChatBlock.ToolCall(id, name, ToolCallState.Completed(emptyMap(), "done"))
}
