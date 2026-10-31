package xyz.mederi.ui.components.atoms

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.text.style.TextOverflow
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.WorkspaceStyle

/** 文件与阅读器共用导航项；选择由调用方持有，组件只表达选择与焦点。 */
@Composable
internal fun WorkspaceNavigationTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    detail: String? = null,
    role: Role = Role.Tab,
) {
    val colors = LocalMederiColors.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val background by animateColorAsState(
        when {
            selected -> colors.accentBg
            hovered || focused -> colors.surfaceHover
            else -> Color.Transparent
        }, LocalWorkspaceMotion.current.feedbackSpec(), label = "workspaceTabBackground",
    )
    Row(
        modifier = modifier.widthIn(max = WorkspaceStyle.NavigationTabMaxWidth).heightIn(min = WorkspaceStyle.ControlHeight)
            .clip(RoundedCornerShape(MederiRadius.Control)).background(background).hoverable(source)
            .selectable(selected = selected, role = role, interactionSource = source,
                indication = null, onClick = onClick)
            .padding(horizontal = MederiSpacing.Compact, vertical = MederiSpacing.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.Tight),
    ) {
        val foreground = if (selected) colors.accentText else colors.textSecondary
        if (icon != null) Icon(icon, null, tint = foreground, modifier = Modifier.size(WorkspaceStyle.IconSize))
        Text(text, color = foreground, style = if (selected) WorkspaceStyle.Title else WorkspaceStyle.Body,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (!detail.isNullOrEmpty()) Text(detail, color = colors.textSecondary, style = WorkspaceStyle.Caption,
            maxLines = 1)
    }
}
