package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.browser.BrowserAutomationUiState
import xyz.mederi.ui.browser.BrowserFlowStep
import xyz.mederi.ui.browser.BrowserStepStatus
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspacePanelEmptyState

/** Dock内容独立于浮动条的展开状态；关闭面板不改变任务事实。 */
@Composable
fun BrowserAutomationDockPanel(viewModel: WorkspaceViewModel, colors: MederiColors) {
    BrowserAutomationPanelContent(
        state = viewModel.browserAutomationState,
        colors = colors,
        onStopTask = viewModel::stopBrowserAutomation,
        onPreviewScreenshot = viewModel::openBrowserScreenshotPreview,
    )
}

/** 只接收事实与回调的完整流程面板，Dock与浮动详情复用同一内容。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowserAutomationPanelContent(
    state: BrowserAutomationUiState,
    colors: MederiColors,
    onStopTask: () -> Unit,
    onPreviewScreenshot: (BrowserFlowStep.Screenshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasContent = state.isAutomating || state.steps.isNotEmpty() ||
        !state.taskSummary.isNullOrBlank() || state.liveReasoning.isNotBlank() || state.liveText.isNotBlank()
    if (!hasContent) {
        WorkspacePanelEmptyState(
            icon = FeatherIcons.Globe,
            title = stringResource(Res.string.browser_automation_empty),
            hint = stringResource(Res.string.browser_automation_empty_hint),
            modifier = modifier,
            colors = colors,
        )
        return
    }
    val elapsed = rememberBrowserAutomationElapsed(state)
    val actionSteps = state.steps.filterIsInstance<BrowserFlowStep.Action>()
    val completedSteps = actionSteps.count { it.status == BrowserStepStatus.COMPLETED }
    Column(modifier = modifier.fillMaxSize().background(colors.surfaceWorkspace)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.XS),
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    state.isAutomating -> stringResource(Res.string.browser_flow_running_locked)
                    state.completedAtMillis != null -> stringResource(Res.string.browser_automation_finished)
                    else -> stringResource(Res.string.status_idle)
                },
                style = WorkspaceStyle.Title,
                color = if (state.isAutomating) colors.accentText else colors.textSecondary,
            )
            if (elapsed != null) Text(elapsed, style = WorkspaceStyle.Caption, color = colors.textMuted)
            if (actionSteps.isNotEmpty()) {
                Text("$completedSteps/${actionSteps.size}", style = WorkspaceStyle.Caption, color = colors.textMuted)
            }
            if (state.isAutomating) {
                WorkspaceActionButton(
                    text = stringResource(Res.string.browser_automation_stop_task),
                    onClick = onStopTask,
                    icon = FeatherIcons.Square,
                    contentColor = colors.dangerText,
                    colors = colors,
                )
            }
        }
        if (!state.taskSummary.isNullOrBlank()) {
            Text(state.taskSummary, style = WorkspaceStyle.Body, color = colors.textPrimary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.XS))
        }
        // 流式推理/输出保持固定区并独立滚动，不挤入不断增长的步骤列表。
        if (state.liveReasoning.isNotBlank() || state.liveText.isNotBlank()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.SM)
                    .heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
            ) {
                if (state.liveReasoning.isNotBlank()) {
                    BrowserAutomationSectionTitle(stringResource(Res.string.browser_automation_reasoning_title), colors)
                    Text(state.liveReasoning, style = WorkspaceStyle.Body, color = colors.textSecondary)
                }
                if (state.liveText.isNotBlank()) {
                    BrowserAutomationSectionTitle(stringResource(Res.string.browser_automation_output_title), colors)
                    Text(state.liveText, style = WorkspaceStyle.Body, color = colors.textPrimary)
                }
            }
        }
        val listState = rememberLazyListState()
        // 只在读者停留列表底部时跟随新增记录，翻阅历史不被新步骤拉走。
        var followLatest by remember { mutableStateOf(true) }
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canForward) ->
                if (scrolling || !canForward) followLatest = !canForward
            }
        }
        LaunchedEffect(state.steps.size) {
            if (followLatest && state.steps.isNotEmpty()) listState.animateScrollToItem(state.steps.size)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(MederiSpacing.MD),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        ) {
            item(key = "browser_steps_header") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
                    Icon(FeatherIcons.List, null, tint = colors.textMuted, modifier = Modifier.size(WorkspaceStyle.IconSize))
                    BrowserAutomationSectionTitle(stringResource(Res.string.browser_automation_steps_title), colors)
                    Text("${state.steps.size}", style = WorkspaceStyle.Caption, color = colors.textMuted)
                }
                if (state.steps.isEmpty()) {
                    Text(stringResource(Res.string.browser_flow_waiting_steps),
                        style = WorkspaceStyle.Caption, color = colors.textMuted)
                }
            }
            itemsIndexed(state.steps, key = { _, step -> step.id }) { index, step ->
                BrowserFlowTimelineRow(
                    step = step,
                    isLast = index == state.steps.lastIndex,
                    colors = colors,
                    onPreviewScreenshot = onPreviewScreenshot,
                )
            }
        }
    }
}

@Composable
private fun BrowserAutomationSectionTitle(title: String, colors: MederiColors) {
    Text(title, style = WorkspaceStyle.Caption, color = colors.textMuted)
}
