package xyz.mederi.ui.components.atoms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.theme.AppTheme
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.LocalWorkspaceMotion
import xyz.mederi.theme.WorkspaceMotionPolicy
import xyz.mederi.ui.components.ConversationStatusDot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WorkspaceTaskMotionDeliveryTest {
    @Test
    fun taskAlphaBreathesOnTheSharedCycleWithoutScale() = runComposeUiTest {
        mainClock.autoAdvance = false
        var alpha = 0f
        setContent {
            val current = workspaceTaskPulseAlpha(running = true, minAlpha = 0.2f, maxAlpha = 0.8f)
            SideEffect { alpha = current }
            Box(Modifier.size(24.dp))
        }
        mainClock.advanceTimeByFrame()
        val initial = runOnIdle { alpha }
        mainClock.advanceTimeBy(WorkspaceMotionPolicy.TaskCycleMillis / 2L)
        val bright = runOnIdle { alpha }
        assertTrue(bright > initial + 0.4f)
        mainClock.advanceTimeBy(WorkspaceMotionPolicy.TaskCycleMillis / 2L)
        val returned = runOnIdle { alpha }
        assertTrue(returned < bright - 0.4f)
        assertTrue(returned in 0.19f..0.25f)
    }

    @Test
    fun stoppedHiddenAndReducedBranchesRemainStaticAcrossClockAdvances() = runComposeUiTest {
        mainClock.autoAdvance = false
        val running = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val reduced = mutableStateOf(false)
        var alpha = 0f
        setContent {
            CompositionLocalProvider(LocalWorkspaceMotion provides WorkspaceMotionPolicy(reduced.value)) {
                val current = workspaceTaskPulseAlpha(running.value, visible.value)
                SideEffect { alpha = current }
                Box(Modifier.size(24.dp))
            }
        }
        mainClock.advanceTimeByFrame()
        runOnIdle { assertEquals(0.85f, alpha); running.value = true; visible.value = false }
        mainClock.advanceTimeBy(3600)
        runOnIdle { assertEquals(0.85f, alpha); visible.value = true; reduced.value = true }
        mainClock.advanceTimeBy(3600)
        runOnIdle { assertEquals(0.85f, alpha); reduced.value = false }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(600)
        runOnIdle { assertTrue(alpha < 0.85f); running.value = false }
        mainClock.advanceTimeBy(3600)
        runOnIdle { assertEquals(0.85f, alpha) }
    }

    @Test
    fun clippedTaskHasNoVisibleAreaAndResumesOnlyWhenShown() = runComposeUiTest {
        mainClock.autoAdvance = false
        val offset = mutableStateOf(80.dp)
        var visible = true
        var alpha = 0f
        setContent {
            val visibility = rememberWorkspaceTaskVisibility()
            val current = workspaceTaskPulseAlpha(true, visibility.visible)
            SideEffect { visible = visibility.visible; alpha = current }
            Box(Modifier.size(40.dp).clipToBounds()) {
                Box(Modifier.offset(y = offset.value).size(16.dp).then(visibility.modifier))
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        runOnIdle { assertFalse(visible); assertEquals(0.85f, alpha); offset.value = 0.dp }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        // 布局可见性回调在布局之后写状态；下一帧才将新值投影给SideEffect。
        mainClock.advanceTimeByFrame()
        runOnIdle { assertTrue(visible) }
        mainClock.advanceTimeBy(600)
        runOnIdle { assertTrue(alpha < 0.85f); offset.value = 80.dp }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        mainClock.advanceTimeBy(3600)
        runOnIdle { assertFalse(visible); assertEquals(0.85f, alpha) }
    }

    @Test
    fun waitingForUserKeepsTheActualConversationDotStatic() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { AppTheme {
            ConversationStatusDot(ConversationStatus.WaitingUser, LocalMederiColors.current, Modifier.testTag("waitingDot"))
        } }
        mainClock.advanceTimeByFrame()
        val before = onNodeWithTag("waitingDot").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(WorkspaceMotionPolicy.TaskCycleMillis.toLong())
        val after = onNodeWithTag("waitingDot").captureToImage().toPixelMap()
        for (x in 0 until before.width) for (y in 0 until before.height) {
            assertEquals(before[x, y], after[x, y], "Waiting-user dot must remain static")
        }
    }
}
