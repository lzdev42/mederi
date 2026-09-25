package xyz.mederi.ui.components.command

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.CornerDownLeft
import xyz.mederi.theme.LocalMederiColors

/**
 * 快捷命令菜单浮层面板。
 *
 * 样式对标高品质暗色卡片设计：
 * 1. 分组标题（"命令"、"插件"）浅灰低饱和展示；
 * 2. 单行条目：左侧彩色图标或徽标 + 加粗命令名称 + 单行截断描述；
 * 3. 高亮选中态：背景圆角 Pill 浅亮色填充 + 右侧回车符（⏎）提示；
 * 4. 支持最大高度限制与流畅纵向滚动。
 *
 * @param items 过滤后的展示条目列表
 * @param selectedIndex 当前被键盘或鼠标选中的条目索引（对应 items 的全局索引）
 * @param onSelect 选中条目回调
 * @param modifier 布局 Modifier
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
    val scrollState = rememberScrollState()
    val grouped = SlashCommandRegistry.groupItems(items)

    // 计算每个条目在全局 items 中的索引，方便键盘上下键导航
    var globalIndexCounter = 0

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.surfaceCardBorder),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            grouped.forEach { (group, groupItems) ->
                // 分组标题
                Text(
                    text = group.title,
                    color = colors.textMuted,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp)
                )

                groupItems.forEach { item ->
                    val currentIndex = globalIndexCounter++
                    val isSelected = currentIndex == selectedIndex

                    SlashCommandRow(
                        item = item,
                        isSelected = isSelected,
                        onClick = { onSelect(item) }
                    )
                }
            }
        }

        HorizontalDivider(
            color = colors.surfaceCardBorder.copy(alpha = 0.5f),
            thickness = 1.dp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⏎/Tab 确认补全",
                fontSize = 10.5.sp,
                color = colors.textMuted
            )
            Text(
                text = "Esc 取消联想",
                fontSize = 10.5.sp,
                color = colors.textMuted
            )
        }
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) colors.surfaceHover else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标 / 徽标
        if (!item.iconBadge.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(item.iconBadgeBg ?: Color(0xFFF97316)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.iconBadge,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else if (item.icon != null) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = if (item.iconTint != Color.Unspecified) item.iconTint else colors.textPrimary,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.accentPrimary)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // 命令主标题
        Text(
            text = item.label,
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 命令功能简要描述（单行，超出截断）
        Text(
            text = item.description,
            color = colors.textMuted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // 选中时右侧回车符指示
        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = FeatherIcons.CornerDownLeft,
                contentDescription = "Select",
                tint = colors.textMuted,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}
