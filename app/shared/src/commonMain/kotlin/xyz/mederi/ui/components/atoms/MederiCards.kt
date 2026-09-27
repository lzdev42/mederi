package xyz.mederi.ui.components.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.MederiTypeScale

/**
 * 通用卡片壳（02-components §2.10 panel-card shell + 01-tokens §4.2 card 8dp 收敛）。
 *
 * 标准：bg `surface-1`（MederiColors.surfaceCard）、1dp `border`（surfaceCardBorder）、
 * radius Card 8dp、无阴影（01-tokens §4.3）；padding 可注入，默认 LG 16dp。
 * [onClick] 非空时整卡可点击（ripple 裁剪在 Card 圆角内，clip 置于 clickable 外层）。
 * 这是 Surface 卡片壳收敛的唯一出口：新卡片一律基于 [MederiCard] 组装。
 */
@Composable
fun MederiCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(MederiSpacing.LG),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMederiColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MederiRadius.Card))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Card))
            .padding(padding),
        content = content,
    )
}

/**
 * 度量卡（02-components §2.9 `usage-col-card`）。
 *
 * 标准：padding 12/14dp（T/B = MederiSpacing.MD、L/R = MederiSpacing.Loose）、gap 4（01-tokens §3.3）、
 * radius Control 6dp、bg `surface-1`（surfaceCard）+ 1dp `border`（surfaceCardBorder）；
 * title 11.5sp `text-secondary`（MederiTypeScale.BodyCompact）、
 * value 20sp/500 `text-primary`（MederiTypeScale.Metric，01-tokens §2.3 metric）、
 * sub 11sp `text-secondary`（MederiTypeScale.Label）。
 */
@Composable
fun MederiMetricCard(
    title: String,
    value: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MederiRadius.Control))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Control))
            .padding(vertical = MederiSpacing.MD, horizontal = MederiSpacing.Loose),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
    ) {
        Text(text = title, style = MederiTypeScale.BodyCompact, color = colors.textSecondary)
        Text(text = value, style = MederiTypeScale.Metric, color = colors.textPrimary)
        if (sub != null) {
            Text(text = sub, style = MederiTypeScale.Label, color = colors.textSecondary)
        }
    }
}
