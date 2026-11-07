package xyz.mederi.ui.components.atoms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.AppTheme
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.LocalWorkspaceMotion
import xyz.mederi.theme.WorkspaceMotionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProcessLabelTest {
    @Test
    fun runningTextSweepsButStopsWhenCompletedOrMotionIsReduced() = runComposeUiTest {
        mainClock.autoAdvance = false
        val running = mutableStateOf(true)
        val reduced = mutableStateOf(false)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalWorkspaceMotion provides WorkspaceMotionPolicy(reduced.value)) {
                    Box(Modifier.width(400.dp)) {
                        ProcessLabel("Running git branch --show-current", LocalMederiColors.current.textSecondary,
                            running.value, Modifier.testTag("label"))
                    }
                }
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        mainClock.advanceTimeBy(400)
        val first = onNodeWithTag("label").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(800)
        val next = onNodeWithTag("label").captureToImage().toPixelMap()
        assertTrue((0 until first.width).any { x ->
            (0 until first.height).any { y -> first[x, y] != next[x, y] }
        }, "Running label must show a moving highlight")
        for (reduce in listOf(false, true)) {
            runOnIdle { running.value = reduce; reduced.value = reduce }
            mainClock.advanceTimeByFrame()
            val before = onNodeWithTag("label").captureToImage().toPixelMap()
            mainClock.advanceTimeBy(1200)
            val after = onNodeWithTag("label").captureToImage().toPixelMap()
            for (x in 0 until before.width) for (y in 0 until before.height) {
                assertEquals(before[x, y], after[x, y], "Completed/reduced label must remain static")
            }
        }
    }
}
