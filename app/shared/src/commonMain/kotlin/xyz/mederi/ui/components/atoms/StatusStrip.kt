package xyz.mederi.ui.components.atoms

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.WorkspaceStyle

/** 一次性反馈的轻量行；状态由图标表达，不为短提示重复绘制卡框或持续动画。 */
@Composable
fun StatusStrip(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconTint: Color = LocalMederiColors.current.textSecondary,
    iconContentDescription: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalMederiColors.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (onAction != null && (hovered || focused)) colors.surfaceHover else Color.Transparent,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "statusNoticeBackground",
    )
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = WorkspaceStyle.ControlHeight)
            .clip(RoundedCornerShape(6.dp)).background(background)
            .then(if (onAction != null) Modifier.clickable(
                interactionSource = source, indication = null, role = Role.Button, onClick = onAction,
            ) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, iconContentDescription, tint = iconTint, modifier = Modifier.size(WorkspaceStyle.IconSize))
        Text(text = text, color = colors.textPrimary, style = WorkspaceStyle.Caption,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (actionLabel != null) {
            Text(actionLabel, color = colors.accentText, style = WorkspaceStyle.Caption)
            Icon(FeatherIcons.ChevronRight, null, tint = colors.accentText, modifier = Modifier.size(12.dp))
        }
    }
}
