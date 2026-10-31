package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.*
import xyz.mederi.ui.components.EventMessageCard
import xyz.mederi.ui.components.ReportReaderContent
import xyz.mederi.ui.host.FileNode
import xyz.mederi.ui.host.LocalProjectFileTreeProvider
import xyz.mederi.ui.host.ProjectFileTreeProvider
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ReportReaderDeliveryTest {
    private val request = ReportRequest(ReportKey("session-a", "event-a"), "/report/fixture.md", "  Summary fixture  \n\nOriginal final line\n")
    private val title = "Report reader fixture"
    @Composable
    private fun Host(content: @Composable () -> Unit) {
        Box(Modifier.width(760.dp).height(680.dp).background(LocalMederiColors.current.surfaceWorkspace)
            .testTag("reportReaderPreview")) { content() }
    }

    @Test
    fun loadingShowsOnlyStatusAndNeverOffersFakeDocumentCopyOrExport() = runComposeUiTest {
        var loading = ""; var copy = ""; var html = ""; var pdf = ""; var reload = ""
        setContent { AppTheme(reducedMotion = true) {
            loading = stringResource(Res.string.report_loading); copy = stringResource(Res.string.dock_copy_full)
            html = stringResource(Res.string.dock_export_html); pdf = stringResource(Res.string.dock_export_pdf)
            reload = stringResource(Res.string.report_reload)
            Host { ReportReaderContent(ReportLoadState.Loading, request, title, {}, LocalMederiColors.current) }
        } }
        onNodeWithText(loading).assertIsDisplayed()
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
        onNodeWithText(copy).assertDoesNotExist(); onNodeWithText(html).assertDoesNotExist(); onNodeWithText(pdf).assertDoesNotExist()
        onNodeWithText(reload).assertDoesNotExist()
        onNodeWithText("Summary fixture", substring = true).assertDoesNotExist()
    }

    @Test
    fun fullContentUsesOriginalReaderCountsAndCanHideItsRepeatedTitle() = runComposeUiTest {
        val full = "Full report fixture\nSecond line\n"
        var metadata = ""; var copy = ""; var notice = ""; var reload = ""
        setContent { AppTheme(reducedMotion = true) {
            metadata = stringResource(Res.string.attachment_meta, full.lines().size, full.length)
            copy = stringResource(Res.string.dock_copy_full); notice = stringResource(Res.string.report_summary_notice)
            reload = stringResource(Res.string.report_reload)
            Host { ReportReaderContent(ReportLoadState.Content(full), request, title, {}, LocalMederiColors.current, showTitle = false) }
        } }
        onNodeWithText(metadata).assertIsDisplayed()
        onNodeWithText(copy).assertIsDisplayed()
        onNodeWithText("Full report fixture", substring = true).assertExists()
        onNodeWithText(title).assertDoesNotExist()
        onNodeWithText(notice).assertDoesNotExist(); onNodeWithText(reload).assertDoesNotExist()
    }

    @Test
    fun missingReportClearlyLabelsOriginalSummaryAndPreservesWhitespaceInReaderMetadata() = runComposeUiTest {
        var missing = ""; var notice = ""; var metadata = ""; var reload = ""; var retries = 0
        setContent { AppTheme(reducedMotion = true) {
            missing = stringResource(Res.string.report_missing); notice = stringResource(Res.string.report_summary_notice)
            metadata = stringResource(Res.string.attachment_meta, request.summary.lines().size, request.summary.length)
            reload = stringResource(Res.string.report_reload)
            Host { ReportReaderContent(ReportLoadState.Missing, request, title, { retries++ }, LocalMederiColors.current) }
        } }
        onNodeWithText(missing).assertIsDisplayed(); onNodeWithText(notice).assertIsDisplayed()
        onNodeWithText(metadata).assertIsDisplayed()
        onNodeWithText("Original final line", substring = true).assertExists()
        onNodeWithText(reload).performClick()
        runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun errorRetryUsesTheCurrentRequestCallbackAndSummaryRemainsClearlyMarked() = runComposeUiTest {
        val active = mutableStateOf(request)
        val retried = mutableListOf<ReportKey>(); var reload = ""; var notice = ""
        setContent { AppTheme(reducedMotion = true) {
            reload = stringResource(Res.string.report_reload); notice = stringResource(Res.string.report_summary_notice)
            Host { ReportReaderContent(ReportLoadState.Error(UiMessage(Res.string.report_load_failed, listOf("read failure fixture"))),
                active.value, title, { retried += active.value.key }, LocalMederiColors.current) }
        } }
        onNodeWithText("read failure fixture", substring = true).assertIsDisplayed()
        onNodeWithText(notice).assertIsDisplayed()
        onNodeWithText(reload).performClick()
        runOnIdle { active.value = request.copy(key = ReportKey("session-b", "event-b")) }
        onNodeWithText(reload).performClick()
        runOnIdle { assertEquals(listOf(request.key, ReportKey("session-b", "event-b")), retried) }
    }

    @Test
    fun pausedOrMissingWithoutSummaryNeverCreatesAFakeDocument() = runComposeUiTest {
        val state = mutableStateOf<ReportLoadState>(ReportLoadState.Idle)
        var paused = ""; var empty = ""; var copy = ""; var html = ""; var pdf = ""; var reload = ""; var retries = 0
        setContent { AppTheme(reducedMotion = true) {
            paused = stringResource(Res.string.report_read_paused); empty = stringResource(Res.string.event_message_no_content)
            copy = stringResource(Res.string.dock_copy_full); html = stringResource(Res.string.dock_export_html)
            pdf = stringResource(Res.string.dock_export_pdf); reload = stringResource(Res.string.report_reload)
            Host { ReportReaderContent(state.value, request.copy(summary = "  \n "), title, { retries++ }, LocalMederiColors.current) }
        } }
        onNodeWithText(paused).assertIsDisplayed(); onNodeWithText(empty).assertIsDisplayed()
        onNodeWithText(copy).assertDoesNotExist(); onNodeWithText(html).assertDoesNotExist(); onNodeWithText(pdf).assertDoesNotExist()
        onNodeWithText(reload).performClick()
        runOnIdle { state.value = ReportLoadState.Missing }
        onNodeWithText(empty).assertIsDisplayed(); onNodeWithText(copy).assertDoesNotExist()
        onNodeWithText(reload).performClick()
        runOnIdle { assertEquals(2, retries) }
    }

    @Test
    fun eventCardOnlyEmitsLocalizedTitleAndNeverReadsEvenAThrowingProvider() = runComposeUiTest {
        var reads = 0
        val provider = object : ProjectFileTreeProvider {
            override fun rootNode(): FileNode = error("Event card must not inspect files")
            override fun listChildren(dirPath: String): List<FileNode> = error("Event card must not list files")
            override fun readText(path: String): String? { reads++; error("Event card must not read report content") }
        }
        val event = ChatListItem.EventMessageCard("event-card-fixture", "background_task_completed", "agent-fixture", "COMPLETED", "", null,
            "/must-not-read.md", "command", "Original event summary")
        val titles = mutableListOf<String>(); var expected = ""
        setContent { AppTheme(reducedMotion = true) {
            expected = stringResource(Res.string.event_message_open_report_title, stringResource(Res.string.event_message_bg_task_title))
            CompositionLocalProvider(LocalProjectFileTreeProvider provides provider) {
                EventMessageCard(event, { titles += it }, Modifier.testTag("eventReportAction"))
            }
        } }
        runOnIdle { assertEquals(0, reads); assertTrue(titles.isEmpty()) }
        onNodeWithTag("eventReportAction").performClick()
        runOnIdle { assertEquals(listOf(expected), titles); assertEquals(0, reads) }
    }

    @Test
    fun renderActualReportContentInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        val full = "# Report fixture\n\nThis is the full report loaded from its original source.\n\n- First result\n- Second result\n"
        setContent { AppTheme(mode.value, reducedMotion = true) {
            Host { ReportReaderContent(ReportLoadState.Content(full), request, title, {}, LocalMederiColors.current) }
        } }
        onNodeWithText(title).assertIsDisplayed()
        System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { directory ->
            File(directory).mkdirs()
            ImageIO.write(onNodeWithTag("reportReaderPreview").captureToImage().toAwtImage(), "png", File(directory, "report-reader-dark.png"))
        }
        runOnIdle { mode.value = AppThemeMode.LIGHT }
        onNodeWithText(title).assertIsDisplayed()
        System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { directory ->
            File(directory).mkdirs()
            ImageIO.write(onNodeWithTag("reportReaderPreview").captureToImage().toAwtImage(), "png", File(directory, "report-reader-light.png"))
        }
    }
}
