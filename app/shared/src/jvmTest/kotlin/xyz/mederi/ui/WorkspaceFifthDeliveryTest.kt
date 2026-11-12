package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.TodoItem
import xyz.mederi.core.contract.models.TodoStatus
import xyz.mederi.ui.overview.OverviewTodoContent
import xyz.mederi.ui.overview.OverviewPlanItemContent
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.core.contract.models.ProcessStats
import xyz.mederi.theme.AppTheme
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.components.SystemInfoBar
import xyz.mederi.ui.components.SystemInfoContent
import xyz.mederi.ui.overview.OverviewAccordionRow
import xyz.mederi.ui.overview.RawMessageItemContent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 诊断与概览步骤的真实组件回归；预览为组件组合，不启动APP或采集进程指标。 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceFifthDeliveryTest {
    private val stats = ProcessStats(cpuUsage = 0.25, cpuCores = 8, heapUsedBytes = 128L * 1024 * 1024,
        heapMaxBytes = 1024L * 1024 * 1024, rssBytes = 256L * 1024 * 1024)
    private val prettyJson = """
        {
            "role": "assistant",
            "text": "Continue chatting while the task runs"
        }
    """.trimIndent()

    @Test
    fun resourceBarStartsCollapsedAndOnlyRevealsMetricsWhenExpanded() = runComposeUiTest {
        var title = ""
        var memory = ""
        setContent {
            AppTheme {
                title = stringResource(Res.string.sysinfo_resources)
                memory = stringResource(Res.string.sysinfo_memory_rss)
                SystemInfoBar(stats, Modifier.width(360.dp))
            }
        }
        onNodeWithText(title).assertIsDisplayed()
        onNodeWithText(memory).assertDoesNotExist()
        onNodeWithText(title).performSemanticsAction(SemanticsActions.Expand) { it() }
        onNodeWithText(memory).assertIsDisplayed()
        onNodeWithText("256.0 MB").assertIsDisplayed()
        onNodeWithText(title).performSemanticsAction(SemanticsActions.Collapse) { it() }
        onNodeWithText(memory).assertDoesNotExist()
    }

    @Test
    fun liveMetricUpdatesDoNotChangeTheCallersExpansionState() = runComposeUiTest {
        val current = mutableStateOf(stats)
        val expanded = mutableStateOf(true)
        val expansionChanges = mutableListOf<Boolean>()
        setContent {
            AppTheme {
                SystemInfoContent(current.value, expanded.value,
                    { expansionChanges += it; expanded.value = it }, Modifier.width(360.dp))
            }
        }
        onNodeWithText("256.0 MB").assertIsDisplayed()
        onNodeWithText("128.0 MB / 1.0 GB").assertIsDisplayed()
        onAllNodesWithText("25.0%", substring = true).fetchSemanticsNodes().forEach { node ->
            val labels = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
            assertTrue(labels.none { it.text.contains('"') })
        }
        runOnIdle { current.value = stats.copy(cpuUsage = 0.5, rssBytes = 512L * 1024 * 1024) }
        onNodeWithText("512.0 MB").assertIsDisplayed()
        onNodeWithText("256.0 MB").assertDoesNotExist()
        onAllNodesWithText("50.0%", substring = true).assertCountEquals(2)
        runOnIdle { assertTrue(expanded.value); assertTrue(expansionChanges.isEmpty()) }
    }

    @Test
    fun unavailableMetricsStayExplicitAtNarrowWidthAndLargeFont() = runComposeUiTest {
        var memory = ""
        var heap = ""
        setContent {
            AppTheme {
                memory = stringResource(Res.string.sysinfo_memory_rss)
                heap = stringResource(Res.string.sysinfo_heap)
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    SystemInfoContent(null, true, {}, Modifier.width(280.dp).testTag("metrics"))
                }
            }
        }
        onNodeWithText(memory).assertIsDisplayed()
        onNodeWithText(heap).assertIsDisplayed()
        onAllNodesWithText("—").assertCountEquals(3)
        val panel = onNodeWithTag("metrics").fetchSemanticsNode().boundsInRoot
        for (label in listOf(memory, heap)) {
            val metric = onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue(metric.left >= panel.left && metric.right <= panel.right)
        }
        onNodeWithText("0 B").assertDoesNotExist()
    }

    @Test
    fun rawMessageExpandAndCopyAreSeparateAndCopyPreservesFormattedContent() = runComposeUiTest {
        val expanded = mutableStateOf(false)
        val copied = mutableStateOf(false)
        val copies = mutableListOf<String>()
        var copy = ""
        var done = ""
        setContent {
            AppTheme {
                copy = stringResource(Res.string.copy)
                done = stringResource(Res.string.copy_done)
                RawMessageItemContent("Assistant · #5", "10:42", "128 tokens", prettyJson, expanded.value,
                    { expanded.value = !expanded.value }, { copies += prettyJson; copied.value = true }, copied.value,
                    Modifier.width(280.dp))
            }
        }
        onNodeWithText(prettyJson).assertDoesNotExist()
        onNodeWithText(copy).assertDoesNotExist()
        onNodeWithText("Assistant · #5").performSemanticsAction(SemanticsActions.Expand) { it() }
        onNodeWithText(prettyJson).assertIsDisplayed()
        onNodeWithText("10:42 · 128 tokens").assertIsDisplayed()
        onNodeWithText(copy).performClick()
        onNodeWithText(done).assertIsDisplayed()
        onNodeWithText(prettyJson).assertIsDisplayed()
        runOnIdle { assertEquals(listOf(prettyJson), copies); assertTrue(expanded.value) }
        onNodeWithText("Assistant · #5").performSemanticsAction(SemanticsActions.Collapse) { it() }
        onNodeWithText(prettyJson).assertDoesNotExist()
    }

    @Test
    fun accordionSupportsKeyboardAndSemanticDisclosureWithoutChangingItsContent() = runComposeUiTest {
        val expanded = mutableStateOf(false)
        setContent {
            AppTheme {
                OverviewAccordionRow(FeatherIcons.Activity, "Diagnostics", {}, expanded.value,
                    { expanded.value = !expanded.value }, Modifier.width(280.dp)) {
                    Text("Actual diagnostic details")
                }
            }
        }
        onNodeWithText("Diagnostics").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithText("Diagnostics").performKeyInput { pressKey(Key.Enter) }
        onNodeWithText("Actual diagnostic details").assertIsDisplayed()
        onNodeWithText("Diagnostics").performSemanticsAction(SemanticsActions.Collapse) { it() }
        onNodeWithText("Actual diagnostic details").assertDoesNotExist()
        onNodeWithText("Diagnostics").performSemanticsAction(SemanticsActions.Expand) { it() }
        onNodeWithText("Actual diagnostic details").assertIsDisplayed()
    }

    private fun plan(id: String = "plan-a", status: PlanOverviewStatus = PlanOverviewStatus.PendingApproval) = PlanOverviewItem(
        id, "A plan with a long title that can wrap without hiding the actions", "", "/plan.md", status,
        (0..3).map { PlanSubtaskOverview(it, "Step ${it + 1}", if (it == 0) "CUSTOM_STATE" else "PENDING",
            it == 0) })

    @Test
    fun todoShowsFiveRealStatesAndDisappearsWhenEmpty() = runComposeUiTest {
        val todos = mutableStateOf(TodoStatus.entries.map { TodoItem(it.name, "Task ${it.name}", it) })
        var labels = emptyList<String>()
        setContent { AppTheme {
            labels = listOf(Res.string.overview_todo_pending, Res.string.overview_todo_in_progress,
                Res.string.overview_todo_completed, Res.string.overview_todo_cancelled, Res.string.overview_todo_failed)
                .map { stringResource(it) }
            OverviewTodoContent(todos.value, true, {}, Modifier.width(320.dp))
        } }
        labels.forEach { onNodeWithText(it).assertIsDisplayed() }
        onNodeWithText("1/5").assertIsDisplayed()
        runOnIdle { todos.value = emptyList() }
        labels.forEach { onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun planActionsKeepTargetsAndExpansionResetsForAnotherPlan() = runComposeUiTest {
        val current = mutableStateOf(plan())
        var approvals = 0; var opens = 0
        val specs = mutableListOf<PlanSubtaskOverview>()
        var approve = ""; var read = ""; var spec = ""; var expand = ""
        var report = ""
        var reportTitle = ""
        var openedReport: Pair<String, String>? = null
        setContent { AppTheme {
            approve = stringResource(Res.string.dock_plan_approve)
            read = stringResource(Res.string.plan_read_full)
            spec = stringResource(Res.string.step_spec)
            expand = stringResource(Res.string.worktrace_expand)
            report = stringResource(Res.string.plan_open_acceptance_report)
            reportTitle = stringResource(Res.string.plan_acceptance_report_title, current.value.title)
            val item = current.value
            OverviewPlanItemContent(
                item, { approvals++ }, { opens++ }, { specs += it }, Modifier.width(320.dp),
                onOpenAcceptanceReport = { openedReport = item.id to it },
            )
        } }
        onNodeWithText("CUSTOM_STATE").assertIsDisplayed()
        onNodeWithText(report).assertDoesNotExist()
        onNodeWithText(read).performClick()
        onNodeWithText(spec).performClick()
        runOnIdle { assertEquals(1, opens); assertEquals(listOf(current.value.subtasks[0]), specs); assertEquals(0, approvals) }
        onNodeWithText(approve).performClick()
        runOnIdle { assertEquals(1, approvals) }
        onNodeWithText("Step 4", substring = true).assertDoesNotExist()
        onNodeWithText(expand).performSemanticsAction(SemanticsActions.Expand) { it() }
        onNodeWithText("Step 4", substring = true).assertIsDisplayed()
        runOnIdle { current.value = plan("plan-b", PlanOverviewStatus.Completed) }
        onNodeWithText(approve).assertDoesNotExist()
        onNodeWithText(report).performClick()
        runOnIdle { assertEquals("plan-b" to reportTitle, openedReport) }
        onNodeWithText("Step 4", substring = true).assertDoesNotExist()
    }

    @Test
    fun longRawIdentityAtLargeFontKeepsCopyOutsideJsonAndInsidePanel() = runComposeUiTest {
        var copy = ""
        setContent { AppTheme {
            copy = stringResource(Res.string.copy)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                RawMessageItemContent("Assistant response with an extremely long provider and model identity", "10:42", "128 tokens",
                    prettyJson, true, {}, {}, modifier = Modifier.width(280.dp).testTag("raw"))
            }
        } }
        val panel = onNodeWithTag("raw").fetchSemanticsNode().boundsInRoot
        val button = onNodeWithText(copy).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val json = onNodeWithText(prettyJson).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(button.left >= panel.left && button.right <= panel.right)
        assertTrue(button.bottom <= json.top)
    }

    @Test
    fun renderDiagnosticsAndTaskComponentsInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        setContent { AppTheme(mode.value) {
            Row(Modifier.width(1080.dp).height(760.dp).background(LocalMederiColors.current.surfaceWorkspace)
                .padding(20.dp).testTag("diagnosticsSample"), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Diagnostics", style = WorkspaceStyle.Title, color = LocalMederiColors.current.textPrimary)
                    SystemInfoContent(stats, true, {})
                    RawMessageItemContent("Assistant · #5", "10:42", "128 tokens", prettyJson, true, {}, {})
                }
                Column(Modifier.width(290.dp)) {
                    OverviewTodoContent(TodoStatus.entries.map { TodoItem(it.name, "Task ${it.name}", it) }, true, {})
                }
                OverviewPlanItemContent(plan(), {}, {}, {}, Modifier.weight(1f), onOpenAcceptanceReport = {})
            }
        } }
        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { mode.value = theme }
            onNodeWithText("CUSTOM_STATE").assertIsDisplayed()
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("diagnosticsSample").captureToImage().toAwtImage(), "png",
                    File(directory, "workspace-diagnostics-${theme.name.lowercase()}.png"))
            }
        }
    }
}
