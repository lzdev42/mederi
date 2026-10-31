package xyz.mederi.ui.overview

import xyz.mederi.theme.LocalWorkspaceMotion
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import xyz.mederi.theme.WorkspaceStyle
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import xyz.mederi.ui.components.atoms.expansionSemantics
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.MederiTypeScale

/**
 * 概览共用折叠行：以留白和轻背景分组，只有必要时显式添加分隔线。
 * 整行可键盘操作，展开状态有语义，箭头和内容使用同一180ms节奏。
 */
@Composable
fun OverviewAccordionRow(
    icon: ImageVector,                         // 行图标（FeatherIcons.*）
    title: String,                             // 行标题
    summary: @Composable RowScope.() -> Unit,  // 折叠态右侧摘要（slot）
    expanded: Boolean,                         // 是否展开
    onToggle: () -> Unit,                      // 点击行头切换展开
    modifier: Modifier = Modifier,
    showTopDivider: Boolean = false,            // false = 第一行不画顶部分隔线
    content: @Composable () -> Unit,           // 展开内容
) {
    val colors = LocalMederiColors.current

    // hover 底色：常态透明，鼠标移入才浮出
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = when {
            isHovered || isFocused -> colors.surfaceHover
            expanded -> colors.surfaceHover.copy(alpha = 0.45f)
            else -> Color.Transparent
        },
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "overviewRowBackground",
    )
    val angle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = LocalWorkspaceMotion.current.disclosureSpec(),
        label = "overviewChevron",
    )


    Column(modifier = modifier) {
        if (showTopDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.divider)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(background)
                .expansionSemantics(expanded) { if (it != expanded) onToggle() }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onToggle,
                )
                .padding(horizontal = MederiSpacing.MD),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.Tight),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = title,
                color = colors.textPrimary,
                style = WorkspaceStyle.Title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            summary()
            Icon(
                imageVector = FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = angle },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else expandVertically(animationSpec = LocalWorkspaceMotion.current.disclosureSpec()),
            exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else shrinkVertically(animationSpec = LocalWorkspaceMotion.current.disclosureSpec()),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = MederiSpacing.MD,
                    end = MederiSpacing.MD,
                    top = MederiSpacing.XS,
                    bottom = MederiSpacing.MD,
                )
            ) {
                content()
            }
        }
    }
}