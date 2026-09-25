package xyz.mederi.ui.components.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.mederi.theme.LocalMederiColors

/**
 * 概览面板卡片外壳：8dp 圆角 + surfaceCard 背景 + surfaceCardBorder 描边 + 12dp 内边距。
 * 对齐 Skill/Mcp/Metrics 等概览面板家族的统一外观。
 */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMederiColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        content = content,
    )
}

/**
 * 卡片头部：可选图标 + 标题 + 可选计数 + 尾部动作区。
 */
@Composable
fun CardHeader(
    icon: ImageVector? = null,
    title: String,
    count: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalMederiColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
        }
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f, fill = false),
        )
        count?.invoke()
        actions()
    }
}

/**
 * 空态占位：居中大图标 + 标题 + 可选提示 + 可选动作。
 * 对齐 InfoPanels/Skill/Mcp 三处同构空态（32dp 图标 / 10dp / 13sp Medium / 4dp / 11sp / 12dp 动作）。
 */
@Composable
fun PanelEmptyState(
    icon: ImageVector,
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = LocalMederiColors.current
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = colors.textMuted, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            color = colors.textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, color = colors.textMuted, fontSize = 11.sp)
        }
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}