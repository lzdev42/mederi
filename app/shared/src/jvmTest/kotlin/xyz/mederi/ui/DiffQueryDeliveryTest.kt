package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.FileDiff
import xyz.mederi.theme.*
import xyz.mederi.ui.components.DiffQueryContent
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DiffQueryDeliveryTest {
    @Composable private fun Host(state: DiffUiState, refresh: () -> Unit = {}, select: (String) -> Unit = {}) {
        Box(Modifier.size(760.dp, 580.dp).background(LocalMederiColors.current.surfaceWorkspace).testTag("diffPreview")) { DiffQueryContent(state, select, refresh, LocalMederiColors.current) }
    }
    @Test fun initialLoadingIsDistinctFromSuccessfulEmptyAndPaused() = runComposeUiTest {
        val state = mutableStateOf(DiffUiState(loading = true, requested = true))
        var loading = ""; var empty = ""; var paused = ""; var refresh = ""; var calls = 0
        setContent { AppTheme(reducedMotion = true) {
            loading = stringResource(Res.string.diff_loading); empty = stringResource(Res.string.dock_diff_empty)
            paused = stringResource(Res.string.diff_not_loaded); refresh = stringResource(Res.string.dock_refresh_changes)
            Host(state.value, { calls++ })
        } }
        onNodeWithText(loading).assertIsDisplayed(); onNodeWithText(empty).assertDoesNotExist(); onNodeWithText(refresh).assertDoesNotExist()
        runOnIdle { state.value = state.value.copy(loading = false) }
        onNodeWithText(paused).assertIsDisplayed(); onNodeWithText(refresh).performClick()
        runOnIdle { assertEquals(1, calls); state.value = state.value.copy(loaded = true) }
        onNodeWithText(empty).assertIsDisplayed(); onNodeWithText(paused).assertDoesNotExist()
    }
    @Test fun errorReusesSharedBoardWithOneRealRetryAndNoFakeEmpty() = runComposeUiTest {
        var retry = ""; var empty = ""; var calls = 0
        setContent { AppTheme(reducedMotion = true) {
            retry = stringResource(Res.string.dock_refresh_changes); empty = stringResource(Res.string.dock_diff_empty)
            Host(DiffUiState(loaded = true, error = UiMessage(Res.string.diff_load_failed, listOf("offline fixture"))), { calls++ })
        } }
        onNodeWithText("offline fixture", substring = true).assertIsDisplayed()
        onNodeWithText(empty).assertDoesNotExist(); onAllNodesWithText(retry).assertCountEquals(1)
        onNodeWithText(retry).performClick(); runOnIdle { assertEquals(1, calls) }
    }
    @Test fun refreshingSuccessfulEmptyDisablesDuplicateRefresh() = runComposeUiTest {
        var refresh = ""; var empty = ""; var loading = ""
        setContent { AppTheme(reducedMotion = true) {
            refresh = stringResource(Res.string.dock_refresh_changes); empty = stringResource(Res.string.dock_diff_empty)
            loading = stringResource(Res.string.diff_loading)
            Host(DiffUiState(loaded = true, loading = true, requested = true))
        } }
        onNodeWithText(empty).assertIsDisplayed(); onNodeWithText(refresh).assertIsNotEnabled()
        onNodeWithText(loading).assertIsDisplayed()
    }
    @Test fun refreshFailureKeepsExistingFilesAndExactTabSelectionCallback() = runComposeUiTest {
        val one = FileDiff("/src/One.kt", "old", "new", 1, 1)
        val two = one.copy(filePath = "/src/Two.kt")
        val selected = mutableListOf<String>()
        setContent { AppTheme(reducedMotion = true) {
            Host(DiffUiState(items = listOf(one, two), selectedPath = one.filePath, loaded = true,
                error = UiMessage(Res.string.diff_load_failed, listOf("retry fixture"))), select = { selected += it })
        } }
        onNodeWithText("retry fixture", substring = true).assertIsDisplayed()
        onNodeWithText("One.kt").assertIsSelected(); onNodeWithText("Two.kt").performClick()
        runOnIdle { assertEquals(listOf(two.filePath), selected) }
    }
    @Test fun renderActualFailureWithRetainedDiffInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        val diff = FileDiff("src/Example.kt", "val mode = 1", "val mode = 2", 1, 1)
        setContent { AppTheme(mode.value, reducedMotion = true) {
            Host(DiffUiState(items = listOf(diff), selectedPath = diff.filePath, loaded = true,
                error = UiMessage(Res.string.diff_load_failed, listOf("Connection unavailable"))))
        } }
        onNodeWithText("Example.kt").assertIsDisplayed()
        System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { directory ->
            File(directory).mkdirs()
            ImageIO.write(onNodeWithTag("diffPreview").captureToImage().toAwtImage(), "png", File(directory, "diff-query-dark.png"))
        }
        runOnIdle { mode.value = AppThemeMode.LIGHT }
        onNodeWithText("Example.kt").assertIsDisplayed()
        System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { directory ->
            ImageIO.write(onNodeWithTag("diffPreview").captureToImage().toAwtImage(), "png", File(directory, "diff-query-light.png"))
        }
    }

    @Test fun noSessionHasHonestAvailabilityAndNoDeadRefreshAction() = runComposeUiTest {
        var noSession = ""; var refresh = ""
        setContent { AppTheme(reducedMotion = true) {
            noSession = stringResource(Res.string.diff_no_session); refresh = stringResource(Res.string.dock_refresh_changes)
            Host(DiffUiState())
        } }
        onNodeWithText(noSession).assertIsDisplayed(); onNodeWithText(refresh).assertDoesNotExist()
    }

}
