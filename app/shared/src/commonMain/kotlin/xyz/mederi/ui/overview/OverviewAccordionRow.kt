package xyz.mederi.ui.overview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * 概览浮动窗的手风琴行壳（8 个行共用）。
 *
 * 折叠态：34dp 高行头（icon + 标题 + 弹性占位 + 右侧摘要 slot + chevron），
 * hover 时浮出 `surfaceHover` 底色（参照 ProcessRow / MederiCards 的
 * MutableInteractionSource + collectIsHoveredAsState 范式）；顶部 1dp divider 分隔线
 * 由 [showTopDivider] 控制（容器层第一行传 false 不画）。
 *
 * 展开态：AnimatedVisibility(expandVertically / shrinkVertically, tween(180)) 平滑展开，
 * 内容区左右 12dp、下 12dp 内边距。
 */
@Composable
fun OverviewAccordionRow(
    icon: ImageVector,                         // 行图标（FeatherIcons.*）
    title: String,                             // 行标题
    summary: @Composable RowScope.() -> Unit,  // 折叠态右侧摘要（slot）
    expanded: Boolean,                         // 是否展开
    onToggle: () -> Unit,                      // 点击行头切换展开
    modifier: Modifier = Modifier,
    showTopDivider: Boolean = true,            // false = 第一行不画顶部分隔线
    content: @Composable () -> Unit,           // 展开内容
) {
    val colors = LocalMederiColors.current

    // hover 底色：常态透明，鼠标移入才浮出
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

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
                .height(34.dp)
                .background(if (isHovered) colors.surfaceHover else Color.Transparent)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
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
                style = MederiTypeScale.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            summary()
            Icon(
                imageVector = FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = tween(180)),
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