package xyz.mederi.ui.components

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.delay
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.RightDockPanel
import xyz.mederi.ui.ChatLayout
import xyz.mederi.theme.*
import xyz.mederi.ui.components.atoms.WorkspaceTooltipPlacement
import xyz.mederi.ui.components.atoms.WorkspaceTooltipPopup

data class DockItemData(val panel: RightDockPanel, val icon: ImageVector)

@Composable
fun RightDock(
    activePanel: RightDockPanel?,
    onSelectPanel: (RightDockPanel) -> Unit,
    hasRunningSubagents: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    // 2026-11：文件管理器（FILES，项目目录树）顶替原"文件查看器"按钮位；
    // 文档面板（ARTIFACTS）无 dock 按钮——由 openDocument / 概览"文档"行 / 对话流文档卡触达。
    val items = listOf(DockItemData(RightDockPanel.DIFF, FeatherIcons.GitCommit),
        DockItemData(RightDockPanel.FILES, FeatherIcons.Folder),
        DockItemData(RightDockPanel.TERMINAL, FeatherIcons.Terminal),
        DockItemData(RightDockPanel.BROWSER_AUTOMATION, FeatherIcons.Globe))
    Column(modifier.fillMaxHeight().width(ChatLayout.rightDockWidth)
        .background(colors.surfaceSidebar).padding(vertical = 10.dp, horizontal = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            val label = stringResource(when (item.panel) {
                RightDockPanel.DIFF -> Res.string.rightdock_diff
                RightDockPanel.FILES -> Res.string.rightdock_files
                RightDockPanel.ARTIFACTS -> Res.string.rightdock_artifacts
                RightDockPanel.TERMINAL -> Res.string.rightdock_terminal
                RightDockPanel.BROWSER_AUTOMATION -> Res.string.rightdock_browser_automation
                RightDockPanel.BROWSER -> Res.string.rightdock_browser
            })
            DockIconButton(item.icon, label, activePanel == item.panel, { onSelectPanel(item.panel) })
        }
    }
}

/** 面板选择只转发目标；选中与焦点同族反馈，无与子任务无关的脉冲角标。 */
@Composable
internal fun DockIconButton(icon: ImageVector, label: String, isActive: Boolean, onClick: () -> Unit) {
    val colors = LocalMederiColors.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    var tooltip by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, focused) {
        tooltip = false
        if (hovered || focused) { delay(WorkspaceStyle.TooltipDelayMillis); tooltip = true }
    }
    val background by animateColorAsState(when {
        isActive -> colors.accentBg
        hovered || focused -> colors.surfaceHover
        else -> Color.Transparent
    }, LocalWorkspaceMotion.current.feedbackSpec(), label = "dockFeedback")
    Box(Modifier.sizeIn(minWidth = WorkspaceStyle.IconTarget, minHeight = WorkspaceStyle.IconTarget)
        .clip(RoundedCornerShape(MederiRadius.Control)).background(background).hoverable(source)
        .semantics { contentDescription = label; selected = isActive }
        .onPreviewKeyEvent { if (it.key == Key.Escape && tooltip) { tooltip = false; true } else false }
        .clickable(interactionSource = source, indication = null, role = Role.Button) { tooltip = false; onClick() }
        .padding(6.dp), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (isActive) colors.accentText else colors.textSecondary,
            modifier = Modifier.size(WorkspaceStyle.IconSize))
        if (tooltip) WorkspaceTooltipPopup(label, placement = WorkspaceTooltipPlacement.Before, onDismiss = { tooltip = false })
    }
}
