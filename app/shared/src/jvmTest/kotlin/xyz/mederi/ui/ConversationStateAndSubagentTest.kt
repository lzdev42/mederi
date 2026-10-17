package xyz.mederi.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.mederi.core.contract.ConversationStateReducer
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.CostSummary
import xyz.mederi.core.contract.models.SubagentActivity
import xyz.mederi.core.contract.models.TokenUsage
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.appstate.AppState

class ConversationStateAndSubagentTest {

    private fun createInitialState(convId: String = "conv-1"): ConversationState {
        val conv = Conversation(
            id = convId,
            projectId = "p-1",
            title = "Test Conversation",
            status = ConversationStatus.Idle,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        return ConversationState(
            conversation = conv,
            messages = emptyList(),
            tokenUsage = TokenUsage(),
            cost = CostSummary()
        )
    }

    @Test
    fun testSubagentLifecycleInStateReducer() {
        val initial = createInitialState("c-1")
        val reducer = ConversationStateReducer

        // 1. SUBAGENT_STARTED
        val startedEvent = CoreEvent(
            type = CoreEventType.SUBAGENT_STARTED,
            sessionId = "c-1",
            payload = mapOf(
                "agentId" to "sub-1",
                "role" to "RESEARCHER",
                "modelId" to "m-gemini",
                "modelName" to "Gemini 2.5 Pro",
                "reasoningLevel" to "HIGH",
                "task" to "Research codebase",
                "briefing" to "Find subagent occurrences"
            ),
            timestamp = "2026-10-17T00:00:00Z"
        )
        val state1 = reducer.apply(initial, startedEvent)
        assertEquals(1, state1.subagents.size)
        val agent1 = state1.subagents.first()
        assertEquals("sub-1", agent1.agentId)
        assertEquals("RESEARCHER", agent1.role)
        assertEquals("RUNNING", agent1.status)
        assertEquals("Research codebase", agent1.task)

        // 2. SUBAGENT_PROGRESS: 工具调用阶段更新 currentActivity 与 currentTool
        val progressEvent = CoreEvent(
            type = CoreEventType.SUBAGENT_PROGRESS,
            sessionId = "c-1",
            payload = mapOf(
                "agentId" to "sub-1",
                "activity" to SubagentActivity.TOOL_CALL,
                "tool" to "read_file",
                "delta" to "Found 5 matches",
                "isMessage" to "true"
            )
        )
        val state2 = reducer.apply(state1, progressEvent)
        val agent2 = state2.subagents.first()
        assertEquals(SubagentActivity.TOOL_CALL, agent2.currentActivity)
        assertEquals("read_file", agent2.currentTool)
        assertEquals("Found 5 matches", agent2.outputText)

        // 3. SUBAGENT_COMPLETED
        val completedEvent = CoreEvent(
            type = CoreEventType.SUBAGENT_COMPLETED,
            sessionId = "c-1",
            payload = mapOf(
                "agentId" to "sub-1",
                "status" to "COMPLETED",
                "completedAt" to "2026-10-17T00:01:00Z"
            )
        )
        val state3 = reducer.apply(state2, completedEvent)
        val agent3 = state3.subagents.first()
        assertEquals("COMPLETED", agent3.status)
        assertEquals("2026-10-17T00:01:00Z", agent3.completedAt)

        // 4. SUBAGENT_DISCARDED
        val discardedEvent = CoreEvent(
            type = CoreEventType.SUBAGENT_DISCARDED,
            sessionId = "c-1",
            payload = mapOf("agentId" to "sub-1")
        )
        val state4 = reducer.apply(state3, discardedEvent)
        assertTrue(state4.subagents.isEmpty(), "Subagents should be empty after discard")
    }

    @Test
    fun testBrowserTaskLifecycleAndDockPanelAutoOpen() {
        val initial = createInitialState("c-1")
        val reducer = ConversationStateReducer

        // 1. BROWSER_TASK_STARTED -> 应该更新 browserAutomation 并自动展开 RightDockPanel.BROWSER
        val startEvent = CoreEvent(
            type = CoreEventType.BROWSER_TASK_STARTED,
            sessionId = "c-1",
            payload = mapOf(
                "browser" to "jcef",
                "summary" to "Automating website"
            )
        )
        val state1 = reducer.apply(initial, startEvent)
        assertNotNull(state1.browserAutomation)
        assertTrue(state1.browserAutomation.isAutomating)
        assertTrue(state1.browserAutomation.isPanelOpen)
        assertEquals(RightDockPanel.BROWSER, state1.activeDockPanel)

        // 2. BROWSER_TASK_COMPLETED -> isAutomating 变为 false
        val finishEvent = CoreEvent(
            type = CoreEventType.BROWSER_TASK_COMPLETED,
            sessionId = "c-1",
            payload = emptyMap()
        )
        val state2 = reducer.apply(state1, finishEvent)
        assertNotNull(state2.browserAutomation)
        assertFalse(state2.browserAutomation.isAutomating)
    }

    @Test
    fun testMessageDeltaResetsInterruptedFlag() {
        val initial = createInitialState("c-1").copy(
            errorIsStreamInterrupted = true
        )
        val reducer = ConversationStateReducer

        val deltaEvent = CoreEvent(
            type = CoreEventType.MESSAGE_DELTA,
            sessionId = "c-1",
            messageId = "msg-1",
            payload = mapOf("text" to "Hello world")
        )
        val state1 = reducer.apply(initial, deltaEvent)
        assertFalse(state1.errorIsStreamInterrupted, "MESSAGE_DELTA should reset errorIsStreamInterrupted")
    }

    @Test
    fun testMessageCompletedParsesTurnDiffSummary() {
        val initial = createInitialState("c-1").copy(
            messages = listOf(
                ChatMessage(
                    id = "msg-assistant",
                    conversationId = "c-1",
                    role = ChatRole.Assistant,
                    blocks = listOf(ChatBlock.Text("b-1", "Done.")),
                    createdAt = 1000L,
                    completedAt = null,
                    parentMessageId = null,
                    model = null,
                    agent = null
                )
            )
        )
        val reducer = ConversationStateReducer

        val diffJson = """{"files":[{"path":"src/Foo.kt","status":"Modified","additions":10,"deletions":2}],"totalAdditions":10,"totalDeletions":2}"""
        val completedEvent = CoreEvent(
            type = CoreEventType.MESSAGE_COMPLETED,
            sessionId = "c-1",
            messageId = "msg-assistant",
            payload = mapOf(
                "diffMessageId" to "msg-assistant",
                "turnDiffSummary" to diffJson
            )
        )
        val state1 = reducer.apply(initial, completedEvent)
        val msg = state1.messages.first()
        assertNotNull(msg.turnDiffSummary)
        assertEquals(10, msg.turnDiffSummary.totalAdditions)
        assertEquals(2, msg.turnDiffSummary.totalDeletions)
        assertEquals(1, msg.turnDiffSummary.files.size)
        assertEquals("src/Foo.kt", msg.turnDiffSummary.files.first().path)
    }

    @Test
    fun testSessionSwitchingDockAndSubagentIsolation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val mockAiCore = MockAiCore()
            mockAiCore.initialize()
            val prefs = InMemoryPreferencesStore()
            val appState = AppState(aiCore = mockAiCore, preferences = prefs, scope = scope)
            val vm = WorkspaceViewModel(appState)

            suspend fun waitUntilAttached(convId: String) {
                val start = xyz.mederi.currentTimeMillis()
                while (vm.conversationId != convId || vm.conversationState == null) {
                    if (xyz.mederi.currentTimeMillis() - start > 5000) error("Timed out waiting for attach($convId)")
                    delay(20)
                }
            }

            // 挂载 conv_101
            vm.selectConversation("conv_101")
            waitUntilAttached("conv_101")

            vm.openDockPanel(RightDockPanel.ARTIFACTS)
            assertEquals(RightDockPanel.ARTIFACTS, vm.activeDockPanel)
            assertEquals(RightDockPanel.ARTIFACTS, vm.conversationState?.activeDockPanel)

            // 挂载 conv_102
            vm.selectConversation("conv_102")
            waitUntilAttached("conv_102")

            // 新会话初始 activeDockPanel 为 null
            assertNull(vm.activeDockPanel)

            // 修改 conv_102 的 Dock 为 TERMINAL
            vm.openDockPanel(RightDockPanel.TERMINAL)
            assertEquals(RightDockPanel.TERMINAL, vm.activeDockPanel)
            assertEquals(RightDockPanel.TERMINAL, vm.conversationState?.activeDockPanel)

            // 切回 conv_101，Dock 状态应该独立恢复为 ARTIFACTS
            vm.selectConversation("conv_101")
            waitUntilAttached("conv_101")
            assertEquals(RightDockPanel.ARTIFACTS, vm.activeDockPanel)
            assertEquals(RightDockPanel.ARTIFACTS, vm.conversationState?.activeDockPanel)

            // 切回 conv_102，Dock 状态应该独立恢复为 TERMINAL
            vm.selectConversation("conv_102")
            waitUntilAttached("conv_102")
            assertEquals(RightDockPanel.TERMINAL, vm.activeDockPanel)
            assertEquals(RightDockPanel.TERMINAL, vm.conversationState?.activeDockPanel)
        } finally {
            scope.cancel()
        }
    }
}
