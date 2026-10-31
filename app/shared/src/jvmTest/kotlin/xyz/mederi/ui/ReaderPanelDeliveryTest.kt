package xyz.mederi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.*
import xyz.mederi.ui.components.ReaderPanelContent
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ReaderPanelDeliveryTest {
    @Test fun pureReaderTabsSendExactIdAndOnlyRenderProjectedLiveContent() = runComposeUiTest {
        val stored = ArtifactItem.Text("same", "First", "stored body")
        val second = ArtifactItem.Text("second", "Second", "second body")
        val state = mutableStateOf(ReaderPanelState(listOf(stored, second), stored.copy(content = "live projected body")))
        val selected = mutableListOf<String>()
        setContent { AppTheme(reducedMotion = true) {
            Box(Modifier.size(760.dp, 580.dp)) { ReaderPanelContent(state.value, { selected += it }, {}, LocalMederiColors.current) }
        } }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("live projected body").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("live projected body").assertIsDisplayed()
        onNodeWithText("stored body").assertDoesNotExist()
        onNodeWithText("Second").performClick()
        runOnIdle { assertEquals(listOf("second"), selected); state.value = state.value.copy(activeItem = second) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("second body").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("second body").assertIsDisplayed()
        onNodeWithText("live projected body").assertDoesNotExist()
    }

    @Test fun reportRetryUsesProjectedRequestIdentityAfterStateSwitch() = runComposeUiTest {
        val a = ReportRequest(ReportKey("a", "same"), "a.md", "summary a")
        val b = a.copy(key = ReportKey("b", "same"), summary = "summary b")
        val state = mutableStateOf(ReaderPanelState(listOf(ArtifactItem.Report("a", "A", a)), ArtifactItem.Report("a", "A", a), ReportLoadState.Missing))
        val retries = mutableListOf<ReportKey>(); var reload = ""
        setContent { AppTheme(reducedMotion = true) {
            reload = stringResource(Res.string.report_reload)
            Box(Modifier.size(760.dp, 580.dp)) { ReaderPanelContent(state.value, {}, { retries += it }, LocalMederiColors.current) }
        } }
        onNodeWithText(reload).performClick()
        runOnIdle { val item = ArtifactItem.Report("b", "B", b); state.value = ReaderPanelState(listOf(item), item, ReportLoadState.Missing) }
        onNodeWithText(reload).performClick()
        runOnIdle { assertEquals(listOf(a.key, b.key), retries) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("summary b").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("summary b").assertIsDisplayed(); onNodeWithText("summary a").assertDoesNotExist()
    }

    @Test fun emptyReaderHasNoFakeDocumentActions() = runComposeUiTest {
        var empty = ""; var copy = ""
        setContent { AppTheme(reducedMotion = true) {
            empty = stringResource(Res.string.dock_artifact_empty); copy = stringResource(Res.string.dock_copy_full)
            Box(Modifier.size(760.dp, 580.dp)) { ReaderPanelContent(ReaderPanelState(emptyList(), null), {}, {}, LocalMederiColors.current) }
        } }
        onNodeWithText(empty).assertIsDisplayed(); onNodeWithText(copy).assertDoesNotExist()
    }
}
