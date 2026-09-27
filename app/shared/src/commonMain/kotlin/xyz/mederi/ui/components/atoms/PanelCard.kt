package xyz.mederi.ui.components.atoms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.MederiTypeScale

/**
 * 概览面板卡片外壳（02-components §2.10 panel-card shell）：Card 8dp 圆角 + surfaceCard 背景 +
 * surfaceCardBorder 描边 + 12dp（MD）内边距，基于 [MederiCard] 组装。
 * 对齐 Skill/Mcp/Metrics 等概览面板家族的统一外观。
 */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    MederiCard(
        modifier = modifier,
        padding = PaddingValues(MederiSpacing.MD),
        content = content,
    )
}

/**
 * 卡片头部（02-components §2.10 panel-card header）：可选图标 + 标题 + 可选计数 + 尾部动作区。
 * 标准：左 13sp/600（收敛为 MederiTypeScale.Title 13sp Medium）+ count 11sp/500（调用方自定）；
 * gap 6（MederiSpacing.Tight）。
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
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.Tight),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(MederiSpacing.Loose))
        }
        Text(
            text = title,
            style = MederiTypeScale.Title,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f, fill = false),
        )
        count?.invoke()
        actions()
    }
}

/**
 * 空态占位（02-components §2.10 空态同构，对齐 InfoPanels/Skill/Mcp 三处）：居中大图标 + 标题 + 可选提示 + 可选动作。
 * 标准：32dp 图标 / 10dp（MederiSpacing.Compact）/ 13sp Medium（MederiTypeScale.Title）/ 4dp（XS）/ 11sp（Label）/ 12dp（MD）动作。
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
        modifier = modifier.fillMaxSize().padding(MederiSpacing.XXL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = colors.textMuted, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(MederiSpacing.Compact))
        Text(
            text = title,
            color = colors.textSecondary,
            style = MederiTypeScale.Title,
        )
        if (hint != null) {
            Spacer(Modifier.height(MederiSpacing.XS))
            Text(hint, color = colors.textMuted, style = MederiTypeScale.Label)
        }
        if (action != null) {
            Spacer(Modifier.height(MederiSpacing.MD))
            action()
        }
    }
}
