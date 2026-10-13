package xyz.mederi.ui.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserAutomationModelsTest {

    @Test
    fun `when isAutomating is true, panel is forced visible and cannot be closed`() {
        val state = BrowserAutomationUiState(
            isAutomating = true,
            isPanelOpen = false,
        )
        assertTrue(state.isVisible, "Panel must be visible while isAutomating is true")
        assertFalse(state.canClose, "Panel must not be closable while isAutomating is true")
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
}
