package xyz.mederi.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mederi.core.contract.SubagentTracker
import xyz.mederi.core.contract.models.*
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.SubagentChatItems
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 子会话使用与主会话相同的事件/消息/投影，不能重猜推理或模拟工具完成。 */
class SubagentEventParityTest {
    private fun child(type: CoreEventType, response: String? = "r1", payload: Map<String, String> = emptyMap()) =
        CoreEvent(type, "child", response, payload + ("turnId" to "work"), "2026-10-27T08:00:00Z")
    private fun delta(kind: String, content: String, response: String = "r1") =
        child(CoreEventType.MESSAGE_DELTA, response, mapOf("type" to kind, "content" to content))
    private fun base() = SubagentState("child", "parent", "RESEARCHER", "model", "Model", "HIGH",
        "investigate", null, "RUNNING", "2026-10-27T08:00:00Z")
    private fun forward(state: SubagentState, event: CoreEvent): SubagentState {
        val envelope = CoreEvent(CoreEventType.SUBAGENT_PROGRESS, "parent", payload = mapOf(
            "agentId" to "child", "eventJson" to Json.encodeToString(event)), timestamp = event.timestamp)
        return SubagentTracker.apply(mapOf("child" to state), envelope).getValue("child")
    }

    @Test
    fun retryAndFullErrorDiagnosticSurviveProgressReplayAndStaleSnapshots() {
        val diagnostic = "network diagnostic\nCaused by: java.io.EOFException\n" + "full details\n".repeat(600)
        val retry = child(CoreEventType.STATUS, null, mapOf("code" to "RETRYING", "scope" to "provider",
            "attempt" to "3", "delayMs" to "1000", "message" to "connection reset"))
        val failure = child(CoreEventType.MESSAGE_ERROR, null, mapOf("error" to "[NETWORK] EOFException",
            "errorId" to "err-detail", "fullDiagnostic" to diagnostic))
        val retrying = forward(base(), retry)
        assertTrue(retrying.statusHint.orEmpty().contains("connection reset"))
        val failed = forward(retrying, failure)
        assertEquals(diagnostic, failed.errorDiagnostic)
        assertEquals("err-detail", failed.errorId)
        assertNull(failed.statusHint)
        val replayed = SubagentTracker.replay(base().copy(status = "ERROR"), listOf(retry, failure))
        assertEquals(diagnostic, replayed.errorDiagnostic)
        val reconciled = SubagentTracker.reconcileSnapshots(listOf(base()), listOf(replayed)).single()
        assertEquals(diagnostic, reconciled.errorDiagnostic)
        assertEquals("ERROR", reconciled.status)
        val wire = Json.decodeFromString<SubagentState>(Json.encodeToString(replayed))
        assertEquals(diagnostic, wire.errorDiagnostic)
        val finishedRetry = forward(retrying, child(CoreEventType.STATUS, null, mapOf("scope" to "provider", "code" to "RETRY_FINISHED")))
        assertNull(finishedRetry.statusHint)
    }

    @Test
    fun reasoningTextAndToolResultsPreserveExactContentsAndKinds() {
        val args = buildJsonObject { put("path", "docs/architecture"); put("recursive", false) }.toString()
        val events = listOf(
            delta("response_started", ""),
            delta("reasoning", "plan_d87"),
            delta("reasoning", "a4db4."),
            delta("text", "The notebook's recent entries will"),
            delta("response_completed", ""),
            child(CoreEventType.TOOL_CALLED, payload = mapOf("toolCallId" to "directory-1", "tool" to "list_directory", "args" to args)),
            child(CoreEventType.TOOL_RESULT, payload = mapOf("toolCallId" to "directory-1", "tool" to "list_directory", "output" to "complete listing\n01-core.md\n02-app-shared.md", "isError" to "false")),
            delta("response_started", "", "r2"),
            delta("reasoning", "next reasoning", "r2"),
            delta("text", "complete findings", "r2"),
            delta("text", " and closing", "r2"),
            delta("response_completed", "", "r2"),
            child(CoreEventType.MESSAGE_COMPLETED, null, mapOf("finalMessageId" to "r2")),
        )
        val state = events.fold(base(), ::forward)
        assertEquals(listOf("r1", "r2"), state.messages.map { it.id })
        val first = state.messages.first()
        assertEquals("plan_d87a4db4.", first.blocks.filterIsInstance<ChatBlock.Reasoning>().single().text)
        assertEquals("The notebook's recent entries will", first.blocks.filterIsInstance<ChatBlock.Text>().single().text)
        val tool = first.blocks.filterIsInstance<ChatBlock.ToolCall>().single()
        assertEquals("tool_directory-1", tool.id)
        val completed = tool.state as ToolCallState.Completed
        assertEquals("docs/architecture", completed.input["path"])
        assertEquals("false", completed.input["recursive"])
        assertEquals("complete listing\n01-core.md\n02-app-shared.md", completed.output)
        assertEquals(false, first.isFinalAnswer)
        assertEquals(true, state.messages.last().isFinalAnswer)
        assertEquals("complete findings and closing", state.messages.last().blocks.filterIsInstance<ChatBlock.Text>().single().text)
        assertTrue(!state.outputText.orEmpty().contains("plan_d87"))

        val finished = state.copy(status = "COMPLETED", completedAt = "2026-10-27T08:00:10Z")
        val items = SubagentChatItems.build(finished)
        assertEquals(listOf("complete findings and closing"), items.filterIsInstance<ChatListItem.TextMessage>().filter { !it.isUser }.map { it.text })
        val trace = items.filterIsInstance<ChatListItem.WorkTraceBlock>().single()
        assertEquals(listOf("plan_d87a4db4.", "next reasoning"), trace.items.filterIsInstance<ChatListItem.Reasoning>().map { it.text })
        assertEquals(listOf("The notebook's recent entries will"), trace.items.filterIsInstance<ChatListItem.TextMessage>().map { it.text })

        val hydrated = SubagentTracker.replay(base(), events)
        assertEquals(state.messages.map { it.id to it.blocks }, hydrated.messages.map { it.id to it.blocks })
        assertEquals(state.messages.map { it.isFinalAnswer }, hydrated.messages.map { it.isFinalAnswer })
    }

