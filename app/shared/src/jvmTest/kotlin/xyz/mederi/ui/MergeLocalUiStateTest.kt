package xyz.mederi.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.CostSummary
import xyz.mederi.core.contract.models.TokenUsage
import xyz.mederi.ui.browser.BrowserAutomationUiState

class MergeLocalUiStateTest {

    private fun state(
        modelId: String? = null,
        activeDockPanel: RightDockPanel? = null,
        browserAutomation: BrowserAutomationUiState? = null,
    ): ConversationState {
        val conv = Conversation(
            id = "conv-1",
            projectId = "p-1",
            title = "Test Conversation",
            status = ConversationStatus.Idle,
            createdAt = 1000L,
            updatedAt = 1000L,
            modelId = modelId,
        )
        return ConversationState(
            conversation = conv,
            messages = emptyList(),
            tokenUsage = TokenUsage(),
            cost = CostSummary(),
            activeDockPanel = activeDockPanel,
            browserAutomation = browserAutomation,
        )
    }

    @Test
    fun `completion snapshot merge closes dock`() {
        // aggregator 归约快照：browserAutomation 非空（isAutomating=false = COMPLETED），activeDockPanel 显式 null
        val snap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = false),
            activeDockPanel = null,
        )
        val prevSnap = state(activeDockPanel = RightDockPanel.BROWSER)
        assertNull(mergeLocalUiState(snap, prevSnap).activeDockPanel)
    }

    @Test
    fun `store snapshot keeps prevSnap dock`() {
        // store 初始快照：无 browserAutomation，activeDockPanel 为 null → 回退 prevSnap 的 BROWSER
        val snap = state(activeDockPanel = null)
        val prevSnap = state(activeDockPanel = RightDockPanel.BROWSER)
        assertEquals(RightDockPanel.BROWSER, mergeLocalUiState(snap, prevSnap).activeDockPanel)
    }

    @Test
    fun `browserAutomation keeps semantics`() {
        // snap.browserAutomation 非空 → 结果用 snap 值
        val snapWith = state(browserAutomation = BrowserAutomationUiState(isAutomating = true, isPanelOpen = true))
        val prevWith = state(browserAutomation = BrowserAutomationUiState(isAutomating = false))
        assertEquals(true, mergeLocalUiState(snapWith, prevWith).browserAutomation?.isAutomating)

        // snap.browserAutomation 为空 → 结果用 prevSnap 值
        val snapWithout = state()
        val prevWithout = state(browserAutomation = BrowserAutomationUiState(isAutomating = true, isPanelOpen = true))
        assertEquals(true, mergeLocalUiState(snapWithout, prevWithout).browserAutomation?.isAutomating)
    }

    @Test
    fun `effectiveConv fields prefer prevSnap`() {
        // 写侧输入设置以 prevSnap 优先：snap.modelId = null、prevSnap.modelId = "m1" → 结果 "m1"
        val snap = state(modelId = null)
        val prevSnap = state(modelId = "m1")
        assertEquals("m1", mergeLocalUiState(snap, prevSnap).conversation.modelId)
    }
}
