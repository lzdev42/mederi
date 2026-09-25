package xyz.mederi.ui

import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventMessageParsingTest {

    @Test
    fun testParseExecutorEventMessage() {
        val xml = """
            <event_message type="subagent_completed" agentId="sub_executor_1" status="COMPLETED">
            Role: EXECUTOR
            Subtask: Subtask 0 (planId: plan_demo, index: 0)
            ReportPath: .mederi/plans/plan_demo/reports/00-executor.md
            Summary:
            [executor report saved to .mederi/plans/plan_demo/reports/00-executor.md]
            All 5 unit tests passed.
            </event_message>
        """.trimIndent()

        val parsed = parseEventMessage(xml)
        assertNotNull(parsed)
        assertEquals("subagent_completed", parsed.eventType)
        assertEquals("sub_executor_1", parsed.agentId)
        assertEquals("COMPLETED", parsed.status)
        assertEquals("EXECUTOR", parsed.role)
        assertEquals("Subtask 0 (planId: plan_demo, index: 0)", parsed.subtaskInfo)
        assertEquals(".mederi/plans/plan_demo/reports/00-executor.md", parsed.reportPath)
        assertTrue(parsed.summary.contains("All 5 unit tests passed."))
    }

    @Test
    fun testParseResearcherEventMessageWithoutPlan() {
        val xml = """
            <event_message type="subagent_completed" agentId="sub_researcher_2" status="COMPLETED">
            Role: RESEARCHER
            Summary:
            Found 3 relevant files for authentication implementation.
            </event_message>
        """.trimIndent()

        val parsed = parseEventMessage(xml)
        assertNotNull(parsed)
        assertEquals("subagent_completed", parsed.eventType)
        assertEquals("sub_researcher_2", parsed.agentId)
        assertEquals("COMPLETED", parsed.status)
        assertEquals("RESEARCHER", parsed.role)
        assertNull(parsed.subtaskInfo)
        assertNull(parsed.reportPath)
        assertEquals("Found 3 relevant files for authentication implementation.", parsed.summary)
    }

    @Test
    fun testComputeChatItemsConvertsEventMessageToCard() {
        val eventXml = """
            <event_message type="subagent_completed" agentId="sub_executor_1" status="COMPLETED">
            Role: EXECUTOR
            Subtask: Subtask 0 (planId: plan_demo, index: 0)
            ReportPath: .mederi/plans/plan_demo/reports/00-executor.md
            Summary:
            Execution completed successfully.
            </event_message>
        """.trimIndent()

        val normalMsg = ChatMessage(
            id = "msg_1",
            conversationId = "conv_1",
            role = ChatRole.User,
            blocks = listOf(ChatBlock.Text(id = "b1", text = "Hello AI")),
            createdAt = 1000L,
            completedAt = 1000L,
            parentMessageId = null,
            model = null,
            agent = null
        )

        val eventMsg = ChatMessage(
            id = "msg_2",
            conversationId = "conv_1",
            role = ChatRole.User,
            blocks = listOf(ChatBlock.Text(id = "b2", text = eventXml)),
            createdAt = 2000L,
            completedAt = 2000L,
            parentMessageId = null,
            model = null,
            agent = null
        )

        val items = computeChatItems(
            msgs = listOf(normalMsg, eventMsg),
            isWorking = false,
            snapshot = null
        )

        assertEquals(2, items.size)
        assertTrue(items[0] is ChatListItem.TextMessage)
        assertEquals("Hello AI", (items[0] as ChatListItem.TextMessage).text)

        assertTrue(items[1] is ChatListItem.EventMessageCard)
        val eventCard = items[1] as ChatListItem.EventMessageCard
        assertEquals("sub_executor_1", eventCard.agentId)
        assertEquals("COMPLETED", eventCard.status)
        assertEquals("EXECUTOR", eventCard.role)
        assertEquals(".mederi/plans/plan_demo/reports/00-executor.md", eventCard.reportPath)
    }
}
