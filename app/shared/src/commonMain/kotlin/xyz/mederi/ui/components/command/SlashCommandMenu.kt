package xyz.mederi.ui.components.command

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.CornerDownLeft
import kotlin.math.roundToInt
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.slash_group_commands
import mederi.app.shared.generated.resources.slash_group_plugins
import mederi.app.shared.generated.resources.slash_hint_cancel
import mederi.app.shared.generated.resources.slash_hint_cancel_keys
import mederi.app.shared.generated.resources.slash_hint_confirm
import mederi.app.shared.generated.resources.slash_hint_confirm_keys
import mederi.app.shared.generated.resources.slash_select_indicator
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.LocalWorkspaceMotion
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.MederiTypeScale
import xyz.mederi.theme.WorkspaceStyle

/**
 * 快捷命令菜单浮层面板。
 *
 * 垂直三段布局（列表可滚动区 + 细分割线 + 底部键盘提示栏），保证长列表限高内滚动时底部提示固定可见且互不遮挡。
 * 配色、圆角、字重（仅 Regular / Medium）与交互反馈对齐 Mederi Workspace 弹层规范。
 */
@Composable
fun SlashCommandMenu(
    items: List<SlashCommandItem>,
    selectedIndex: Int,
    onSelect: (SlashCommandItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val colors = LocalMederiColors.current
    val motion = LocalWorkspaceMotion.current
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val grouped = remember(items) { SlashCommandRegistry.groupItems(items) }
    val itemBounds = remember(items) { mutableMapOf<Int, Pair<Int, Int>>() }
    val rowScrollBufferPx = with(density) { MederiSpacing.Compact.toPx() }.roundToInt()

    LaunchedEffect(selectedIndex, items, scrollState.viewportSize) {
        if (selectedIndex <= 0) {
            if (scrollState.value != 0) {
                if (motion.reducedMotion) scrollState.scrollTo(0) else scrollState.animateScrollTo(0)
            }
            return@LaunchedEffect
        }
        val (itemTop, itemBottom) = itemBounds[selectedIndex] ?: return@LaunchedEffect
        val viewportHeight = scrollState.viewportSize
        if (viewportHeight <= 0) return@LaunchedEffect
        val viewTop = scrollState.value
        val viewBottom = viewTop + viewportHeight
        val target = when {
            itemTop < viewTop -> (itemTop - rowScrollBufferPx).coerceAtLeast(0)
            itemBottom > viewBottom -> (itemBottom + rowScrollBufferPx - viewportHeight).coerceAtMost(scrollState.maxValue)
            else -> null
        }
        if (target != null && target != scrollState.value) {
            if (motion.reducedMotion) scrollState.scrollTo(target) else scrollState.animateScrollTo(target)
        }
    }

    val containerShape = RoundedCornerShape(WorkspaceStyle.InputRadius)
    var globalIndexCounter = 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .shadow(4.dp, containerShape, clip = false)
            .clip(containerShape)
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, containerShape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(horizontal = MederiSpacing.Tight),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.Micro)
        ) {
            Spacer(modifier = Modifier.height(MederiSpacing.Tight))

            grouped.entries.forEachIndexed { groupIndex, (group, groupItems) ->
                val groupTitle = when (group) {
                    CommandGroup.COMMAND -> stringResource(Res.string.slash_group_commands)
                    CommandGroup.PLUGIN -> stringResource(Res.string.slash_group_plugins)
                }
                Text(
                    text = groupTitle,
                    color = colors.textMuted,
                    style = MederiTypeScale.Label,
                    modifier = Modifier.padding(
                        start = MederiSpacing.Compact,
                        end = MederiSpacing.Compact,
                        top = if (groupIndex == 0) MederiSpacing.XS else MederiSpacing.SM,
                        bottom = MederiSpacing.XS
                    )
                )

                groupItems.forEach { item ->
                    val currentIndex = globalIndexCounter++
                    val isSelected = currentIndex == selectedIndex

                    SlashCommandRow(
                        item = item,
                        isSelected = isSelected,
                        onClick = { onSelect(item) },
                        modifier = Modifier.onPlaced { coords ->
                            val top = coords.positionInParent().y.roundToInt()
                            itemBounds[currentIndex] = top to (top + coords.size.height)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(MederiSpacing.Tight))
        }

        HorizontalDivider(
            color = colors.divider.copy(alpha = 0.6f),
            thickness = 1.dp
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.SM),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyHintLabel(
                keys = stringResource(Res.string.slash_hint_confirm_keys),
                action = stringResource(Res.string.slash_hint_confirm),
                colors = colors
            )
            Spacer(modifier = Modifier.width(MederiSpacing.SM))
            KeyHintLabel(
                keys = stringResource(Res.string.slash_hint_cancel_keys),
                action = stringResource(Res.string.slash_hint_cancel),
                colors = colors
            )
        }
    }
}

