package xyz.mederi.ui.chat

import xyz.mederi.core.contract.models.SubagentBlock
import xyz.mederi.core.contract.models.SubagentState
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.SubagentChatItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubagentChatItemsTest {

    private fun subagent(
        status: String = "RUNNING",
        blocks: List<SubagentBlock> = emptyList(),
    ) = SubagentState(
        agentId = "sub_test",
        parentSessionId = "sess_p",
        role = "EXECUTOR",
        modelId = "m1",
        modelName = "ModelX",
        reasoningLevel = "HIGH",
        task = "implement feature",
        briefing = "watch tests",
        status = status,
        startedAt = "2026-10-21T08:00:00Z",
        blocks = blocks,
    )

    @Test
    fun `build produces user message with task and briefing then assistant blocks in order`() {
        val sub = subagent(status = "RUNNING", blocks = listOf(
            SubagentBlock.Reasoning("thinking..."),
            SubagentBlock.ToolCall("read_file", ToolCallState.Running(input = emptyMap())),
            SubagentBlock.Text("done result"),
        ))
        val items = SubagentChatItems.build(sub)

        // computeChatItems 把 User turn 放前、Assistant turn 放后
        // 第一项应是 user TextMessage（含 task）
        val firstText = items.first()
        assertTrue(firstText is ChatListItem.TextMessage, "first item should be user TextMessage")
        assertTrue((firstText as ChatListItem.TextMessage).isUser)
        assertTrue(firstText.text.contains("implement feature"))
        assertTrue(firstText.text.contains("watch tests"))

        // 子代理也复用同一规则：运行中全部过程收进折叠块。
        val trace = items.filterIsInstance<ChatListItem.WorkTraceBlock>().single()
        assertTrue(trace.isRunning)
        assertTrue(trace.items.any { it is ChatListItem.Reasoning }, "should contain reasoning block")
        assertTrue(trace.items.any { it is ChatListItem.ToolCalls }, "should contain tool calls block")
        assertTrue(trace.items.filterIsInstance<ChatListItem.TextMessage>().any { it.text.contains("done result") })
        assertTrue(items.filterIsInstance<ChatListItem.TextMessage>().none { !it.isUser })
    }

    @Test
    fun `build sets isStreaming true when running and false when completed`() {
        val running = subagent(status = "RUNNING", blocks = listOf(SubagentBlock.Text("hi")))
        val runningItems = SubagentChatItems.build(running)
        // 运行中的流式文本仍保留在过程详情内。
        val runningText = runningItems.filterIsInstance<ChatListItem.WorkTraceBlock>().single()
            .items.filterIsInstance<ChatListItem.TextMessage>().first { !it.isUser }
        assertTrue(runningText.isStreaming, "running subagent text should be streaming")

        val completed = subagent(status = "COMPLETED", blocks = listOf(SubagentBlock.Text("hi")))
        val completedItems = SubagentChatItems.build(completed)
        val completedText = completedItems.filterIsInstance<ChatListItem.TextMessage>().first { !it.isUser }
        assertTrue(!completedText.isStreaming, "completed subagent text should not be streaming")
    }

    @Test
    fun `build with empty blocks still produces user message`() {
        val sub = subagent(status = "RUNNING", blocks = emptyList())
        val items = SubagentChatItems.build(sub)
        // 至少有 user TextMessage；assistant 无 block 时 computeChatItems 可能产出空 assistant turn
        assertTrue(items.any { it is ChatListItem.TextMessage && it.isUser }, "should always produce user message")
    }

    @Test
    fun `build completed subagent folds tools into work trace block`() {
        val sub = subagent(status = "COMPLETED", blocks = listOf(
            SubagentBlock.ToolCall("read_file", ToolCallState.Running(input = emptyMap())),
            SubagentBlock.Text("final answer"),
        ))
        val items = SubagentChatItems.build(sub)
        // 完成态 + 有工具调用 → ToolCall 折叠进 WorkTraceBlock，外部留 final TextMessage
        assertTrue(items.any { it is ChatListItem.WorkTraceBlock }, "completed turn with tools should fold into WorkTraceBlock")
        assertTrue(items.filterIsInstance<ChatListItem.TextMessage>().any { !it.isUser && it.text.contains("final answer") }, "final deliverable text should remain outside")
    }
}
