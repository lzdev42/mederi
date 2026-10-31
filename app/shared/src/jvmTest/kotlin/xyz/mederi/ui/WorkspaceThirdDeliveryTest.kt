package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Download
import kotlinx.coroutines.CompletableDeferred
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.FileDiff
import xyz.mederi.core.contract.models.FileDiffSummaryUi
import xyz.mederi.core.contract.models.TurnDiffSummaryUi
import xyz.mederi.theme.AppTheme
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.ArtifactTabs
import xyz.mederi.ui.components.DiffFilesContent
import xyz.mederi.ui.components.TextReaderToolbar
import xyz.mederi.ui.components.ImageReaderToolbar
import xyz.mederi.ui.components.ExportActionButton
import xyz.mederi.ui.components.ExportOutcome
import xyz.mederi.util.ExportStatus
import xyz.mederi.ui.components.FilesPanelTabs
import xyz.mederi.ui.components.FilesTab
import xyz.mederi.ui.components.ProjectTreeContent
import xyz.mederi.ui.host.FileNode
import xyz.mederi.ui.host.ProjectFileTreeProvider
import xyz.mederi.ui.components.FileDiffRow
import xyz.mederi.ui.components.TurnDiffSummaryCard
import xyz.mederi.ui.components.atoms.WorkspaceNavigationTab
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 文件与阅读器使用真实Compose组件验证；预览为组件组合，非完整APP截图。 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceThirdDeliveryTest {
    private val longPath = "app/shared/src/commonMain/kotlin/xyz/mederi/very-long-directory-name/another-long-directory/ConversationWorkspacePresentation.kt"
    private val summary = TurnDiffSummaryUi(
        files = listOf(FileDiffSummaryUi(longPath, "modified", 1234, 567)),
        totalAdditions = 1234, totalDeletions = 567,
    )

    @Test
    fun longFilePathKeepsChangeCountsVisibleAtNarrowWidth() = runComposeUiTest {
        var clicks = 0
        setContent {
            AppTheme {
                FileDiffRow(longPath, 1234, 567, Modifier.width(280.dp).testTag("fileRow"), onClick = { clicks++ })
            }
        }
        onNodeWithText("+1234").assertIsDisplayed()
        onNodeWithText("-567").assertIsDisplayed()
        val row = onNodeWithTag("fileRow").fetchSemanticsNode().boundsInRoot
        for (text in listOf("+1234", "-567")) {
            val count = onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(count.left >= row.left && count.right <= row.right, "$text must fit inside narrow row")
        }
        onNodeWithText(longPath.substringAfterLast('/')).performClick()
        runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun fileAndReviewActionsStayIndependentAndPreserveFullPath() = runComposeUiTest {
        val opened = mutableListOf<String>()
        var reviews = 0
        var review = ""
        setContent {
            AppTheme {
                review = stringResource(Res.string.diff_review)
                Box(Modifier.width(280.dp)) {
                    TurnDiffSummaryCard(summary, onReviewClick = { reviews++ }, onFileClick = { opened += it })
                }
            }
        }
        onNodeWithText("+1234", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("-567", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(longPath.substringAfterLast('/')).performClick()
        runOnIdle { assertEquals(listOf(longPath), opened); assertEquals(0, reviews) }
        onNodeWithText(review).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(listOf(longPath), opened); assertEquals(1, reviews) }
    }

    @Test
    fun emptyDiffSummaryDoesNotCreateAPlaceholderOrReviewAction() = runComposeUiTest {
        var review = ""
        setContent {
            AppTheme {
                review = stringResource(Res.string.diff_review)
                TurnDiffSummaryCard(TurnDiffSummaryUi(), {}, {}, Modifier.testTag("emptyDiff"))
            }
        }
        onNodeWithTag("emptyDiff").assertDoesNotExist()
        onNodeWithText(review).assertDoesNotExist()
    }

    @Test
    fun navigationTabSelectionIsControlledByCallerAndExposesSelectedSemantics() = runComposeUiTest {
        val selected = mutableStateOf("Files")
        val clicks = mutableListOf<String>()
        setContent {
            AppTheme {
                Row(Modifier.width(280.dp)) {
                    for (label in listOf("Files", "Changes")) {
                        WorkspaceNavigationTab(label, selected.value == label,
                            { clicks += label; selected.value = label }, Modifier.weight(1f))
                    }
                }
            }
        }
        onNodeWithText("Files").assertIsSelected()
        onNodeWithText("Changes").assertIsNotSelected().performClick()
        onNodeWithText("Files").assertIsNotSelected()
        onNodeWithText("Changes").assertIsSelected()
        runOnIdle { assertEquals(listOf("Changes"), clicks) }
    }

    private class TreeFixture : ProjectFileTreeProvider {
        val root = FileNode("mederi", "/workspace/mederi", true)
        val source = FileNode("source", "${root.path}/source", true)
        val target = FileNode("Target.kt", "${source.path}/Target.kt", false)
        val other = FileNode("Other.kt", "${source.path}/Other.kt", false)
        val readme = FileNode("README.md", "${root.path}/README.md", false)
        val children = mutableMapOf(root.path to listOf(source, readme), source.path to listOf(target, other))
        var reads = 0
        override fun rootNode() = root
        override fun listChildren(dirPath: String): List<FileNode> { reads++; return children[dirPath].orEmpty() }
        override fun readText(path: String): String? = null
    }

    @Test
    fun filesTabsSwitchThroughCallerAndExposeSelectionAndCount() = runComposeUiTest {
        val current = mutableStateOf(FilesTab.Tree)
        var tree = ""
        var diff = ""
        setContent {
            AppTheme {
                tree = stringResource(Res.string.files_subtab_tree)
                diff = stringResource(Res.string.files_subtab_diff)
                FilesPanelTabs(current.value, 3, { current.value = it }, Modifier.width(280.dp))
            }
        }
        onNodeWithText(tree).assertIsSelected()
        onNodeWithText(diff).assertIsNotSelected().performClick()
        onNodeWithText(diff).assertIsSelected()
        onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(tree).assertIsNotSelected().performClick()
        runOnIdle { assertEquals(FilesTab.Tree, current.value) }
    }

    @Test
    fun fileNavigationKeepsBothActionsInsideNarrowWidthWithLargerText() = runComposeUiTest {
        var tree = ""
        var diff = ""
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    tree = stringResource(Res.string.files_subtab_tree)
                    diff = stringResource(Res.string.files_subtab_diff)
                    FilesPanelTabs(FilesTab.Tree, 123, {}, Modifier.width(280.dp).testTag("largeTextTabs"))
                }
            }
        }
        val group = onNodeWithTag("largeTextTabs").fetchSemanticsNode().boundsInRoot
        for (label in listOf(tree, diff)) {
            onNodeWithText(label).assertIsDisplayed()
            val bounds = onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= group.left && bounds.right <= group.right)
        }
        onNodeWithText("123", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun filteringRetainsAncestorsAndFileOpenReceivesFullNode() = runComposeUiTest {
        val fixture = TreeFixture()
        val opened = mutableListOf<FileNode>()
        var search = ""
        setContent {
            AppTheme {
                search = stringResource(Res.string.tree_search_placeholder)
                ProjectTreeContent(fixture.root, fixture, LocalMederiColors.current,
                    { opened += it }, Modifier.width(280.dp).height(420.dp))
            }
        }
        onNodeWithText("Target.kt").assertDoesNotExist()
        onNodeWithContentDescription(search).performTextInput("target")
        onNodeWithText("source").assertIsDisplayed()
        onNodeWithText("README.md").assertDoesNotExist()
        onNodeWithText("source").performSemanticsAction(SemanticsActions.Expand) { it() }
        onNodeWithText("Target.kt").assertIsDisplayed().assertIsNotSelected().performClick()
        onNodeWithText("Target.kt").assertIsSelected()
        onNodeWithText("Other.kt").assertDoesNotExist()
        runOnIdle { assertEquals(listOf(fixture.target), opened) }
        onNodeWithText("source").performSemanticsAction(SemanticsActions.Collapse) { it() }
        onNodeWithText("Target.kt").assertDoesNotExist()
    }

    @Test
    fun refreshRequeriesProviderAndPreservesExpandedSelectedAndFilteredState() = runComposeUiTest {
        val fixture = TreeFixture()
        var search = ""
        var refresh = ""
        var collapse = ""
        setContent {
            AppTheme {
                search = stringResource(Res.string.tree_search_placeholder)
                refresh = stringResource(Res.string.tree_refresh)
                collapse = stringResource(Res.string.tree_collapse_all)
                ProjectTreeContent(fixture.root, fixture, LocalMederiColors.current,
                    {}, Modifier.width(280.dp).height(420.dp))
            }
        }
        // 目录名命中时，其下所有文件都应保留。
        onNodeWithContentDescription(search).performTextInput("source")
        onNode(hasText("source") and hasClickAction() and !hasSetTextAction()).performClick()
        onNodeWithText("Other.kt").assertIsDisplayed()
        onNodeWithText("Target.kt").performClick()
        val added = FileNode("New.kt", "${fixture.source.path}/New.kt", false)
        var priorReads = 0
        runOnIdle {
            priorReads = fixture.reads
            fixture.children[fixture.source.path] = listOf(fixture.target, fixture.other, added)
        }
        onNodeWithText("New.kt").assertDoesNotExist()
        onNodeWithContentDescription(refresh).performClick()
        onNodeWithText("New.kt").assertIsDisplayed()
        onNodeWithText("Target.kt").assertIsSelected()
        onNodeWithText("README.md").assertDoesNotExist()
        runOnIdle { assertTrue(fixture.reads > priorReads) }
        onNodeWithContentDescription(collapse).performClick()
        onNodeWithText("Target.kt").assertDoesNotExist()
    }

    @Test
    fun windowsPathIsPresentedAsFilenameButCallbackKeepsOriginalPath() = runComposeUiTest {
        val path = "C:\\workspace\\mederi\\src\\ConversationWorkspacePresentation.kt"
        val opened = mutableListOf<String>()
        setContent {
            AppTheme {
                TurnDiffSummaryCard(TurnDiffSummaryUi(listOf(FileDiffSummaryUi(path, "modified", 8, 2)), 8, 2),
                    {}, { opened += it }, Modifier.width(280.dp))
            }
        }
        onNodeWithText("ConversationWorkspacePresentation.kt").performClick()
        runOnIdle { assertEquals(listOf(path), opened) }
    }

    @Test
    fun artifactTabsSelectTheOriginalArtifactIdentity() = runComposeUiTest {
        val items = listOf(ArtifactItem.Text("first", "Report", "# Original report"),
            ArtifactItem.Image("second", "Diagram", "fixture://image"))
        val active = mutableStateOf("first")
        val selections = mutableListOf<String>()
        setContent {
            AppTheme {
                Box(Modifier.width(280.dp)) {
                    ArtifactTabs(items, active.value, { active.value = it; selections += it }, LocalMederiColors.current)
                }
            }
        }
        onNodeWithText("Report").assertIsSelected()
        val labelBounds = onNodeWithText("Report", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(labelBounds.width > 20f, "Scrollable tabs must allocate visible width to their text")
        onNodeWithText("Diagram").assertIsNotSelected().performClick()
        onNodeWithText("Diagram").assertIsSelected()
        onNodeWithText("Report").assertIsNotSelected()
        runOnIdle { assertEquals(listOf("second"), selections) }
    }

    @Test
    fun diffSelectionKeepsDisplayedBodyInSyncWithTheSelectedPath() = runComposeUiTest {
        val diffs = listOf(FileDiff("src/first.kt", "first-before", "first-after", 1, 1),
            FileDiff("src/second.kt", "second-before", "second-after", 2, 1))
        val selected = mutableStateOf<String?>("src/first.kt")
        val selections = mutableListOf<String>()
        setContent {
            AppTheme {
                Box(Modifier.width(600.dp).height(300.dp)) {
                    DiffFilesContent(diffs, selected.value, { selected.value = it; selections += it }, {}, LocalMederiColors.current)
                }
            }
        }
        onNodeWithText("first.kt").assertIsSelected()
        onNodeWithText("second.kt").assertIsNotSelected().performClick()
        onNodeWithText("second.kt").assertIsSelected()
        onNodeWithText("first.kt").assertIsNotSelected()
        onNodeWithText("second-after", substring = true).assertIsDisplayed()
        onNodeWithText("first-after", substring = true).assertDoesNotExist()
        runOnIdle { assertEquals(listOf("src/second.kt"), selections) }
    }

    @Test
    fun readerToolbarsKeepCopyAndImageScaleActionsAvailableAtNarrowWidth() = runComposeUiTest {
        val original = mutableStateOf(false)
        val copied = mutableStateOf(false)
        var copies = 0
        var copy = ""
        var copiedLabel = ""
        var originalLabel = ""
        var fit = ""
        setContent {
            AppTheme {
                copy = stringResource(Res.string.dock_copy_full)
                copiedLabel = stringResource(Res.string.copy_done)
                originalLabel = stringResource(Res.string.dock_original_ratio)
                fit = stringResource(Res.string.dock_fit_window)
                Column(Modifier.width(280.dp)) {
                    TextReaderToolbar("Long document title that wraps within the available reader width", 251, 8313,
                        false, copied.value, { copies++; copied.value = true }, LocalMederiColors.current)
                    ImageReaderToolbar(null, original.value, { original.value = !original.value }, LocalMederiColors.current)
                }
            }
        }
        onNodeWithText(copy).assertIsDisplayed().performClick()
        onNodeWithText(copiedLabel).assertIsDisplayed()
        runOnIdle { assertEquals(1, copies) }
        onNodeWithText(originalLabel).assertIsDisplayed().performClick()
        onNodeWithText(fit).assertIsDisplayed().performClick()
        onNodeWithText(originalLabel).assertIsDisplayed()
        runOnIdle { assertEquals(false, original.value) }
    }

    @Test
    fun exportButtonsRespectMutualExclusionAndSurfaceFailureWithoutActualIO() = runComposeUiTest {
        val busy = mutableStateOf(false)
        val pending = CompletableDeferred<ExportOutcome>()
        val errors = mutableListOf<String>()
        var htmlExports = 0
        var pdfExports = 0
        setContent {
            AppTheme {
                Row(Modifier.width(280.dp)) {
                    ExportActionButton(FeatherIcons.Download, "HTML", "Exporting HTML", "HTML saved",
                        !busy.value, LocalMederiColors.current, onExport = {
                            htmlExports++
                            busy.value = true
                            try { pending.await() } finally { busy.value = false }
                        }, onError = { errors += it })
                    ExportActionButton(FeatherIcons.Download, "PDF", "Exporting PDF", "PDF saved",
                        !busy.value, LocalMederiColors.current, onExport = {
                            pdfExports++
                            ExportOutcome(ExportStatus.CANCELLED)
                        }, onError = { errors += it })
                }
            }
        }
        onNodeWithText("HTML").performClick()
        onNodeWithText("Exporting HTML").assertIsNotEnabled()
        onNodeWithText("PDF").assertIsNotEnabled()
        runOnIdle {
            assertEquals(1, htmlExports)
            assertEquals(0, pdfExports)
            pending.complete(ExportOutcome(ExportStatus.WRITE_FAILED, "Permission denied"))
        }
        onNodeWithText("HTML").assertIsEnabled()
        onNodeWithText("PDF").assertIsEnabled().performClick()
        runOnIdle { assertEquals(listOf("Permission denied"), errors); assertEquals(1, pdfExports) }
        onNodeWithText("PDF saved").assertDoesNotExist()
    }

    @Test
    fun renderFileComponentsInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        val fixture = TreeFixture()
        setContent {
            AppTheme(mode.value) {
                Row(Modifier.width(960.dp).height(720.dp)
                    .background(LocalMederiColors.current.surfaceWorkspace).padding(24.dp).testTag("fileSample"),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.width(260.dp)) {
                        FilesPanelTabs(FilesTab.Tree, 3, {})
                        ProjectTreeContent(fixture.root, fixture, LocalMederiColors.current, {}, Modifier.weight(1f))
                    }
                    Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Changes", style = WorkspaceStyle.Title, color = LocalMederiColors.current.textPrimary)
                        TurnDiffSummaryCard(summary, {}, {})
                        Text("Narrow layout · 280 dp", color = LocalMederiColors.current.textSecondary,
                            style = WorkspaceStyle.Caption)
                        Box(Modifier.width(280.dp)) { TurnDiffSummaryCard(summary, {}, {}) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        ArtifactTabs(listOf(ArtifactItem.Text("report", "Report", ""),
                            ArtifactItem.Image("diagram", "Diagram", "fixture://image")),
                            "report", {}, LocalMederiColors.current)
                        TextReaderToolbar(null, 251, 8313, false, false, {}, LocalMederiColors.current)
                        Text("Document content stays the focus", style = WorkspaceStyle.Title,
                            color = LocalMederiColors.current.textPrimary)
                        Text("The main conversation remains available while independent tasks run.",
                            style = WorkspaceStyle.Body, color = LocalMederiColors.current.textSecondary)
                        ImageReaderToolbar("Architecture diagram", false, {}, LocalMederiColors.current)
                    }
                }
            }
        }
        onNode(hasText("source") and hasClickAction() and !hasSetTextAction()).performClick()
        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { mode.value = theme }
            onNodeWithText("Target.kt").assertIsDisplayed()
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { outputDir ->
                val directory = File(outputDir).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("fileSample").captureToImage().toAwtImage(), "png",
                    File(directory, "workspace-files-${theme.name.lowercase()}.png"))
            }
        }
    }
}
