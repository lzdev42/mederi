package xyz.mederi.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.RawMessagesViewModel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.hasRunningSubagents
import xyz.mederi.ui.settings.SessionAgentSettingsDialog

/** 每个会话独立的展开状态；运行态边界自动展开/收起，手动查看已结束任务不受重组干扰。 */
internal class SubagentPanelState {
    var expanded by mutableStateOf(false)
    private var wasRunning = false

    fun updateRunning(running: Boolean) {
        if (running != wasRunning) expanded = running
        wasRunning = running
    }
}

/** 子代理常驻于概览上方；两者共享有界的排版栈，隐藏概览不会隐藏子代理。 */
@Composable
fun WorkspaceAgentOverviewStack(
    viewModel: WorkspaceViewModel,
    rawVm: RawMessagesViewModel,
    state: OverviewWindowState,
    maxHeight: Dp,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val conversationId = viewModel.conversationId
    val panel = remember(conversationId) { SubagentPanelState() }
    val draftSettings = viewModel.draftSubagentSettings
    var showSettings by remember(conversationId, draftSettings) { mutableStateOf(false) }
    val running = hasRunningSubagents(viewModel.subagents)
    LaunchedEffect(running, panel) { panel.updateRunning(running) }
    val availableHeight = (maxHeight - 64.dp).coerceAtLeast(0.dp)
    val colors = LocalMederiColors.current
    val shape = RoundedCornerShape(WorkspaceStyle.ObjectRadius)
    Column(
        modifier.padding(top = 48.dp, end = 16.dp)
            .widthIn(max = (maxWidth - 16.dp).coerceAtLeast(0.dp)).width(292.dp)
            .heightIn(max = availableHeight).zIndex(10f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OverviewSubAgentRow(
            viewModel = viewModel,
            expanded = panel.expanded,
            onToggle = { panel.expanded = !panel.expanded },
            onOpenSettings = { showSettings = true },
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = if (state.visible) availableHeight * 0.5f else availableHeight)
                .clip(shape).background(colors.surfaceCard)
                .border(1.dp, colors.divider.copy(alpha = 0.55f), shape)
                .verticalScroll(rememberScrollState()).testTag("session-subagent-panel"),
        )
        FloatingOverviewWindow(viewModel, rawVm, state, availableHeight, maxWidth,
            modifier = Modifier.weight(1f, fill = false), inStack = true)
    }
    if (showSettings) {
        key(conversationId, draftSettings) {
            SessionAgentSettingsDialog(conversationId, draftSettings, onDismiss = { showSettings = false })
        }
    }
}
