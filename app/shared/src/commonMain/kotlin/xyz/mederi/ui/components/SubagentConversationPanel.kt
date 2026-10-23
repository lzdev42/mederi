package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.subagentui_stop_task
import mederi.app.shared.generated.resources.subagentui_status_running
import mederi.app.shared.generated.resources.subagentui_status_completed
import mederi.app.shared.generated.resources.subagentui_status_error
import mederi.app.shared.generated.resources.subagentui_status_stopped
import mederi.app.shared.generated.resources.subagentui_role_executor
import mederi.app.shared.generated.resources.subagentui_role_researcher
import mederi.app.shared.generated.resources.dock_close_panel
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.SubagentState
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.ChatListContent
import xyz.mederi.ui.ChatLayout
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.theme.rememberMederiMarkdownTheme
import xyz.mederi.ui.components.atoms.MederiRoleTag
import xyz.mederi.ui.components.atoms.MederiPanelHeaderIconButton

/**
 * 右侧子代理对话视图面板（无输入框）。
 *
 * 复用 [ChatListContent] 渲染子代理对话（首条=主代理指令，下面=子代理实时工作流），
 * 与主对话像素级一致，只是不挂 ChatInputCard 且用户消息无回滚按钮。
 * Header：角色标签 + mono 模型名 + 状态徽标 + 运行中停止钮 + 关闭钮。
 */
@Composable
fun SubagentConversationPanel(
    viewModel: WorkspaceViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val subagent = viewModel.activeSubagentPanel ?: return
    val appMarkdownTheme = rememberMederiMarkdownTheme()
    val lazyListState = rememberLazyListState()
    val isRunning = subagent.status.equals("RUNNING", ignoreCase = true)

    val statusLabel = when (subagent.status.uppercase()) {
        "RUNNING" -> stringResource(Res.string.subagentui_status_running)
        "COMPLETED" -> stringResource(Res.string.subagentui_status_completed)
        "ERROR" -> stringResource(Res.string.subagentui_status_error)
        else -> stringResource(Res.string.subagentui_status_stopped)
    }
    val roleLabel = if (subagent.role.equals("RESEARCHER", ignoreCase = true))
        stringResource(Res.string.subagentui_role_researcher)
    else stringResource(Res.string.subagentui_role_executor)
    val modelLabel = buildString {
        append(subagent.modelName.ifBlank { subagent.modelId })
        if (!subagent.reasoningLevel.isNullOrBlank()) append(" (${subagent.reasoningLevel})")
    }

    // 运行中自动滚底（简单 snapToBottom：chatItems 变化时贴底）
    LaunchedEffect(viewModel.subagentChatItems.size, viewModel.subagentChatItems.lastOrNull()?.key) {
        if (isRunning) {
            val last = viewModel.subagentChatItems.size - 1
            if (last >= 0) lazyListState.scrollToItem(last, 1_000_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(colors.surfaceWorkspace)
            .border(width = 1.dp, color = colors.divider)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(colors.surfaceSidebar)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MederiRoleTag(text = roleLabel)
                Text(
                    text = modelLabel,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = statusLabel,
                    color = if (isRunning) colors.accentPrimary else colors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRunning) {
                    MederiPanelHeaderIconButton(
                        icon = FeatherIcons.X,
                        onClick = { viewModel.stopSubagent(subagent.agentId) },
                        contentDescription = stringResource(Res.string.subagentui_stop_task),
                        colors = colors,
                    )
                }
                MederiPanelHeaderIconButton(
                    icon = FeatherIcons.X,
                    onClick = onDismiss,
                    contentDescription = stringResource(Res.string.dock_close_panel),
                    colors = colors,
                )
            }
        }
        HorizontalDivider(color = colors.divider)

        // Body：复用 ChatListContent（无输入框、用户消息无回滚按钮）
        Box(modifier = Modifier.fillMaxSize()) {
            ChatListContent(
                viewModel = viewModel,
                chatItems = viewModel.subagentChatItems,
                lazyListState = lazyListState,
                selectionMenuActions = emptyList(),
                appMarkdownTheme = appMarkdownTheme,
                modifier = Modifier.fillMaxSize(),
                contentMaxWidth = ChatLayout.contentMaxWidth,
                showUserMessageActions = false,
            )
        }
    }
}
