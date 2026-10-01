package xyz.mederi.ui

import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.ToolCallState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FloatingOverlayTest {

    @Test
    fun testSubagentCallsExtractedFromToolCall() {
        val toolCallMsg = ChatMessage(
            id = "msg_sub_spawn",
            conversationId = "conv_test",
            role = ChatRole.Assistant,
            blocks = listOf(
                ChatBlock.ToolCall(
                    id = "tc_1",
                    name = "subagent",
                    state = ToolCallState.Completed(
                        input = mapOf("action" to "SPAWN", "role" to "EXECUTOR", "task" to "执行 ST0"),
                        output = """{"agentId":"agent_123"}"""
                    )
                )
            ),
            createdAt = 1000L,
            completedAt = 1000L,
            parentMessageId = null,
            model = null,
            agent = null
        )

        val items = computeChatItems(
            msgs = listOf(toolCallMsg),
            isWorking = false,
            snapshot = null
        )

        val allSubagentCalls = items.flatMap {
            when (it) {
                is ChatListItem.SubagentCalls -> listOf(it)
                is ChatListItem.WorkTraceBlock -> it.items.filterIsInstance<ChatListItem.SubagentCalls>()
                else -> emptyList()
            }
        }
        val subagentItem = allSubagentCalls.firstOrNull()
        assertNotNull(subagentItem, "应当解析出 SubagentCalls")
        assertEquals(1, subagentItem.subagents.size)
        assertEquals("subagent", subagentItem.subagents[0].name)
        val input = (subagentItem.subagents[0].state as ToolCallState.Completed).input
        assertEquals("SPAWN", input["action"])
        assertEquals("EXECUTOR", input["role"])
    }
}
