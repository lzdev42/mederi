package xyz.mederi.core

import xyz.mederi.core.contract.SubagentTracker
import xyz.mederi.core.contract.models.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubagentSnapshotReconcileTest {
    private fun message(text: String, streaming: Boolean = true) = ChatMessage(
        id = "response", conversationId = "child", role = ChatRole.Assistant,
        blocks = listOf(ChatBlock.Reasoning("reasoning", text), ChatBlock.ToolCall("tool_call", "read_file", ToolCallState.Running(mapOf("path" to "source.kt")))),
        createdAt = 1L, completedAt = null, parentMessageId = null, model = null, agent = null,
        isStreaming = streaming, isFinalAnswer = false, turnId = "work", workStartedAt = 1L)
    private fun child(sequence: Long, text: String, status: String = "RUNNING", completedAt: String? = null) =
        SubagentState("child", "parent", "RESEARCHER", "model", "Model", "HIGH", "research", null,
            status, "2026-10-27T08:00:00Z", currentActivity = "THINKING", currentTool = "read_file",
            outputText = text, completedAt = completedAt, messages = listOf(message(text)), progressSequence = sequence)
    private fun merge(snapshot: SubagentState, previous: SubagentState) =
        SubagentTracker.reconcileSnapshots(listOf(snapshot), listOf(previous)).single()

    @Test
    fun olderNonemptyHydrationCannotOverwriteNewerLiveMessage() {
        val live = child(5, "plan_d87a4db4. complete live reasoning")
        val oldSnapshot = child(3, "plan_d87")
        val result = merge(oldSnapshot, live)
        assertEquals(5L, result.progressSequence)
        assertEquals(live.messages, result.messages)
        assertEquals(live.currentActivity, result.currentActivity)
        assertEquals(live.outputText, result.outputText)
    }

    @Test
    fun newerSequenceReplacesOlderMessages() {
        val incoming = child(6, "new frame")
        val result = merge(incoming, child(5, "old"))
        assertEquals(incoming.messages, result.messages)
        assertEquals(6L, result.progressSequence)
    }

    @Test
    fun terminalLifecycleDoesNotRevertEvenWhenLateContentIsNewer() {
        val stopped = child(9, "old", "STOPPED", "2026-10-27T08:00:20Z")
        val result = merge(child(10, "late but valid buffered frame"), stopped)
        assertEquals(10L, result.progressSequence)
        assertEquals("STOPPED", result.status)
        assertEquals(stopped.completedAt, result.completedAt)
        assertFalse(result.messages.single().isStreaming)
        assertEquals(null, result.currentActivity)
        assertEquals(null, result.currentTool)
        assertTrue(result.messages.single().blocks.filterIsInstance<ChatBlock.ToolCall>().single().state is ToolCallState.Running,
            "生命周期终止不是工具成功证据，不能伪造Completed结果")
    }

    @Test
    fun newerRealTerminalTimeWinsIndependentlyOfProgressSequence() {
        val old = child(10, "most complete content", "COMPLETED", "2026-10-27T08:00:10Z")
        val incoming = child(9, "older content", "ERROR", "2026-10-27T08:00:20Z")
        val result = merge(incoming, old)
        assertEquals("ERROR", result.status)
        assertEquals(incoming.completedAt, result.completedAt)
        assertEquals(old.messages.single().blocks, result.messages.single().blocks)
        assertEquals("ERROR", merge(old, result).status, "更旧终态也不能覆盖更新的ERROR")
    }

    @Test
    fun authoritativeEmptyAndRemovedMembersNeverFallBackToPreviousAgents() {
        val old = child(2, "old")
        assertTrue(SubagentTracker.reconcileSnapshots(emptyList(), listOf(old)).isEmpty())
        val other = child(1, "other").copy(agentId = "other")
        assertEquals(listOf("other"), SubagentTracker.reconcileSnapshots(listOf(other), listOf(old)).map { it.agentId })
        val event = CoreEvent(CoreEventType.SUBAGENT_DISCARDED, "parent", payload = mapOf("agentId" to "child"))
        val discarded = SubagentTracker.apply(mapOf("child" to old), event).values.toList()
        assertTrue(SubagentTracker.reconcileSnapshots(discarded, listOf(old)).isEmpty())
    }

    @Test
    fun terminalHydrationSettlesStreamingWithoutFabricatingToolResult() {
        val stopped = child(0, "", "STOPPED", "2026-10-27T08:00:20Z")
        val events = listOf(
            CoreEvent(CoreEventType.MESSAGE_DELTA, "child", "response", mapOf("type" to "response_started", "turnId" to "work")),
            CoreEvent(CoreEventType.TOOL_CALLED, "child", "response", mapOf("toolCallId" to "read", "tool" to "read_file", "args" to "{}")))
        val result = SubagentTracker.replay(stopped, events)
        assertFalse(result.messages.single().isStreaming)
        assertEquals(1793088020000L, result.messages.single().workCompletedAt)
        assertTrue(result.messages.single().blocks.filterIsInstance<ChatBlock.ToolCall>().single().state is ToolCallState.Running)
    }
}
