package xyz.mederi.ui.components.atoms

import xyz.mederi.theme.LocalWorkspaceMotion
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import xyz.mederi.theme.WorkspaceStyle
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.disclosure_expanded
import mederi.app.shared.generated.resources.disclosure_collapsed
import org.jetbrains.compose.resources.stringResource
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
        enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else fadeIn(LocalWorkspaceMotion.current.disclosureSpec()) + expandVertically(LocalWorkspaceMotion.current.disclosureSpec()),
        exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else fadeOut(LocalWorkspaceMotion.current.disclosureSpec()) + shrinkVertically(LocalWorkspaceMotion.current.disclosureSpec()),
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
    size: Dp = WorkspaceStyle.IconSize,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = LocalWorkspaceMotion.current.disclosureSpec(),
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
    val colors = LocalMederiColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (hovered || focused) colors.surfaceHover else Color.Transparent,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "expandableRowFeedback",
    )
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = WorkspaceStyle.ControlHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(background)
                .expansionSemantics(expanded, onExpandedChange)
                .clickable(interactionSource = interactionSource, role = Role.Button) { onExpandedChange(!expanded) }
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

/** 折叠行共享的无障碍状态与动作；不可展开的调用方不挂此修饰符。 */
@Composable
internal fun Modifier.expansionSemantics(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
): Modifier {
    val description = stringResource(
        if (expanded) Res.string.disclosure_expanded else Res.string.disclosure_collapsed
    )
    return semantics(mergeDescendants = true) {
        stateDescription = description
        if (expanded) {
            collapse { onExpandedChange(false); true }
        } else {
            expand { onExpandedChange(true); true }
        }
    }
}
