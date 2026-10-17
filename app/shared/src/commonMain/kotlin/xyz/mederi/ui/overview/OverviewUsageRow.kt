package xyz.mederi.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.dock_cost_title
import mederi.app.shared.generated.resources.dock_last_request_cached
import mederi.app.shared.generated.resources.dock_last_request_input
import mederi.app.shared.generated.resources.dock_last_request_output
import mederi.app.shared.generated.resources.overview_usage_row_cache_rate
import mederi.app.shared.generated.resources.overview_usage_row_count
import mederi.app.shared.generated.resources.overview_usage_row_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.formatCost
import xyz.mederi.ui.components.formatTokenShort

/**
 * 概览浮动窗用量行（合并 LastRequestUsage + UsageMetric 2 列）。
 *
 * 折叠态摘要：请求次数 + （有缓存数据时）缓存率；无缓存数据只显示次数。
 * 缓存率 = cached / input（防除零：cachedTokens 非 null 且 inputTokens > 0 才算，
 * 否则不显示——诚实原则，不伪造数据）。
 *
 * 展开态：2×2 紧凑数据块（输入 / 输出 / 缓存 / 预估成本），surfaceHover 底色保持层次。
 * 缓存块在 cachedTokens == null 时显示 "--"（不伪造 0）。
 */
@Composable
fun OverviewUsageRow(
    viewModel: WorkspaceViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    val requestCount = viewModel.requestCount
    val usage = viewModel.lastRequestUsage
    // 参考价估算（models.dev 目录价 × token 用量），非真实账单
    val costUsd = viewModel.referenceCostUsd

    // 缓存率（整数百分比）：仅当有缓存数据且 input > 0 时计算（防除零）
    val cacheRate = if (usage != null && usage.cachedTokens != null && usage.inputTokens > 0) {
        usage.cachedTokens * 100 / usage.inputTokens
    } else null

    OverviewAccordionRow(
        icon = FeatherIcons.Activity,
        title = stringResource(Res.string.overview_usage_row_title),
        summary = {
            Text(
                text = stringResource(Res.string.overview_usage_row_count, requestCount),
                color = colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (cacheRate != null) {
                Text(
                    text = stringResource(Res.string.overview_usage_row_cache_rate, cacheRate) + "%",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.Tight)) {
            UsageLine(
                label = stringResource(Res.string.dock_last_request_input),
                value = formatTokenShort(usage?.inputTokens ?: 0),
                colors = colors,
            )
            UsageLine(
                label = stringResource(Res.string.dock_last_request_output),
                value = formatTokenShort(usage?.outputTokens ?: 0),
                colors = colors,
            )
            UsageLine(
                label = stringResource(Res.string.dock_last_request_cached),
                value = if (usage?.cachedTokens != null) {
                    formatTokenShort(usage.cachedTokens)
                } else {
                    "--"
                },
                colors = colors,
            )
            UsageLine(
                label = stringResource(Res.string.dock_cost_title),
                value = "$${formatCost(costUsd)}",
                colors = colors,
            )
        }
    }
}

/**
 * 扁平用量行：左侧 label（11sp muted）+ 右侧 value（13sp Medium），
 * 无卡片背景，与上下文行展开风格一致。
 */
@Composable
private fun UsageLine(
    label: String,
    value: String,
    colors: MederiColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.textMuted,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}