@Composable
private fun KeyHintLabel(
    keys: String,
    action: String,
    colors: MederiColors,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.Tight)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(MederiRadius.Square))
                .background(colors.surfaceHover)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Square))
                .padding(horizontal = 5.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = keys,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
                maxLines = 1,
                softWrap = false
            )
        }
        Text(
            text = action,
            style = MederiTypeScale.Micro,
            color = colors.textMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 单条命令渲染行。
 */
@Composable
private fun SlashCommandRow(
    item: SlashCommandItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val motion = LocalWorkspaceMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val rowBackground by animateColorAsState(
        targetValue = when {
            isSelected -> colors.accentBg
            hovered -> colors.surfaceHover
            else -> Color.Transparent
        },
        animationSpec = motion.feedbackSpec(),
        label = "slashCommandRowBackground"
    )
    val rowShape = RoundedCornerShape(MederiRadius.Control)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WorkspaceStyle.ControlHeight)
            .semantics { selected = isSelected }
            .clip(rowShape)
            .background(rowBackground)
            .border(
                width = 1.dp,
                color = if (isSelected) colors.accentBorder else Color.Transparent,
                shape = rowShape
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = MederiSpacing.Compact, vertical = MederiSpacing.Tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!item.iconBadge.isNullOrEmpty()) {
            val badgeShape = RoundedCornerShape(MederiRadius.Square)
            val customBg = item.iconBadgeBg
            Box(
                modifier = Modifier
                    .size(WorkspaceStyle.IconSize)
                    .clip(badgeShape)
                    .background(customBg ?: colors.accentBg)
                    .border(
                        width = 1.dp,
                        color = if (customBg == null) colors.accentBorder else Color.Transparent,
                        shape = badgeShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.iconBadge,
                    color = if (customBg == null) colors.accentText else colors.onAccentPrimary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else if (item.icon != null) {
            val resolvedTint = resolveCommandIconTint(item, isSelected, colors)
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = resolvedTint,
                modifier = Modifier.size(WorkspaceStyle.IconSize)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(MederiRadius.Badge))
                    .background(if (isSelected) colors.accentPrimary else colors.textMuted)
            )
        }

        Spacer(modifier = Modifier.width(MederiSpacing.Compact))

        Text(
            text = item.label,
            style = WorkspaceStyle.Title,
            color = if (isSelected) colors.accentText else colors.textPrimary,
            maxLines = 1,
            softWrap = false
        )

        Spacer(modifier = Modifier.width(MederiSpacing.SM))

        Text(
            text = item.description,
            style = WorkspaceStyle.Caption,
            color = if (isSelected) colors.textSecondary else colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (isSelected) {
            Spacer(modifier = Modifier.width(MederiSpacing.SM))
            Icon(
                imageVector = FeatherIcons.CornerDownLeft,
                contentDescription = stringResource(Res.string.slash_select_indicator),
                tint = colors.accentText,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

private fun resolveCommandIconTint(
    item: SlashCommandItem,
    isSelected: Boolean,
    colors: MederiColors,
): Color {
    if (item.iconTint != Color.Unspecified) return item.iconTint
    return when {
        item.group == CommandGroup.PLUGIN -> colors.accentSuccess
        item.id == "clear" -> colors.accentDanger
        item.id == "compact" -> colors.accentWarning
        item.id == "plan" -> colors.thoughtAccent
        item.id == "skill" -> colors.accentSuccess
        isSelected -> colors.accentText
        else -> colors.textSecondary
    }
}
