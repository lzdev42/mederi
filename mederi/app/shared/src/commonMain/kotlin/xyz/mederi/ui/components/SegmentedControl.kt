package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.mederi.theme.LocalMederiColors

/**
 * 分段选择器单项定义。
 *
 * @param key 选项业务值（如 [xyz.mederi.core.contract.models.WorkType.CODE] / [xyz.mederi.core.contract.models.AgentMode.AUTONOMOUS]）
 * @param label 选项显示文案（如 "Code" / "自主"）
 * @param icon 可选前置图标（如 [compose.icons.FeatherIcons.Code] / [compose.icons.FeatherIcons.Shield]）
 */
data class SegmentItem<T>(
    val key: T,
    val label: String,
    val icon: ImageVector? = null
)

/**
 * 平铺胶囊型分段选择器（Segmented Control / Pill Switcher）。
 *
 * 设计决策：
 * 1. 采用宽裕的内边距（高度 30-32dp），告别拥挤紧凑感。
 * 2. 支持可选图标（Leading Icon）与文字搭配，提升视觉识别度。
 * 3. 选中有明确的胶囊微高亮（buttonSecondary），未选中文字保持清晰可读的 textSecondary。
 * 4. 支持 [equalWeight] 等宽分布（适合侧边栏顶部），或自适应宽度（适合输入框工具栏）。
 *
 * @param items 所有选项列表
 * @param selectedKey 当前选中的选项 key
 * @param onSelect 选项点击选择回调
 * @param modifier 外部修饰符
 * @param height 高度，默认 30.dp
 * @param equalWeight 是否等分宽度铺满容器
 */
@Composable
fun <T> SegmentedControl(
    items: List<SegmentItem<T>>,
    selectedKey: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 30.dp,
    equalWeight: Boolean = false
) {
    val colors = LocalMederiColors.current

    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceInput)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            val isSelected = item.key == selectedKey
            val itemModifier = if (equalWeight) Modifier.weight(1f) else Modifier
            Row(
                modifier = itemModifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) colors.buttonSecondary else Color.Transparent)
                    .border(
                        width = if (isSelected) 1.dp else 0.dp,
                        color = if (isSelected) colors.divider else Color.Transparent,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable { onSelect(item.key) }
                    .padding(horizontal = if (equalWeight) 8.dp else 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.icon != null) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = if (isSelected) colors.textPrimary else colors.textMuted,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = item.label,
                    color = if (isSelected) colors.textPrimary else colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}
