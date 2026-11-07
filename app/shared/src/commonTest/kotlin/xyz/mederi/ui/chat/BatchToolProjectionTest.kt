package xyz.mederi.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi
import xyz.mederi.ui.chat.ToolActionKind
import xyz.mederi.ui.chat.groupToolCallsByAction

class BatchToolProjectionTest {
    private val input = mapOf("calls" to """[
        {"tool":"read_file","parameters":{"path":"src/First.kt","offset":685,"max_lines":40}},
        {"tool":"read_file","parameters":{"path":"src/Second.kt","offset":720,"max_lines":40}},
        {"tool":"bash","parameters":{"command":"git diff --stat"}}
    ]""")
    private val output = """[
        {"index":2,"tool":"bash","output":"1 file changed"},
        {"index":0,"tool":"read_file","output":"first line\nsecond line"},
        {"index":1,"tool":"read_file","error":"Permission denied"}
    ]"""

    @Test
    fun completedBatchShowsIndividualRowsAndEachOwnOutputOrError() {
        val call = ToolCallUi("batch-1", "batch", ToolCallState.Completed(input, output))
        val groups = groupToolCallsByAction(listOf(call))
        assertEquals(listOf(ToolActionKind.READ, ToolActionKind.READ, ToolActionKind.COMMAND), groups.map { it.kind })
        val rows = groups.flatMap { it.calls }
        assertEquals(listOf("read_file", "read_file", "bash"), rows.map { it.name })
        assertEquals("685", assertIs<ToolCallState.Completed>(rows[0].state).input["offset"])
        assertEquals("first line\nsecond line", assertIs<ToolCallState.Completed>(rows[0].state).output)
        assertEquals("Permission denied", assertIs<ToolCallState.Failed>(rows[1].state).error)
        assertTrue(rows[1].isFailed)
        assertFalse(rows[0].isFailed)
        assertEquals("src/First.kt#L685-724", rows[0].target)
        assertEquals("git diff --stat", rows[2].target)
        assertEquals("1 file changed", assertIs<ToolCallState.Completed>(rows[2].state).output)
    }

    @Test
    fun runningAndCompletedRowsKeepStableIds() {
        val running = expandBatchToolCall(ToolCallUi("batch-1", "batch", ToolCallState.Running(input)))
        val completed = expandBatchToolCall(ToolCallUi("batch-1", "batch", ToolCallState.Completed(input, output)))
        assertEquals(3, running.size)
        assertTrue(running.all { it.state is ToolCallState.Running })
        assertEquals(running.map { it.id }, completed.map { it.id })
        val pending = expandBatchToolCall(ToolCallUi("batch-1", "batch", ToolCallState.Pending(input)))
        assertTrue(pending.all { it.state is ToolCallState.Pending })
    }

    @Test
    fun batchFailureRetainsOriginalErrorOnProjectedMembers() {
        val rows = expandBatchToolCall(ToolCallUi("batch-1", "batch", ToolCallState.Failed(input, "original failure")))
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.isFailed })
        assertTrue(rows.all { (it.state as ToolCallState.Failed).error == "original failure" })
    }

    @Test
    fun malformedOrMismatchedResultsPreserveOriginalDiagnostic() {
        for (raw in listOf("broken JSON", "[]", """[{"index":0,"tool":"bash","output":"wrong member"}]""")) {
            val call = ToolCallUi("batch-1", "batch", ToolCallState.Completed(input, raw))
            assertEquals(listOf(call), expandBatchToolCall(call))
        }
        val call = ToolCallUi("bad-input", "batch", ToolCallState.Running(mapOf("calls" to "[partial")))
        assertEquals(emptyList(), expandBatchToolCall(call))
    }

    @Test
    fun ordinaryCallsStayUnchangedAndEditMembersUseExistingEditRows() {
        val normal = ToolCallUi("read", "read_file", ToolCallState.Completed(mapOf("path" to "x.kt"), "text"))
        assertEquals(listOf(normal), expandBatchToolCall(normal))
        val edit = ToolCallUi("legacy", "batch", ToolCallState.Completed(
            mapOf("calls" to """[{"tool":"edit_file","parameters":{"path":"x.kt","original":"a","replacement":"b"}}]"""),
            """[{"index":0,"tool":"edit_file","output":"updated"}]"""))
        assertEquals(ToolActionKind.EDIT, groupToolCallsByAction(listOf(edit)).single().kind)
    }

    @Test
    fun chatProjectionCountsMembersAndDoesNotExposeBatchSummary() {
        val message = ChatMessage(
            id = "message", conversationId = "conversation", role = ChatRole.Assistant,
            blocks = listOf(ChatBlock.ToolCall("batch-1", "batch", ToolCallState.Completed(input, output))),
            createdAt = 1, completedAt = 2, parentMessageId = null, model = null, agent = null,
            isFinalAnswer = false,
        )
        val trace = computeChatItems(listOf(message), false, null).filterIsInstance<ChatListItem.WorkTraceBlock>().single()
        val tools = trace.items.filterIsInstance<ChatListItem.ToolCalls>().single()
        assertEquals(3, trace.totalToolsCount)
        assertEquals(3, tools.toolCalls.size)
        assertFalse(tools.toolSummary.contains("batch"))
        assertTrue(tools.hasFailedTool)
        assertTrue(tools.toolCalls.none { it.name == "batch" })
    }
}
