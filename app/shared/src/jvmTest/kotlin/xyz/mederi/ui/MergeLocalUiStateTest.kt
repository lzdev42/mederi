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

    // ---- 浏览器任务生命周期边沿：事件显式 dock 意图生效 ----

    @Test
    fun `jcef start edge opens browser panel`() {
        // 开始边沿 + 快照携带 BROWSER（jcef BROWSER_TASK_STARTED 显式开面板）→ 信任快照
        val snap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = true, isPanelOpen = true),
            activeDockPanel = RightDockPanel.BROWSER,
        )
        val prevSnap = state(activeDockPanel = null)
        assertEquals(RightDockPanel.BROWSER, mergeLocalUiState(snap, prevSnap).activeDockPanel)
    }

    @Test
    fun `jcef completion edge closes browser panel`() {
        // 真实不变量：终态快照到达时，prevSnap（上一帧）必然 browserAutomation.isAutomating=true
        // （STARTED/STEP 已写过缓存），且用户开着实时浏览器面板 → 终态 null 生效关面板
        val snap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = false),
            activeDockPanel = null,
        )
        val prevSnap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = true, isPanelOpen = true),
            activeDockPanel = RightDockPanel.BROWSER,
        )
        assertNull(mergeLocalUiState(snap, prevSnap).activeDockPanel)
    }

    @Test
    fun `jcef terminal keeps user switched diff panel`() {
        // 任务运行中用户切去 DIFF，终态快照的 null 只关 BROWSER，不冲用户手动开的面板
        val snap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = false),
            activeDockPanel = null,
        )
        val prevSnap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = true),
            activeDockPanel = RightDockPanel.DIFF,
        )
        assertEquals(RightDockPanel.DIFF, mergeLocalUiState(snap, prevSnap).activeDockPanel)
    }

    @Test
    fun `manual close mid jcef task sticks`() {
        // jcef 任务运行中用户手动关面板：后续步骤快照携带聚合器陈旧的 BROWSER，不得复活
        val snap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = true),
            activeDockPanel = RightDockPanel.BROWSER,
        )
        val prevSnap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = true),
            activeDockPanel = null,
        )
        assertNull(mergeLocalUiState(snap, prevSnap).activeDockPanel)
    }

    // ---- 回归：浏览器任务跑过一次的会话，手动开面板不再被事件快照冲掉（点开即折叠 bug） ----

    @Test
    fun `idle browser snapshot keeps manual browser automation dock`() {
        // browserAutomation 非空（任务已结束闲置）+ 快照 dock 为聚合器陈旧 null：
        // 手动点开的 BROWSER_AUTOMATION 必须保住——旧规则在此冲成 null（bug 现场）
        val snap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = false),
            activeDockPanel = null,
        )
        val prevSnap = state(activeDockPanel = RightDockPanel.BROWSER_AUTOMATION)
        assertEquals(
            RightDockPanel.BROWSER_AUTOMATION,
            mergeLocalUiState(snap, prevSnap).activeDockPanel,
        )
    }

    @Test
    fun `idle browser snapshot keeps manual diff dock`() {
        val snap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = false),
            activeDockPanel = null,
        )
        val prevSnap = state(activeDockPanel = RightDockPanel.DIFF)
        assertEquals(RightDockPanel.DIFF, mergeLocalUiState(snap, prevSnap).activeDockPanel)
    }

    @Test
    fun `obscura running snapshot keeps manual terminal dock`() {
        // obscura 任务进行中（reducer 从不写 activeDockPanel，快照恒 null）：用户开的 TERMINAL 保住
        val snap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = true, isPanelOpen = true),
            activeDockPanel = null,
        )
        val prevSnap = state(activeDockPanel = RightDockPanel.TERMINAL)
        assertEquals(RightDockPanel.TERMINAL, mergeLocalUiState(snap, prevSnap).activeDockPanel)
    }

    @Test
    fun `obscura start keeps user diff panel`() {
        // obscura STARTED 边沿：快照 dock 无非空意图（恒 null），不得关掉用户已开的面板
        val snap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = true, isPanelOpen = true),
            activeDockPanel = null,
        )
        val prevSnap = state(activeDockPanel = RightDockPanel.DIFF)
        assertEquals(RightDockPanel.DIFF, mergeLocalUiState(snap, prevSnap).activeDockPanel)
    }

    @Test
    fun `obscura terminal keeps browser automation panel open`() {
        // obscura 终态：reducer 只关 BROWSER 不关 BROWSER_AUTOMATION，面板留驻供复盘步骤
        val snap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = false),
            activeDockPanel = null,
        )
        val prevSnap = state(
            browserAutomation = BrowserAutomationUiState(isAutomating = true),
            activeDockPanel = RightDockPanel.BROWSER_AUTOMATION,
        )
        assertEquals(
            RightDockPanel.BROWSER_AUTOMATION,
            mergeLocalUiState(snap, prevSnap).activeDockPanel,
        )
    }

    // ---- 无浏览器上下文 / 首帧 ----

    @Test
    fun `store snapshot keeps prevSnap dock`() {
        // store 初始快照：无 browserAutomation → 本地面板保住
        val snap = state(activeDockPanel = null)
        val prevSnap = state(activeDockPanel = RightDockPanel.BROWSER)
        assertEquals(RightDockPanel.BROWSER, mergeLocalUiState(snap, prevSnap).activeDockPanel)
    }

    @Test
    fun `first snapshot without prevSnap uses snap value`() {
        // 首帧（无 prevSnap）：用快照值
        val snap = state(activeDockPanel = RightDockPanel.BROWSER)
        assertEquals(RightDockPanel.BROWSER, mergeLocalUiState(snap, null).activeDockPanel)
        assertNull(mergeLocalUiState(state(), null).activeDockPanel)
    }

    // ---- 其他字段语义（回归） ----

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
