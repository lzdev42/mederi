package xyz.mederi.ui.components.atoms

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import compose.icons.FeatherIcons
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.dock_close_panel
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.*

/** 面板/工具栏共用紧凑图标动作；只转发点击，不持有关闭或停止业务状态。 */
@Composable
internal fun WorkspaceIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: MederiColors = LocalMederiColors.current,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val background by animateColorAsState(
        if (enabled && (hovered || focused)) colors.surfaceHover else Color.Transparent,
        LocalWorkspaceMotion.current.feedbackSpec(), label = "workspaceIconBackground",
    )
    Box(
        modifier = modifier.sizeIn(minWidth = WorkspaceStyle.IconTarget, minHeight = WorkspaceStyle.IconTarget)
            .clip(RoundedCornerShape(MederiRadius.Control)).background(background).hoverable(source)
            .semantics { this.contentDescription = contentDescription }
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(MederiSpacing.Tight),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) colors.textSecondary else colors.textMuted,
            modifier = Modifier.size(WorkspaceStyle.IconSize))
    }
}

/** 单层身份与固定关闭入口；长标题只压缩自己，不挤掉动作。 */
@Composable
internal fun WorkspacePanelHeader(
    title: String,
    icon: ImageVector,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    colors: MederiColors = LocalMederiColors.current,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.XS)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
            Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(WorkspaceStyle.IconSize))
            Text(title, style = WorkspaceStyle.Title, color = colors.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            actions()
            WorkspaceIconButton(FeatherIcons.X, onClose, stringResource(Res.string.dock_close_panel), colors = colors)
        }
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = WorkspaceStyle.Caption, color = colors.textMuted,
            modifier = Modifier.fillMaxWidth().padding(start = MederiSpacing.XL), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 能力缺失/无内容/加载与恢复共用安静空态，真实动作由调用方传入。 */
@Composable
internal fun WorkspacePanelEmptyState(
    icon: ImageVector,
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    colors: MederiColors = LocalMederiColors.current,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().padding(MederiSpacing.XXL),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = colors.textMuted, modifier = Modifier.size(WorkspaceStyle.IconSize))
        Spacer(Modifier.height(MederiSpacing.MD))
        Text(title, style = WorkspaceStyle.Title, color = colors.textSecondary, textAlign = TextAlign.Center)
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(MederiSpacing.XS))
            Text(hint, style = WorkspaceStyle.Caption, color = colors.textMuted, textAlign = TextAlign.Center)
        }
        if (action != null) { Spacer(Modifier.height(MederiSpacing.SM)); action() }
    }
}
