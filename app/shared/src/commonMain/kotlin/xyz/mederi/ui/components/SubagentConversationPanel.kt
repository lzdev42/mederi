package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import compose.icons.FeatherIcons
import compose.icons.feathericons.Square
import compose.icons.feathericons.Users
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.SubagentState
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.ChatLayout
import xyz.mederi.ui.ChatListContent
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.ConfirmDialog
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspacePanelHeader

/** 只接线VM；详情继续使用主对话renderer与按agentId独立的阅读位置。 */
@Composable
fun SubagentConversationPanel(
    viewModel: WorkspaceViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subagent = viewModel.activeSubagentPanel ?: return
    val lazyListState = key(subagent.agentId) { rememberLazyListState() }
    SubagentPanelContent(subagent, onDismiss, viewModel::stopSubagent, modifier) {
        ChatListContent(
            viewModel = viewModel,
            chatItems = viewModel.subagentChatItems,
            lazyListState = lazyListState,
            selectionMenuActions = emptyList(),
            modifier = Modifier.fillMaxSize(),
            contentMaxWidth = ChatLayout.contentMaxWidth,
            showUserMessageActions = false,
        )
    }
}

/** 关闭只收起面板；停止必须经过确认，确认状态随目标切换复位。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubagentPanelContent(
    subagent: SubagentState,
    onDismiss: () -> Unit,
    onStop: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalMederiColors.current
    val isRunning = subagent.status.equals("RUNNING", ignoreCase = true)
    var showStopConfirm by remember(subagent.agentId) { mutableStateOf(false) }
    LaunchedEffect(isRunning) { if (!isRunning) showStopConfirm = false }
    if (showStopConfirm && isRunning) {
        ConfirmDialog(
            title = stringResource(Res.string.subagentui_stop_task_confirm_title),
            message = stringResource(Res.string.subagentui_stop_task_confirm_message),
            confirmLabel = stringResource(Res.string.subagentui_stop_task),
            cancelLabel = stringResource(Res.string.cancel),
            danger = true,
            confirmIcon = FeatherIcons.Square,
            onConfirm = {
                if (isRunning) onStop(subagent.agentId)
                showStopConfirm = false
            },
            onDismiss = { showStopConfirm = false },
        )
    }
    val statusLabel = when (subagent.status.uppercase()) {
        "RUNNING" -> stringResource(Res.string.subagentui_status_running)
        "COMPLETED" -> stringResource(Res.string.subagentui_status_completed)
        "ERROR" -> stringResource(Res.string.subagentui_status_error)
        "STOPPED" -> stringResource(Res.string.subagentui_status_stopped)
        else -> subagent.status
    }
    val roleLabel = when (subagent.role.uppercase()) {
        "RESEARCHER" -> stringResource(Res.string.subagentui_role_researcher)
        "EXECUTOR" -> stringResource(Res.string.subagentui_role_executor)
        else -> subagent.role
    }
    val modelLabel = buildString {
        append(subagent.modelName.ifBlank { subagent.modelId })
        if (!subagent.reasoningLevel.isNullOrBlank()) append(" (${subagent.reasoningLevel})")
    }
    Column(modifier.fillMaxHeight().background(colors.surfaceWorkspace)) {
        WorkspacePanelHeader(roleLabel, FeatherIcons.Users, onDismiss, subtitle = modelLabel)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MederiSpacing.SM),
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
            itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(statusLabel, style = WorkspaceStyle.Caption,
                color = when (subagent.status.uppercase()) {
                    "RUNNING" -> colors.accentText
                    "ERROR" -> colors.dangerText
                    else -> colors.textSecondary
                })
            if (isRunning) WorkspaceActionButton(
                text = stringResource(Res.string.subagentui_stop_task), icon = FeatherIcons.Square,
                onClick = { showStopConfirm = true }, contentColor = colors.dangerText,
            )
        }
        HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}
