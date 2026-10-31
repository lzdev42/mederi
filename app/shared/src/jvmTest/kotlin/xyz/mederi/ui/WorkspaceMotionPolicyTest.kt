package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import compose.icons.FeatherIcons
import compose.icons.feathericons.Users
import xyz.mederi.ui.overview.OverviewAccordionRow
import xyz.mederi.ui.overview.overviewTaskIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.theme.*
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.components.atoms.*
import xyz.mederi.ui.settings.GeneralSettingsContent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WorkspaceMotionPolicyTest {
    @Test
    fun sharedPolicyHasOneRhythmAndStaticModeNeverLoops() {
        val normal = WorkspaceMotionPolicy()
        assertEquals(listOf(120, 180, 200), listOf(normal.feedbackMillis, normal.disclosureMillis, normal.panelMillis))
        assertEquals(2400, WorkspaceMotionPolicy.TaskCycleMillis)
        assertEquals(1000, WorkspaceMotionPolicy.LoadingCycleMillis)
        for (reduced in listOf(false, true)) for (running in listOf(false, true)) for (visible in listOf(false, true)) {
            assertEquals(!reduced && running && visible, WorkspaceMotionPolicy(reduced).loops(running, visible))
        }
        val reduced = WorkspaceMotionPolicy(true)
        assertEquals(listOf(0, 0, 0), listOf(reduced.feedbackSpec<Float>().durationMillis,
            reduced.disclosureSpec<Float>().durationMillis, reduced.panelSpec<Float>().durationMillis))
    }

    @Test
    fun motionPreferencePersistsOnOneDeviceAndDoesNotLeakThroughSharedCore() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val core = MockAiCore()
            val preferences = InMemoryPreferencesStore()
            val original = AppState(core, preferences, scope)
            original.setReducedMotion(true); original.flushPreferences()
            assertEquals("true", preferences.getString("ui.motion.reduced"))
            val reloaded = AppState(core, preferences, scope)
            reloaded.hydrate()
            assertTrue(reloaded.reducedMotion.value)
            val otherDevice = AppState(core, InMemoryPreferencesStore(), scope)
            otherDevice.hydrate()
            assertFalse(otherDevice.reducedMotion.value)
            original.setReducedMotion(false); original.flushPreferences()
            assertEquals("false", preferences.getString("ui.motion.reduced"))
        } finally { scope.cancel() }
    }

    @Test
    fun reducedModeLoadingRemainsVisibleButClockDoesNotChangeItsPixels() = runComposeUiTest {
        val reduced = mutableStateOf(true); val running = mutableStateOf(true); val visible = mutableStateOf(true)
        mainClock.autoAdvance = false
        setContent { AppTheme(reducedMotion = reduced.value) {
            WorkspaceLoadingIndicator(Modifier.size(32.dp).testTag("loading"), running = running.value, visible = visible.value)
        } }
        mainClock.advanceTimeByFrame()
        val first = onNodeWithTag("loading").captureToImage().toAwtImage()
        mainClock.advanceTimeBy(320)
        val second = onNodeWithTag("loading").captureToImage().toAwtImage()
        assertTrue(first.getRGB(0, 0, first.width, first.height, null, 0, first.width).contentEquals(
            second.getRGB(0, 0, second.width, second.height, null, 0, second.width)))
        runOnIdle { reduced.value = false }; mainClock.advanceTimeByFrame(); mainClock.advanceTimeByFrame()
        val animated = onNodeWithTag("loading").captureToImage().toAwtImage()
        mainClock.advanceTimeBy(250)
        val later = onNodeWithTag("loading").captureToImage().toAwtImage()
        assertFalse(animated.getRGB(0, 0, animated.width, animated.height, null, 0, animated.width).contentEquals(
            later.getRGB(0, 0, later.width, later.height, null, 0, later.width)))
        runOnIdle { visible.value = false }; mainClock.advanceTimeByFrame()
        onNodeWithTag("loading").assertDoesNotExist()
        runOnIdle { visible.value = true; running.value = false }; mainClock.advanceTimeByFrame()
        onNodeWithTag("loading").assertDoesNotExist()
    }

    @Test
    fun settingsChoiceReflectsOnlyExternalPreferenceAndThemeProvidesSamePolicy() = runComposeUiTest {
        val reduced = mutableStateOf(false); val choices = mutableListOf<Boolean>()
        var label = ""; var policyReduced = false
        setContent { AppTheme(reducedMotion = reduced.value) {
            label = stringResource(Res.string.settings_motion_reduced)
            policyReduced = LocalWorkspaceMotion.current.reducedMotion
            GeneralSettingsContent(AppThemeMode.DARK, AppLanguage.SYSTEM, {}, {}, Modifier.width(700.dp).height(720.dp),
                reducedMotion = reduced.value, onReducedMotionChange = { choices += it })
        } }
        onNodeWithText(label).performScrollTo().assertIsNotSelected().performClick()
        runOnIdle { assertEquals(listOf(true), choices); assertFalse(policyReduced); reduced.value = true }
        onNodeWithText(label).assertIsSelected()
        runOnIdle { assertTrue(policyReduced) }
    }

    @Test
    fun reducedModeDisclosureKeepsSameContentAndRespondsOnNextFrame() = runComposeUiTest {
        val expanded = mutableStateOf(false)
        mainClock.autoAdvance = false
        setContent { AppTheme(reducedMotion = true) {
            Column { ExpandableContent(expanded.value) { Text("Task details stay available") } }
        } }
        onNodeWithText("Task details stay available").assertDoesNotExist()
        runOnIdle { expanded.value = true }; mainClock.advanceTimeByFrame(); mainClock.advanceTimeByFrame()
        onNodeWithText("Task details stay available").assertIsDisplayed()
        runOnIdle { expanded.value = false }; mainClock.advanceTimeByFrame(); mainClock.advanceTimeByFrame()
        onNodeWithText("Task details stay available").assertDoesNotExist()
    }

    @Test
    fun actualOverviewGroupBreathesCollapsedAndExpandedThenStopsAtTerminalState() = runComposeUiTest {
        val expanded = mutableStateOf(false); val running = mutableStateOf(true)
        mainClock.autoAdvance = false
        setContent { AppTheme {
            OverviewAccordionRow(FeatherIcons.Users, "Task group", { Text(if (running.value) "1 running" else "Completed") },
                expanded.value, { expanded.value = !expanded.value },
                Modifier.width(360.dp).testTag("group").overviewTaskIndicator(running.value)) {
                Text("Task details remain readable while chatting")
            }
        } }
        fun pixels(): IntArray {
            val image = onNodeWithTag("group").captureToImage().toAwtImage()
            return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        }
        repeat(2) { index ->
            mainClock.advanceTimeBy(600)
            val first = pixels(); mainClock.advanceTimeBy(400); val later = pixels()
            assertFalse(first.contentEquals(later), "Group pulse must run with expanded=$index")
            if (index == 0) onNodeWithText("Task group").performClick()
        }
        onNodeWithText("Task details remain readable while chatting").assertIsDisplayed()
        runOnIdle { running.value = false }; mainClock.advanceTimeBy(300)
        onNodeWithText("Completed").assertIsDisplayed()
        val terminal = pixels(); mainClock.advanceTimeBy(1200)
        assertTrue(terminal.contentEquals(pixels()), "Terminal group must remain static")
    }

    @Test
    fun renderActualMotionPreferenceInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        setContent { AppTheme(mode.value, reducedMotion = true) {
            Column(Modifier.width(740.dp).height(740.dp).background(LocalMederiColors.current.surfaceWorkspace)
                .padding(20.dp).testTag("motionPreview")) {
                GeneralSettingsContent(mode.value, AppLanguage.SYSTEM, {}, {}, Modifier.weight(1f), reducedMotion = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceLoadingIndicator()
                    Text("Fixture · Task running · 2 agents", style = WorkspaceStyle.Body, color = LocalMederiColors.current.textPrimary)
                }
            }
        } }
        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { mode.value = theme }
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("motionPreview").captureToImage().toAwtImage(), "png",
                    File(directory, "motion-${theme.name.lowercase()}.png"))
            }
        }
    }
}
