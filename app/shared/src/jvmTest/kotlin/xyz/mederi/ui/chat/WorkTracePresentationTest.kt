package xyz.mederi.ui.chat

import kotlin.test.*
import xyz.mederi.ui.*
import xyz.mederi.core.contract.models.*

class WorkTracePresentationTest {
    @Test
    fun `wall clock advances during tools and freezes at authoritative completion`() {
        assertEquals(3_000L, workTraceElapsedMs(1_000, null, true, 4_000))
        assertEquals(8_000L, workTraceElapsedMs(1_000, null, true, 9_000))
        assertEquals(5_000L, workTraceElapsedMs(1_000, 6_000, false, 9_000))
        assertEquals(5_000L, workTraceElapsedMs(1_000, 6_000, false, 900_000))
        assertEquals(0L, workTraceElapsedMs(5_000, null, true, 4_000))
    }

    @Test
    fun `unknown times do not become fake durations`() {
        assertNull(workTraceElapsedMs(null, null, true, 5_000))
        assertNull(workTraceElapsedMs(0, null, true, 5_000))
        assertNull(workTraceElapsedMs(1_000, null, false, 5_000))
    }

    @Test
    fun `activity uses current structured tool and retains its real target`() {
        val completed = ToolCallUi("old", "list_directory", ToolCallState.Completed(mapOf("path" to "old"), "done"), "old")
        val current = ToolCallUi("live", "read_file", ToolCallState.Running(mapOf("path" to "main.kt")), "main.kt")
        val items = listOf(ChatListItem.ToolCalls("tools", listOf(completed, current), true, isRunning = true))
        val activity = deriveWorkTraceActivity(items) as WorkTraceActivity.Tool
        assertEquals("live", activity.call.id)
        assertEquals("main.kt", activity.call.target)
    }

    @Test
    fun `thinking generation and child waiting have distinct summaries without exposing reasoning text`() {
        val thinking = ChatListItem.Reasoning("r", "private reasoning", true, isReasoningActive = true)
        assertEquals(WorkTraceActivity.Thinking, deriveWorkTraceActivity(listOf(thinking)))
        val generating = ChatListItem.TextMessage("t", false, true, true, "partial update", "p", "s")
        assertEquals(WorkTraceActivity.Generating, deriveWorkTraceActivity(listOf(generating)))
        assertEquals(WorkTraceActivity.WaitingSubagents(2), deriveWorkTraceActivity(listOf(generating.copy(isStreaming = false)), 2))
        assertEquals(WorkTraceActivity.WaitingResponse, deriveWorkTraceActivity(emptyList()))
    }

    private fun message(id: String, start: Long, end: Long? = null) = ChatMessage(
        id, "s", ChatRole.Assistant, listOf(ChatBlock.Reasoning("r_$id", "inspect")),
        createdAt = 10, completedAt = null, parentMessageId = null, model = null, agent = null,
        isFinalAnswer = false, turnId = "work", workStartedAt = start, workCompletedAt = end,
        durationMs = 900_000,
    )

    @Test
    fun `projection carries work endpoints instead of summed LLM durations`() {
        val input = message("input", 1_000).copy(role = ChatRole.User, blocks = listOf(ChatBlock.Text("u", "also inspect tests")))
        val items = computeChatItems(listOf(message("first", 1_000), input, message("second", 1_000, 9_000)), false, null)
        val traces = items.filterIsInstance<ChatListItem.WorkTraceBlock>()
        assertEquals(2, traces.size)
        assertNull(traces.first().startedAt, "插话后的累计计时不能回到上一段")
        val trace = traces.last()
        assertEquals(1_000L, trace.startedAt)
        assertEquals(9_000L, trace.completedAt)
        assertEquals(8_000L, workTraceElapsedMs(trace.startedAt, trace.completedAt, trace.isRunning, 50_000))
        assertTrue(trace.items.filterIsInstance<ChatListItem.Reasoning>().all { it.durationMs == 0L })
    }
    @Test
    fun `successful dispatch stays visible outside folded work without any model reply`() {
        for (action in listOf("SPAWN", "SPAWN_RESEARCHER")) {
            val call = ChatBlock.ToolCall("dispatch", "subagent",
                ToolCallState.Completed(mapOf("action" to action, "task" to "inspect browser"), "dispatched"))
            val input = message("dispatch_message", 1_000).copy(blocks = listOf(call))
            for (working in listOf(true, false)) {
                val items = computeChatItems(listOf(input), working, null)
                assertEquals(1, items.filterIsInstance<ChatListItem.SubagentCalls>().size)
                assertTrue(items.filterIsInstance<ChatListItem.WorkTraceBlock>().all { trace ->
                    trace.items.none { it is ChatListItem.SubagentCalls }
                })
            }
        }
    }

    @Test
    fun `pending failed and management calls never produce a successful dispatch notice`() {
        val spawn = mapOf("action" to "SPAWN")
        val states = listOf(
            ToolCallState.Pending(spawn),
            ToolCallState.Running(spawn),
            ToolCallState.Failed(spawn, "unavailable"),
            ToolCallState.Completed(spawn, "Error: concurrency limit reached"),
            ToolCallState.Completed(mapOf("action" to "STATUS"), "running"),
            ToolCallState.Completed(mapOf("action" to "STOP"), "stopped"),
        )
        for (state in states) {
            val input = message("call_message", 1_000).copy(
                blocks = listOf(ChatBlock.ToolCall("call", "subagent", state)))
            val items = computeChatItems(listOf(input), true, null)
            assertTrue(items.none { it is ChatListItem.SubagentCalls }, "unexpected notice for $state")
            assertTrue(items.filterIsInstance<ChatListItem.WorkTraceBlock>().single()
                .items.any { it is ChatListItem.ToolCalls }, "tool details must remain accessible")
        }
    }

}
