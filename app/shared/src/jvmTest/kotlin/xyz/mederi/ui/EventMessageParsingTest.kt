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

    @Test
    fun testParseBackgroundTaskCompletedCommand() {
        val xml = """
            <event_message type="background_task_completed" taskId="bg_2d2bb4e8" taskType="command" status="completed">
            Background command completed: ./gradlew :app:shared:jvmTest 2>&1 | tail -20
            Exit code: 0
            Output:
            BUILD SUCCESSFUL
            </event_message>
        """.trimIndent()

        val parsed = parseEventMessage(xml)
        assertNotNull(parsed)
        assertEquals("background_task_completed", parsed.eventType)
        assertEquals("", parsed.agentId)
        assertEquals("completed", parsed.status)
        assertEquals("command", parsed.taskType)
        assertNull(parsed.reportPath)
        assertNull(parsed.subtaskInfo)
        assertTrue(parsed.summary.contains("Background command completed"))
        assertTrue(parsed.summary.contains("Exit code: 0"))
        assertTrue(parsed.summary.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun testParseBackgroundTaskTimerFired() {
        val xml = """
            <event_message type="background_task_completed" taskId="tm_abc123" taskType="timer" status="completed">
            ⏰ Timer fired: Reminder to check build
            </event_message>
        """.trimIndent()

        val parsed = parseEventMessage(xml)
        assertNotNull(parsed)
        assertEquals("background_task_completed", parsed.eventType)
        assertEquals("timer", parsed.taskType)
        assertEquals("completed", parsed.status)
        assertTrue(parsed.summary.contains("Timer fired"))
    }

    @Test
    fun testComputeChatItemsBackgroundTaskEventToCard() {
        val eventXml = """
            <event_message type="background_task_completed" taskId="bg_test" taskType="command" status="completed">
            Background command completed: echo hello
            Exit code: 0
            Output:
            hello
            </event_message>
        """.trimIndent()

        val eventMsg = ChatMessage(
            id = "msg_bg",
            conversationId = "conv_1",
            role = ChatRole.User,
            blocks = listOf(ChatBlock.Text(id = "b1", text = eventXml)),
            createdAt = 3000L,
            completedAt = 3000L,
            parentMessageId = null,
            model = null,
            agent = null
        )

        val items = computeChatItems(msgs = listOf(eventMsg), isWorking = false, snapshot = null)
        assertEquals(1, items.size)
        assertTrue(items[0] is ChatListItem.EventMessageCard)
        val eventCard = items[0] as ChatListItem.EventMessageCard
        assertEquals("background_task_completed", eventCard.eventType)
        assertEquals("command", eventCard.taskType)
        assertEquals("completed", eventCard.status)
        assertTrue(eventCard.summary.contains("echo hello"))
    }
}