    @Test
    fun repeatedSameNameCallsArePairedByCallIdAndKeepErrors() {
        val args1 = buildJsonObject { put("path", "first.kt") }.toString()
        val args2 = buildJsonObject { put("path", "second.kt") }.toString()
        val events = listOf(delta("response_started", ""),
            child(CoreEventType.TOOL_CALLED, payload = mapOf("toolCallId" to "one", "tool" to "read_file", "args" to args1)),
            child(CoreEventType.TOOL_CALLED, payload = mapOf("toolCallId" to "two", "tool" to "read_file", "args" to args2)),
            child(CoreEventType.TOOL_RESULT, payload = mapOf("toolCallId" to "two", "tool" to "read_file", "output" to "second failed", "isError" to "true")),
            child(CoreEventType.TOOL_RESULT, payload = mapOf("toolCallId" to "one", "tool" to "read_file", "output" to "first complete", "isError" to "false")))
        val tools = events.fold(base(), ::forward).messages.single().blocks.filterIsInstance<ChatBlock.ToolCall>()
        assertEquals(2, tools.size)
        val one = tools.single { it.id == "tool_one" }.state as ToolCallState.Completed
        val two = tools.single { it.id == "tool_two" }.state as ToolCallState.Failed
        assertEquals("first.kt", one.input["path"])
        assertEquals("first complete", one.output)
        assertEquals("second.kt", two.input["path"])
        assertEquals("second failed", two.error)
    }

    @Test
    fun interruptedChildCannotPromoteLastIntermediateText() {
        val events = listOf(delta("response_started", ""), delta("text", "partial explanation"),
            child(CoreEventType.MESSAGE_ERROR, null, mapOf("error" to "stream failed", "finalStatus" to "IDLE")))
        val state = events.fold(base(), ::forward).copy(status = "ERROR")
        val items = SubagentChatItems.build(state)
        assertTrue(items.filterIsInstance<ChatListItem.TextMessage>().none { !it.isUser })
        assertEquals("partial explanation", items.filterIsInstance<ChatListItem.WorkTraceBlock>().single().items.filterIsInstance<ChatListItem.TextMessage>().single().text)
    }
    @Test
    fun hydratedMessagesIgnoreReplayedProgressWithoutDroppingNewerFrames() {
        val events = listOf(delta("response_started", ""), delta("reasoning", "plan_d87a4db4."))
        var state = SubagentTracker.replay(base(), events)
        assertEquals(2L, state.progressSequence)
        fun envelope(event: CoreEvent, sequence: Long) = CoreEvent(CoreEventType.SUBAGENT_PROGRESS, "parent", payload = mapOf(
            "agentId" to "child", "sequence" to sequence.toString(), "eventJson" to Json.encodeToString(event)))
        state = SubagentTracker.apply(mapOf("child" to state), envelope(events.last(), 2)).getValue("child")
        assertEquals("plan_d87a4db4.", state.messages.single().blocks.filterIsInstance<ChatBlock.Reasoning>().single().text)
        val next = delta("reasoning", " next")
        state = SubagentTracker.apply(mapOf("child" to state), envelope(next, 3)).getValue("child")
        assertEquals("plan_d87a4db4. next", state.messages.single().blocks.filterIsInstance<ChatBlock.Reasoning>().single().text)
        val restarted = CoreEvent(CoreEventType.SUBAGENT_STARTED, "parent", payload = mapOf("agentId" to "child"), timestamp = "older")
        val afterStartReplay = SubagentTracker.apply(mapOf("child" to state), restarted).getValue("child")
        assertEquals(state.messages, afterStartReplay.messages)
        assertEquals(3L, afterStartReplay.progressSequence)
    }

}
