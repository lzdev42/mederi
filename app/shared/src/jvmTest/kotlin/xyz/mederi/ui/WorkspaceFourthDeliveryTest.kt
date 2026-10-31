package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Terminal
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.SubagentState
import xyz.mederi.theme.AppTheme
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.browser.BrowserAutomationUiState
import xyz.mederi.ui.browser.BrowserFlowStep
import xyz.mederi.ui.browser.BrowserStepStatus
import xyz.mederi.ui.components.BrowserAutomationPanelContent
import xyz.mederi.ui.components.BrowserUnsupportedPlaceholder
import xyz.mederi.ui.components.SubagentPanelContent
import xyz.mederi.ui.components.TerminalEndedView
import xyz.mederi.ui.components.TerminalErrorView
import xyz.mederi.ui.components.TerminalTabs
import xyz.mederi.ui.components.TerminalUnsupportedPlaceholder
import xyz.mederi.ui.components.atoms.WorkspacePanelHeader
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 辅助面板的真实Compose交互；测试不创建PTY、网页宿主或Core任务。 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceFourthDeliveryTest {
    private fun agent(id: String = "agent-a", status: String = "RUNNING") = SubagentState(
        agentId = id, parentSessionId = "session-fixture", role = "RESEARCHER",
        modelId = "model-fixture", modelName = "A very long model identity with provider and deployment details",
        reasoningLevel = "HIGH", task = "Inspect the workspace without blocking the main conversation",
        briefing = null, status = status, startedAt = "2026-10-29T10:00:00Z",
    )

    @Test
    fun longIdentityAtNarrowWidthAndLargeFontKeepsCloseReachableWithoutStopping() = runComposeUiTest {
        var dismisses = 0
        val stopped = mutableListOf<String>()
        var close = ""
        setContent {
            AppTheme {
                close = stringResource(Res.string.dock_close_panel)
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    SubagentPanelContent(agent(), { dismisses++ }, { stopped += it },
                        Modifier.width(280.dp).height(420.dp).testTag("agentPanel")) {
                        Text("The conversation remains available")
                    }
                }
            }
        }
        val panel = onNodeWithTag("agentPanel").fetchSemanticsNode().boundsInRoot
        val button = onNodeWithContentDescription(close).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(button.left >= panel.left && button.right <= panel.right)
        onNodeWithContentDescription(close).performClick()
        runOnIdle { assertEquals(1, dismisses); assertTrue(stopped.isEmpty()) }
    }

    @Test
    fun stopRequiresConfirmationAndCancelDoesNotStopOrDismissPanel() = runComposeUiTest {
        val stopped = mutableListOf<String>()
        var dismisses = 0
        var stop = ""
        var cancel = ""
        var confirmTitle = ""
        setContent {
            AppTheme {
                stop = stringResource(Res.string.subagentui_stop_task)
                cancel = stringResource(Res.string.cancel)
                confirmTitle = stringResource(Res.string.subagentui_stop_task_confirm_title)
                SubagentPanelContent(agent(), { dismisses++ }, { stopped += it }, Modifier.width(320.dp).height(420.dp)) {}
            }
        }
        onNodeWithText(stop).performClick()
        onNodeWithText(confirmTitle).assertIsDisplayed()
        runOnIdle { assertTrue(stopped.isEmpty()) }
        onNodeWithText(cancel).performClick()
        onNodeWithText(confirmTitle).assertDoesNotExist()
        runOnIdle { assertTrue(stopped.isEmpty()); assertEquals(0, dismisses) }
        onNodeWithText(stop).performClick()
        onNode(hasText(stop) and hasAnyAncestor(isDialog())).performClick()
        runOnIdle { assertEquals(listOf("agent-a"), stopped); assertEquals(0, dismisses) }
    }

    @Test
    fun changingAgentClearsPendingConfirmationAndTerminalStatesOfferNoStop() = runComposeUiTest {
        val current = mutableStateOf(agent())
        val stopped = mutableListOf<String>()
        var stop = ""
        var confirmTitle = ""
        setContent {
            AppTheme {
                stop = stringResource(Res.string.subagentui_stop_task)
                confirmTitle = stringResource(Res.string.subagentui_stop_task_confirm_title)
                SubagentPanelContent(current.value, {}, { stopped += it }, Modifier.width(320.dp).height(420.dp)) {}
            }
        }
        onNodeWithText(stop).performClick()
        onNodeWithText(confirmTitle).assertIsDisplayed()
        runOnIdle { current.value = agent("agent-b") }
        onNodeWithText(confirmTitle).assertDoesNotExist()
        onNodeWithText(stop).performClick()
        onNode(hasText(stop) and hasAnyAncestor(isDialog())).performClick()
        runOnIdle { assertEquals(listOf("agent-b"), stopped) }
        for (status in listOf("COMPLETED", "ERROR", "STOPPED", "CUSTOM_STATE")) {
            runOnIdle { current.value = agent("agent-b", status) }
            onNodeWithText(stop).assertDoesNotExist()
        }
        onNodeWithText("CUSTOM_STATE").assertIsDisplayed()
    }

    @Test
    fun completedTaskDismissesAnOpenStopConfirmation() = runComposeUiTest {
        val current = mutableStateOf(agent())
        val stopped = mutableListOf<String>()
        var stop = ""
        var confirmTitle = ""
        setContent {
            AppTheme {
                stop = stringResource(Res.string.subagentui_stop_task)
                confirmTitle = stringResource(Res.string.subagentui_stop_task_confirm_title)
                SubagentPanelContent(current.value, {}, { stopped += it }, Modifier.width(320.dp).height(420.dp)) {}
            }
        }
        onNodeWithText(stop).performClick()
        onNodeWithText(confirmTitle).assertIsDisplayed()
        runOnIdle { current.value = current.value.copy(status = "COMPLETED") }
        onNodeWithText(confirmTitle).assertDoesNotExist()
        onNodeWithText(stop).assertDoesNotExist()
        runOnIdle { current.value = current.value.copy(status = "RUNNING") }
        onNodeWithText(confirmTitle).assertDoesNotExist()
        runOnIdle { assertTrue(stopped.isEmpty()) }
    }

    @Test
    fun terminalTabsKeepSelectCloseAndNewDistinctAndPreserveUnreadAndEndedFacts() = runComposeUiTest {
        val current = mutableStateOf("a")
        val selections = mutableListOf<String>()
        val closed = mutableListOf<String>()
        var created = 0
        var close = ""
        var add = ""
        var unread = ""
        var ended = ""
        val titles = mapOf("a" to "Shell A", "b" to "Shell B", "c" to "Shell C")
        setContent {
            AppTheme {
                close = stringResource(Res.string.term_close_tab)
                add = stringResource(Res.string.term_new_tab)
                unread = stringResource(Res.string.term_unread)
                ended = stringResource(Res.string.term_tab_ended)
                TerminalTabs(TerminalUiState(listOf(TerminalTab("a"), TerminalTab("b", unread = true),
                    TerminalTab("c", unread = true, ended = true)), current.value), titles,
                    LocalMederiColors.current, { current.value = it; selections += it }, { closed += it },
                    { created++ }, Modifier.width(600.dp))
            }
        }
        onNodeWithText("Shell A").assertIsSelected()
        onAllNodesWithContentDescription(unread).assertCountEquals(1)
        onNodeWithText(ended, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription("$close: Shell B").performClick()
        runOnIdle { assertEquals(listOf("b"), closed); assertTrue(selections.isEmpty()) }
        onNodeWithText("Shell B").performClick().assertIsSelected()
        onAllNodesWithContentDescription(unread).assertCountEquals(0)
        onNodeWithContentDescription(add).performClick()
        runOnIdle { assertEquals(listOf("b"), selections); assertEquals(1, created) }
    }

    @Test
    fun terminalRecoveryActionsAreExplicitAndCapabilityAbsenceHasNoFakeAction() = runComposeUiTest {
        val state = mutableStateOf("error")
        var retries = 0
        var restarts = 0
        var retry = ""
        var restart = ""
        var unavailable = ""
        var browserUnavailable = ""
        setContent {
            AppTheme {
                retry = stringResource(Res.string.term_click_retry)
                restart = stringResource(Res.string.term_reopen)
                unavailable = stringResource(Res.string.term_unavailable)
                browserUnavailable = stringResource(Res.string.dock_browser_unsupported)
                Box(Modifier.width(280.dp).height(320.dp)) {
                    when (state.value) {
                        "error" -> TerminalErrorView("Shell creation failed", LocalMederiColors.current, { retries++ })
                        "ended" -> TerminalEndedView(LocalMederiColors.current, { restarts++ })
                        "unsupported" -> TerminalUnsupportedPlaceholder(LocalMederiColors.current)
                        else -> BrowserUnsupportedPlaceholder(LocalMederiColors.current)
                    }
                }
            }
        }
        onNodeWithText("Shell creation failed").assertIsDisplayed()
        onNodeWithText(retry).performClick()
        runOnIdle { assertEquals(1, retries); assertEquals(0, restarts); state.value = "ended" }
        onNodeWithText(restart).performClick()
        runOnIdle { assertEquals(1, restarts); state.value = "unsupported" }
        onNodeWithText(unavailable).assertIsDisplayed()
        onAllNodes(hasClickAction()).assertCountEquals(0)
        runOnIdle { state.value = "browser" }
        onNodeWithText(browserUnavailable).assertIsDisplayed()
        onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    private val screenshot = BrowserFlowStep.Screenshot("screenshot-fixture",
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=",
        "Captured page", "1 × 1")

    private fun browserState() = BrowserAutomationUiState(
        isAutomating = true, isPanelOpen = false, taskId = "browser-fixture", taskSummary = "Inspect the local page",
        liveReasoning = "Check the current page structure", liveText = "Found the requested section",
        steps = listOf(BrowserFlowStep.Action("read-page", title = "Read current page", status = BrowserStepStatus.COMPLETED),
            screenshot),
    )

    @Test
    fun automationDockShowsWorkWhileFloatingPanelIsClosedAndKeepsStopAndPreviewIndependent() = runComposeUiTest {
        val state = mutableStateOf(browserState())
        var stops = 0
        val previews = mutableListOf<BrowserFlowStep.Screenshot>()
        var stop = ""
        var failed = ""
        setContent {
            AppTheme {
                stop = stringResource(Res.string.browser_automation_stop_task)
                failed = stringResource(Res.string.browser_flow_step_failed)
                BrowserAutomationPanelContent(state.value, LocalMederiColors.current, { stops++ }, { previews += it },
                    Modifier.width(360.dp).height(600.dp))
            }
        }
        onNodeWithText("Inspect the local page").assertIsDisplayed()
        onNodeWithText("Check the current page structure").assertIsDisplayed()
        onNodeWithText("Found the requested section").assertIsDisplayed()
        onNodeWithText("Captured page").performScrollTo().performClick()
        runOnIdle { assertEquals(listOf(screenshot), previews); assertEquals(0, stops) }
        onNodeWithText(stop).assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(1, stops)
            state.value = state.value.copy(isAutomating = false, completedAtMillis = 2000,
                steps = listOf(BrowserFlowStep.Action("failed-action", title = "Page navigation failed", status = BrowserStepStatus.FAILED)))
        }
        onNodeWithText(stop).assertDoesNotExist()
        onNodeWithText("Page navigation failed").assertIsDisplayed()
        onNodeWithText(failed).assertIsDisplayed()
    }

    @Test
    fun emptyAutomationDockStatesItsAbsenceWithoutInventingCompletedWork() = runComposeUiTest {
        var empty = ""
        var finished = ""
        var stop = ""
        setContent {
            AppTheme {
                empty = stringResource(Res.string.browser_automation_empty)
                finished = stringResource(Res.string.browser_automation_finished)
                stop = stringResource(Res.string.browser_automation_stop_task)
                BrowserAutomationPanelContent(BrowserAutomationUiState(), LocalMederiColors.current, {}, {},
                    Modifier.width(280.dp).height(320.dp))
            }
        }
        onNodeWithText(empty).assertIsDisplayed()
        onNodeWithText(finished).assertDoesNotExist()
        onNodeWithText(stop).assertDoesNotExist()
    }

    @Test
    fun renderAuxiliaryPanelComponentsInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        setContent {
            AppTheme(mode.value) {
                Row(Modifier.width(1120.dp).height(720.dp)
                    .background(LocalMederiColors.current.surfaceWorkspace).padding(20.dp).testTag("auxiliarySample"),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    SubagentPanelContent(agent(), {}, {}, Modifier.width(300.dp)) {
                        Text("The researcher is working independently. You can keep chatting in the main conversation.",
                            color = LocalMederiColors.current.textSecondary, style = WorkspaceStyle.Body,
                            modifier = Modifier.padding(12.dp))
                    }
                    Column(Modifier.width(340.dp)) {
                        WorkspacePanelHeader("Terminal", FeatherIcons.Terminal, {})
                        TerminalTabs(TerminalUiState(listOf(TerminalTab("a"), TerminalTab("b", unread = true)), "a"),
                            mapOf("a" to "Shell A", "b" to "Shell B"), LocalMederiColors.current, {}, {}, {})
                        TerminalErrorView("Unable to create the shell session", LocalMederiColors.current, {}, Modifier.weight(1f))
                    }
                    BrowserAutomationPanelContent(browserState(), LocalMederiColors.current, {}, {}, Modifier.weight(1f))
                }
            }
        }
        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { mode.value = theme }
            onNodeWithText("Inspect the local page").assertIsDisplayed()
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { outputDir ->
                val directory = File(outputDir).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("auxiliarySample").captureToImage().toAwtImage(), "png",
                    File(directory, "workspace-auxiliary-${theme.name.lowercase()}.png"))
            }
        }
    }
}
