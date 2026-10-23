package xyz.mederi.ui.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserAutomationModelsTest {

    @Test
    fun `when isAutomating is true, side panel follows isPanelOpen and cannot be closed`() {
        val state = BrowserAutomationUiState(
            isAutomating = true,
            isPanelOpen = false,
        )
        // 自动化主流程已迁移至主会话浮动条，浏览器右侧默认不强制撑开分栏以保留全宽视口
        assertFalse(state.isVisible)
        assertFalse(state.canClose, "Panel must not be closable while isAutomating is true")

        val stateWithPanelOpen = state.copy(isPanelOpen = true)
        assertTrue(stateWithPanelOpen.isVisible)
        assertFalse(stateWithPanelOpen.canClose)
    }

    @Test
    fun `when isAutomating is false, visibility follows isPanelOpen and canClose is true`() {
        val closedState = BrowserAutomationUiState(
            isAutomating = false,
            isPanelOpen = false,
        )
        assertFalse(closedState.isVisible)
        assertTrue(closedState.canClose)

        val openState = closedState.copy(isPanelOpen = true)
        assertTrue(openState.isVisible)
        assertTrue(openState.canClose)
    }

    @Test
    fun `clampWidthDp clamps width within min and max bounds`() {
        assertEquals(
            BrowserAutomationUiState.MIN_PANEL_WIDTH_DP,
            BrowserAutomationUiState.clampWidthDp(100f),
        )
        assertEquals(
            BrowserAutomationUiState.MAX_PANEL_WIDTH_DP,
            BrowserAutomationUiState.clampWidthDp(600f),
        )
        assertEquals(
            300f,
            BrowserAutomationUiState.clampWidthDp(300f),
        )
    }

    @Test
    fun `screenshotCount counts only Screenshot steps and previewScreenshot tracks lightbox target`() {
        val shot = BrowserFlowStep.Screenshot(
            id = "s2",
            imageUrl = "file:///tmp/shot.png",
            title = "搜索结果截图",
            resolution = "1280×800",
        )
        val state = BrowserAutomationUiState(
            isAutomating = true,
            isPanelOpen = true,
            steps = listOf(
                BrowserFlowStep.Narration(id = "s1", text = "我看看网页"),
                shot,
                BrowserFlowStep.Action(
                    id = "s3",
                    kind = BrowserActionKind.CLICK,
                    title = "正在点击",
                    target = "Kotlin Docs",
                    status = BrowserStepStatus.RUNNING,
                ),
            ),
            previewScreenshot = shot,
        )

        assertEquals(3, state.steps.size)
        assertEquals(1, state.screenshotCount)
        assertNotNull(state.previewScreenshot)
        assertEquals("file:///tmp/shot.png", state.previewScreenshot.imageUrl)

        val dismissed = state.copy(previewScreenshot = null)
        assertNull(dismissed.previewScreenshot)
    }

    @Test
    fun `default state has empty live streaming fields`() {
        val state = BrowserAutomationUiState()
        assertEquals("", state.liveReasoning)
        assertEquals("", state.liveText)
    }
}
