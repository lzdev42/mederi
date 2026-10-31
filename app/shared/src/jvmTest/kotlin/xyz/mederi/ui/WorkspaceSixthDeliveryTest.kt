package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.*
import compose.icons.FeatherIcons
import compose.icons.feathericons.Terminal
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.*
import xyz.mederi.ui.components.*
import xyz.mederi.ui.components.atoms.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WorkspaceSixthDeliveryTest {
    @Test
    fun confirmAndCancelRemainSeparateInSharedDialog() = runComposeUiTest {
        var confirms = 0; var cancels = 0
        setContent { AppTheme {
            ConfirmDialog("Stop this task?", "Other tasks can continue.", "Stop task", "Cancel",
                { confirms++ }, { cancels++ }, danger = true)
        } }
        onNodeWithText("Cancel").performClick()
        runOnIdle { assertEquals(1, cancels); assertEquals(0, confirms) }
        onNodeWithText("Stop task").performClick()
        runOnIdle { assertEquals(1, confirms); assertEquals(1, cancels) }
    }

    @Test
    fun inputBlankIsDisabledAndTypedValueIsPreserved() = runComposeUiTest {
        val values = mutableListOf<String>(); var dismisses = 0
        setContent { AppTheme {
            InputDialog("Rename conversation", "", "Save", "Cancel", "Name", { values += it }, { dismisses++ })
        } }
        onNodeWithText("Save").assertIsNotEnabled()
        onNode(hasSetTextAction()).performTextInput("  New name  ")
        onNodeWithText("Save").assertIsEnabled().performClick()
        runOnIdle { assertEquals(listOf("  New name  "), values); assertEquals(0, dismisses) }
        onNodeWithText("Cancel").performClick()
        runOnIdle { assertEquals(1, dismisses) }
    }

    @Test
    fun largeFontLongDialogKeepsBothActionsReachableByScrolling() = runComposeUiTest {
        var clicked = false
        setContent { AppTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ConfirmDialog("Confirm this operation with a long descriptive title",
                    "A detailed explanation that wraps across several lines. ".repeat(12),
                    "Confirm the operation", "Keep working", { clicked = true }, {}, width = 280.dp)
            }
        } }
        onNodeWithText("Keep working").performScrollTo().assertIsDisplayed()
        onNodeWithText("Confirm the operation").performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun sharedMenuSelectionAndDismissAreIndependent() = runComposeUiTest {
        val selected = mutableStateOf("Alpha"); val open = mutableStateOf(true)
        var dismisses = 0
        setContent { AppTheme {
            Box {
                Text("Anchor")
                WorkspaceDropdownMenu(open.value, { open.value = false; dismisses++ }) {
                    listOf("Alpha", "Beta").forEach { value ->
                        WorkspaceMenuItem({ Text(value) }, { selected.value = value }, selected = selected.value == value)
                    }
                }
            }
        } }
        onNodeWithText("Alpha").assertIsSelected()
        onNodeWithText("Beta").performClick().assertIsSelected()
        runOnIdle { assertEquals("Beta", selected.value); assertEquals(0, dismisses) }
        // 平台弹层仍使用原生Escape dismiss。
        onNodeWithText("Beta").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithText("Beta").performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertEquals(1, dismisses) }
        onNodeWithText("Beta").assertDoesNotExist()
    }

    @Test
    fun dockSelectionAndTargetCallbacksDoNotDependOnTooltip() = runComposeUiTest {
        val current = mutableStateOf<RightDockPanel?>(RightDockPanel.TERMINAL)
        val choices = mutableListOf<RightDockPanel>(); var terminal = ""; var files = ""
        setContent { AppTheme {
            terminal = stringResource(Res.string.rightdock_terminal)
            files = stringResource(Res.string.rightdock_diff)
            RightDock(current.value, { choices += it; current.value = if (current.value == it) null else it },
                modifier = Modifier.height(320.dp))
        } }
        onNodeWithContentDescription(terminal).assertIsSelected().performClick().assertIsNotSelected()
        onNodeWithContentDescription(files).performClick().assertIsSelected()
        runOnIdle { assertEquals(listOf(RightDockPanel.TERMINAL, RightDockPanel.DIFF), choices) }
    }

    @Test
    fun helpCanBeOpenedAndClosedByClickAndKeyboard() = runComposeUiTest {
        var description = ""
        setContent { AppTheme {
            description = stringResource(Res.string.tooltip_content_description)
            HelpCircleTooltip("Continue chatting while tasks run", "Details stay in Overview")
        } }
        onNodeWithText("Continue chatting while tasks run").assertDoesNotExist()
        onNodeWithContentDescription(description).performClick()
        onNodeWithText("Continue chatting while tasks run").assertIsDisplayed()
        onNodeWithText("Details stay in Overview").assertIsDisplayed()
        onNodeWithContentDescription(description).performClick()
        onNodeWithText("Continue chatting while tasks run").assertDoesNotExist()
        onNodeWithContentDescription(description).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithContentDescription(description).performKeyInput { pressKey(Key.Enter) }
        onNodeWithText("Continue chatting while tasks run").assertIsDisplayed()
        onNodeWithContentDescription(description).performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Continue chatting while tasks run").assertDoesNotExist()
    }

    @Test
    fun tooltipPositionStaysValidForWindowEdgesAndOversizedContent() {
        for (placement in WorkspaceTooltipPlacement.entries) for (direction in LayoutDirection.entries) {
            val provider = WorkspaceTooltipPositionProvider(8, placement)
            for (size in listOf(IntSize(160, 70), IntSize(900, 900))) {
                for (anchor in listOf(IntRect(0, 0, 30, 30), IntRect(270, 270, 300, 300))) {
                    val offset = provider.calculatePosition(anchor, IntSize(300, 300), direction, size)
                    assertTrue(offset.x in 0..(300 - size.width).coerceAtLeast(0))
                    assertTrue(offset.y in 0..(300 - size.height).coerceAtLeast(0))
                }
            }
        }
    }

    @Test
    fun diagnosticActionsStaySeparateAndLogsRemainSelectable() = runComposeUiTest {
        var copies = 0; var bugs = 0; var closes = 0
        var copy = ""; var bug = ""; var close = ""
        setContent { AppTheme {
            copy = stringResource(Res.string.error_report_copy_logs)
            bug = stringResource(Res.string.error_report_submit_bug)
            close = stringResource(Res.string.close)
            MederiDialogContent(width = 280.dp) {
                ErrorDetailContent("API", "Request failed", "Retry the request", "Complete diagnostic log", null,
                    { copies++ }, { bugs++ }, { closes++ })
            }
        } }
        onNodeWithText("Complete diagnostic log").assertIsDisplayed()
        onNodeWithText(copy).performScrollTo().performClick()
        onNodeWithText(bug).performScrollTo().performClick()
        onNodeWithText(close).performScrollTo().performClick()
        runOnIdle { assertEquals(1, copies); assertEquals(1, bugs); assertEquals(1, closes) }
    }

    @Test
    fun renderSharedPopupComponentsInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        setContent { AppTheme(mode.value) {
            Row(Modifier.width(960.dp).height(700.dp).background(LocalMederiColors.current.surfaceWorkspace)
                .padding(16.dp).testTag("popupSample"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MederiDialogContent(modifier = Modifier.width(320.dp), width = 320.dp,
                    title = { Text("Stop this task?", style = WorkspaceStyle.Title, color = LocalMederiColors.current.textPrimary) }) {
                    Text("You can continue chatting while other tasks run.", style = WorkspaceStyle.Body,
                        color = LocalMederiColors.current.textSecondary)
                    FlowRow {
                        WorkspaceActionButton("Cancel", {})
                        WorkspaceActionButton("Stop task", {}, primary = true, tone = WorkspaceActionTone.Danger)
                    }
                }
                Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    WorkspaceTooltipContent("Task details", "Opening details does not stop the task.")
                    WorkspaceMenuItem({ Text("Model Alpha", style = WorkspaceStyle.Body) }, {}, selected = true)
                    WorkspaceMenuItem({ Text("Model Beta", style = WorkspaceStyle.Body) }, {})
                    DockIconButton(FeatherIcons.Terminal, "Terminal", true, {})
                }
                MederiDialogContent(modifier = Modifier.weight(1f), width = 320.dp) {
                    ErrorDetailContent("API", "The provider could not complete this request.", "Check the connection and retry.",
                        "HTTP 503\nProvider unavailable", null, {}, {}, {})
                }
            }
        } }
        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { mode.value = theme }
            onNodeWithText("Task details").assertIsDisplayed()
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("popupSample").captureToImage().toAwtImage(), "png",
                    File(directory, "workspace-popups-${theme.name.lowercase()}.png"))
            }
        }
    }
}
