package xyz.mederi.ui.components.atoms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import xyz.mederi.theme.LocalMederiColors

/**
 * 折叠展开区：AnimatedVisibility(fadeIn + expandVertically) 的收敛封装。
 * 供各卡片展开区复用（ToolCallsBlock/UserMessageCards/WorkTraceCard/ReasoningBlock 等 7 处
 * 复制粘贴的折叠交互统一走这里）。
 */
@Composable
fun ExpandableContent(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Column(content = content)
    }
}

/**
 * 折叠指示箭头（chevron），展开时旋转 90°。纯视觉原子，无点击语义。
 */
@Composable
fun ExpandChevron(
    expanded: Boolean,
    tint: Color,
    size: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(150),
        label = "expandChevron",
    )
    Icon(
        imageVector = FeatherIcons.ChevronRight,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation },
    )
}

/**
 * 可折叠行：header 行（可点击切换展开）+ [ExpandableContent]。
 * header 内渲染内容后自动补弹性占位与箭头；需要自定义尾部动作时传 [showChevron] = false
 * 并在 header 内自行补 [ExpandChevron]。
 */
@Composable
fun ExpandableRow(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    chevronTint: Color = LocalMederiColors.current.textMuted,
    showChevron: Boolean = true,
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { onExpandedChange(!expanded) }
                .padding(vertical = 4.dp),
        ) {
            header()
            Spacer(Modifier.weight(1f))
            if (showChevron) {
                ExpandChevron(expanded = expanded, tint = chevronTint)
            }
        }
        ExpandableContent(expanded = expanded, content = content)
    }
}