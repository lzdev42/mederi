package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.BackgroundTaskInfo
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.overview.OverviewRunningTaskContent
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BackgroundTaskOverviewDeliveryTest {
    private fun task(id: String, status: String = "running") = BackgroundTaskInfo(
        id, "command", status, command = "command fixture $id", startedAt = "2026-10-30T00:00:00Z")
    private fun queryError() = UiMessage(Res.string.background_tasks_query_failed, listOf("query fixture"))
    private fun stopError(id: String) = UiMessage(Res.string.background_task_stop_failed, listOf(id, "stop fixture $id"))
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test
    fun onlyActualLoadingShowsProgressAndOnlyLoadedSuccessShowsEmpty() = runComposeUiTest {
        val state = mutableStateOf(BackgroundTaskQueryState(sessionId = "session-a", loading = true))
        var empty = ""; var loading = ""; var refresh = ""; var refreshes = 0
        setContent { AppTheme(reducedMotion = true) {
            empty = stringResource(Res.string.overview_running_task_empty)
            loading = stringResource(Res.string.overview_running_task_loading)
            refresh = stringResource(Res.string.overview_running_task_refresh)
            OverviewRunningTaskContent(state.value, true, {}, { refreshes++ }, {}, {}, Modifier.width(360.dp).height(700.dp))
        } }
        onNodeWithText(loading).assertIsDisplayed()
        onAllNodes(progress).assertCountEquals(1)
        onNodeWithText(empty).assertDoesNotExist()
        runOnIdle { state.value = state.value.copy(loading = false) }
        onAllNodes(progress).assertCountEquals(0)
        onNodeWithText(loading).assertDoesNotExist()
        onNodeWithText(empty).assertDoesNotExist()
        onNodeWithText(refresh).performClick()
        runOnIdle { assertEquals(1, refreshes); state.value = state.value.copy(loaded = true) }
        onNodeWithText(empty).assertIsDisplayed()
    }

    @Test
    fun queryFailureKeepsRefreshRealAndDoesNotClaimEmptySuccessEvenWhenRefreshing() = runComposeUiTest {
        val state = mutableStateOf(BackgroundTaskQueryState("session-a", loaded = true, queryError = queryError()))
        var refresh = ""; var empty = ""; var refreshes = 0
        setContent { AppTheme(reducedMotion = true) {
            refresh = stringResource(Res.string.overview_running_task_refresh)
            empty = stringResource(Res.string.overview_running_task_empty)
            OverviewRunningTaskContent(state.value, true, {}, { refreshes++ }, {}, {})
        } }
        onNodeWithText("query fixture", substring = true).assertIsDisplayed()
        onNodeWithText(empty).assertDoesNotExist()
        onNodeWithText(refresh).performClick()
        runOnIdle { state.value = state.value.copy(loading = true) }
        onNodeWithText(refresh).assertIsEnabled().performClick()
        runOnIdle { assertEquals(2, refreshes) }
    }

    @Test
    fun stopUsesExactTaskIdAndOnlyTheBusyTargetIsDisabled() = runComposeUiTest {
        val state = mutableStateOf(BackgroundTaskQueryState("session-a", listOf(task("a"), task("b")), loaded = true))
        val stops = mutableListOf<String>(); var stop = ""
        setContent { AppTheme(reducedMotion = true) {
            stop = stringResource(Res.string.overview_running_task_stop)
            OverviewRunningTaskContent(state.value, true, {}, {}, { stops += it }, {})
        } }
        onAllNodesWithContentDescription(stop)[1].performClick()
        runOnIdle { assertEquals(listOf("b"), stops); state.value = state.value.copy(stoppingTaskIds = setOf("b")) }
        onAllNodesWithContentDescription(stop)[1].assertIsNotEnabled().performTouchInput { click() }
        onAllNodesWithContentDescription(stop)[0].assertIsEnabled().performClick()
        onAllNodes(progress).assertCountEquals(1)
        runOnIdle { assertEquals(listOf("b", "a"), stops) }
    }

    @Test
    fun stopErrorsRetryOnlyRunningTargetsAndDismissEachExactErrorIndependently() = runComposeUiTest {
        val state = mutableStateOf(BackgroundTaskQueryState("session-a", listOf(task("a"), task("b", "completed")),
            loaded = true, stopErrors = linkedMapOf("a" to stopError("a"), "b" to stopError("b"), "gone" to stopError("gone"))))
        val stops = mutableListOf<String>(); val dismissed = mutableListOf<String>()
        var retry = ""; var close = ""
        setContent { AppTheme(reducedMotion = true) {
            retry = stringResource(Res.string.overview_running_task_retry_stop)
            close = stringResource(Res.string.errorboard_close)
            OverviewRunningTaskContent(state.value, true, {}, {}, { stops += it }, { id ->
                dismissed += id; state.value = state.value.copy(stopErrors = state.value.stopErrors - id)
            }, Modifier.width(400.dp).height(900.dp))
        } }
        onAllNodesWithText(retry).assertCountEquals(1)
        onNodeWithText(retry).performClick()
        runOnIdle { assertEquals(listOf("a"), stops) }
        onAllNodesWithContentDescription(close)[1].performClick()
        runOnIdle { assertEquals(listOf("b"), dismissed); assertEquals(setOf("a", "gone"), state.value.stopErrors.keys) }
        onNodeWithText("stop fixture gone", substring = true).assertIsDisplayed()
    }

    @Test
    fun busyStopErrorKeepsCloseAvailableAndNeverOffersASecondStopRequest() = runComposeUiTest {
        val state = BackgroundTaskQueryState("session-a", listOf(task("busy")), loaded = true,
            stoppingTaskIds = setOf("busy"), stopErrors = mapOf("busy" to stopError("busy")))
        val dismissed = mutableListOf<String>(); var retry = ""; var close = ""; var stop = ""; var stops = 0
        setContent { AppTheme(reducedMotion = true) {
            retry = stringResource(Res.string.overview_running_task_retry_stop)
            close = stringResource(Res.string.errorboard_close)
            stop = stringResource(Res.string.overview_running_task_stop)
            OverviewRunningTaskContent(state, true, {}, {}, { stops++ }, { dismissed += it })
        } }
        onNodeWithText(retry).assertDoesNotExist()
        onNodeWithContentDescription(stop).assertIsNotEnabled()
        onNodeWithContentDescription(close).performClick()
        runOnIdle { assertEquals(listOf("busy"), dismissed); assertEquals(0, stops) }
    }

    @Test
    fun switchingSessionProjectionAndThenNoSessionCannotDisplayOldTargets() = runComposeUiTest {
        val state = mutableStateOf(BackgroundTaskQueryState("session-a", listOf(task("old")), loaded = true))
        var noSession = ""; var refresh = ""
        setContent { AppTheme(reducedMotion = true) {
            noSession = stringResource(Res.string.overview_running_task_no_session)
            refresh = stringResource(Res.string.overview_running_task_refresh)
            OverviewRunningTaskContent(state.value, true, {}, {}, {}, {})
        } }
        onNodeWithText("command fixture old").assertIsDisplayed()
        runOnIdle { state.value = BackgroundTaskQueryState("session-b", loading = true) }
        onNodeWithText("command fixture old").assertDoesNotExist()
        runOnIdle { state.value = BackgroundTaskQueryState(tasks = listOf(task("stale"))) }
        onNodeWithText(noSession).assertIsDisplayed()
        onNodeWithText("command fixture stale").assertDoesNotExist()
        onNodeWithText(refresh).assertDoesNotExist()
    }

    @Test
    fun collapsedGroupKeepsTrueRunningCountAndExpansionRemainsExternallyControlled() = runComposeUiTest {
        val expanded = mutableStateOf(false)
        val state = BackgroundTaskQueryState("session-a", listOf(task("a"), task("b"), task("done", "completed")), loaded = true)
        var count = ""; var title = ""; var toggles = 0
        setContent { AppTheme(reducedMotion = true) {
            count = stringResource(Res.string.overview_running_task_count, 2)
            title = stringResource(Res.string.overview_running_task_title)
            OverviewRunningTaskContent(state, expanded.value, { toggles++ }, {}, {}, {})
        } }
        onNodeWithText(count).assertIsDisplayed()
        onNodeWithText("command fixture a").assertDoesNotExist()
        onNodeWithText(title).performClick()
        runOnIdle { assertEquals(1, toggles); assertFalse(expanded.value); expanded.value = true }
        onNodeWithText("command fixture a").assertIsDisplayed()
        onNodeWithText(count).assertIsDisplayed()
    }
    @Test
    fun foldedFailuresRemainDiscoverableAndActualContentRendersInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        val expanded = mutableStateOf(false)
        var feedback = ""; var count = ""
        val state = BackgroundTaskQueryState("session-a", listOf(task("a"), task("b")), loaded = true,
            loading = true, stopErrors = mapOf("a" to stopError("a")))
        setContent { AppTheme(mode.value, reducedMotion = true) {
            feedback = stringResource(Res.string.overview_running_task_feedback)
            count = stringResource(Res.string.overview_running_task_count, 2)
            OverviewRunningTaskContent(state, expanded.value, { expanded.value = !expanded.value }, {}, {}, {},
                Modifier.width(380.dp).heightIn(max = 560.dp).background(LocalMederiColors.current.surfaceWorkspace).testTag("backgroundPreview"))
        } }
        onNodeWithText(feedback).assertIsDisplayed(); onNodeWithText(count).assertIsDisplayed()
        runOnIdle { expanded.value = true }
        // Cached periodic refresh must not add a second loading loop or shift content every interval.
        onAllNodes(progress).assertCountEquals(0)
        onNodeWithText("stop fixture a", substring = true).assertIsDisplayed()
        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { mode.value = theme }
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("backgroundPreview").captureToImage().toAwtImage(), "png",
                    File(directory, "background-tasks-${theme.name.lowercase()}.png"))
            }
        }
    }

}
