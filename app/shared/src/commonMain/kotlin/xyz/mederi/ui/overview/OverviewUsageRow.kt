package xyz.mederi.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import kotlin.math.roundToLong
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.close
import mederi.app.shared.generated.resources.overview_usage_cache_rate
import mederi.app.shared.generated.resources.overview_usage_cache_detail
import mederi.app.shared.generated.resources.overview_usage_cache_read
import mederi.app.shared.generated.resources.overview_usage_cache_write
import mederi.app.shared.generated.resources.overview_usage_cost
import mederi.app.shared.generated.resources.overview_usage_cost_note
import mederi.app.shared.generated.resources.overview_usage_cost_column
import mederi.app.shared.generated.resources.overview_usage_input
import mederi.app.shared.generated.resources.overview_usage_input_uncached
import mederi.app.shared.generated.resources.overview_usage_key_default
import mederi.app.shared.generated.resources.overview_usage_key_rotation
import mederi.app.shared.generated.resources.overview_usage_model
import mederi.app.shared.generated.resources.overview_usage_output
import mederi.app.shared.generated.resources.overview_usage_request
import mederi.app.shared.generated.resources.overview_usage_successful_requests
import mederi.app.shared.generated.resources.overview_usage_row_cache_rate
import mederi.app.shared.generated.resources.overview_usage_row_count
import mederi.app.shared.generated.resources.overview_usage_row_title
import mederi.app.shared.generated.resources.overview_usage_session
import mederi.app.shared.generated.resources.overview_usage_session_cost
import mederi.app.shared.generated.resources.overview_usage_stats
import mederi.app.shared.generated.resources.overview_usage_stats_empty
import mederi.app.shared.generated.resources.overview_usage_stats_title
import mederi.app.shared.generated.resources.overview_usage_total
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.API_KEY_ROTATION_ID
import xyz.mederi.core.contract.models.UsageStatGroup
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.formatTokenShort
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.MederiCompactStrokeButton
import xyz.mederi.ui.components.atoms.MederiDialog
import xyz.mederi.ui.components.atoms.MederiGhostButton
import xyz.mederi.ui.components.atoms.WorkspaceNavigationTab

@Composable
fun OverviewUsageRow(
    viewModel: WorkspaceViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenStats: (() -> Unit)? = null,
) {
    val colors = LocalMederiColors.current
    val selectedModel by viewModel.usageSelectedModel.collectAsState()
    val request = viewModel.usageForModel(selectedModel?.id)
    val session = viewModel.tokenUsage
    val cost = viewModel.cost
    var showSession by remember(viewModel.conversationId) { mutableStateOf(false) }
    var showStats by remember(viewModel.conversationId) { mutableStateOf(false) }

    OverviewAccordionRow(
        icon = FeatherIcons.Activity,
        title = stringResource(Res.string.overview_usage_row_title),
        summary = {
            // 无请求数据时显示 —，不用 0 伪装
            Text(
                text = if (viewModel.requestCount > 0) {
                    stringResource(Res.string.overview_usage_row_count, viewModel.requestCount)
                } else "—",
                style = WorkspaceStyle.Caption,
                color = colors.textSecondary,
            )
            request?.cacheHitPercent?.let { rate ->
                Text(
                    text = stringResource(Res.string.overview_usage_row_cache_rate, usageRate(rate)),
                    style = WorkspaceStyle.Caption,
                    color = colors.textSecondary,
                )
            }
        },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.Tight)) {
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
            ) {
                WorkspaceNavigationTab(
                    text = stringResource(Res.string.overview_usage_request),
                    selected = !showSession,
                    onClick = { showSession = false },
                    modifier = Modifier.weight(1f),
                )
                WorkspaceNavigationTab(
                    text = stringResource(Res.string.overview_usage_session),
                    selected = showSession,
                    onClick = { showSession = true },
                    modifier = Modifier.weight(1f),
                )
            }

            val input = if (showSession) session.input.takeIf { session.inputReported } else request?.inputTokens
            val output = if (showSession) session.output.takeIf { session.outputReported } else request?.outputTokens
            val cacheRead = if (showSession) session.cacheRead else request?.cachedTokens
            val cacheWrite = if (showSession) session.cacheWrite else request?.cacheWriteTokens
            val cacheRate = if (showSession) session.cacheHitSample?.percent else request?.cacheHitPercent
            if (!showSession) {
                selectedModel?.name?.let { name ->
                    Text(name, color = colors.textSecondary, style = WorkspaceStyle.Caption)
                }
            }
            if (showSession) {
                UsageLine(
                    stringResource(Res.string.overview_usage_successful_requests),
                    viewModel.requestCount.takeIf { it > 0 }?.toString() ?: "—",
                )
            }
            // 输入是含缓存读写的总量，拆出无缓存部分后四行互不重叠。
            UsageLine(stringResource(Res.string.overview_usage_input), usageCount(input))
            UsageLine(
                stringResource(Res.string.overview_usage_input_uncached),
                usageCount(uncachedInputTokens(input, cacheRead, cacheWrite)),
            )
            UsageLine(stringResource(Res.string.overview_usage_cache_read), usageCount(cacheRead))
            UsageLine(stringResource(Res.string.overview_usage_cache_write), usageCount(cacheWrite))
            UsageLine(stringResource(Res.string.overview_usage_output), usageCount(output))
            UsageLine(stringResource(Res.string.overview_usage_cache_rate), usageRate(cacheRate))

            HorizontalDivider(color = colors.divider)
            Text(stringResource(Res.string.overview_usage_cost), color = colors.textPrimary, style = WorkspaceStyle.Title)
            UsageCostNote()
            UsageLine(
                stringResource(Res.string.overview_usage_request),
                usageCost(viewModel.costForModel(selectedModel?.id)),
            )
            UsageLine(stringResource(Res.string.overview_usage_session_cost), usageCost(cost.total))
            MederiGhostButton(
                text = stringResource(Res.string.overview_usage_stats),
                onClick = onOpenStats ?: { showStats = true },
                modifier = Modifier.align(Alignment.End),
            )
        }
    }

    if (showStats && onOpenStats == null) {
        UsageStatsDialog(viewModel, onDismiss = { showStats = false })
    }
}

/**
 * 无缓存输入 = 输入总量 − 缓存命中 − 缓存写入（未上报按 0 计，负数钳 0）。
 * 输入本身未上报（null）时保持 null，UI 显示 —。
 */
internal fun uncachedInputTokens(input: Long?, read: Long?, write: Long?): Long? =
    input?.let { maxOf(0L, it - (read ?: 0L) - (write ?: 0L)) }

@Composable
private fun UsageStatsDialog(viewModel: WorkspaceViewModel, onDismiss: () -> Unit) {
    val colors = LocalMederiColors.current
    val statistics by viewModel.usageStatistics.collectAsState()
    // 跨会话累计账本只在打开时拉取一次，不做轮询/自动刷新。
    LaunchedEffect(Unit) { viewModel.loadUsageStatistics() }
    MederiDialog(
        onDismiss = onDismiss,
        width = 620.dp,
        title = {
            Text(
                text = stringResource(Res.string.overview_usage_stats_title),
                color = colors.textPrimary,
                style = WorkspaceStyle.PageTitle,
            )
        },
    ) {
        val groups = statistics?.groups.orEmpty()
        if (groups.isEmpty()) {
            Text(
                text = stringResource(Res.string.overview_usage_stats_empty),
                color = colors.textSecondary,
                style = WorkspaceStyle.Body,
            )
        } else {
            UsageStatsRow(
                name = stringResource(Res.string.overview_usage_model),
                provider = null,
                requests = stringResource(Res.string.overview_usage_successful_requests),
                input = stringResource(Res.string.overview_usage_input),
                output = stringResource(Res.string.overview_usage_output),
                estimate = stringResource(Res.string.overview_usage_cost_column),
                emphasized = true,
            )
            HorizontalDivider(color = colors.divider)
            groups.forEach { group ->
                UsageStatsRow(
                    name = group.modelName ?: group.modelId ?: "—",
                    provider = GroupSecondaryLine(group),
                    requests = group.successfulRequests.toString(),
                    input = usageCount(group.inputTokens),
                    output = usageCount(group.outputTokens),
                    estimate = usageCost(group.estimate),
                )
                UsageCacheDetail(group.inputTokens, group.cacheReadTokens, group.cacheWriteTokens)
            }
            HorizontalDivider(color = colors.divider)
            UsageStatsRow(
                name = stringResource(Res.string.overview_usage_total),
                provider = null,
                requests = groups.sumOf { it.successfulRequests }.takeIf { it > 0 }?.toString() ?: "—",
                input = usageCount(reportedSum(groups.map { it.inputTokens })),
                output = usageCount(reportedSum(groups.map { it.outputTokens })),
                estimate = usageCost(statistics?.totalEstimatedCost),
                emphasized = true,
            )
            UsageCacheDetail(
                reportedSum(groups.map { it.inputTokens }),
                reportedSum(groups.map { it.cacheReadTokens }),
                reportedSum(groups.map { it.cacheWriteTokens }),
            )
        }
        UsageCostNote()
        MederiCompactStrokeButton(
            text = stringResource(Res.string.close),
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@Composable
internal fun CompactUsageStats(viewModel: WorkspaceViewModel) {
    val colors = LocalMederiColors.current
    val statistics by viewModel.usageStatistics.collectAsState()
    // 每次进入累计用量页拉取一次（首帧组合时），页面内不做轮询/自动刷新。
    LaunchedEffect(Unit) { viewModel.loadUsageStatistics() }
    val groups = statistics?.groups.orEmpty()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
    ) {
        if (groups.isEmpty()) {
            Text(
                stringResource(Res.string.overview_usage_stats_empty),
                color = colors.textSecondary,
                style = WorkspaceStyle.Body,
            )
        }
        groups.forEach { group ->
            CompactUsageStatsGroup(
                name = group.modelName ?: group.modelId ?: "—",
                provider = GroupSecondaryLine(group),
                requests = group.successfulRequests.toString(),
                input = group.inputTokens,
                output = group.outputTokens,
                estimate = group.estimate,
                cacheRead = group.cacheReadTokens,
                cacheWrite = group.cacheWriteTokens,
            )
            HorizontalDivider(color = colors.divider)
        }
        if (groups.isNotEmpty()) {
            CompactUsageStatsGroup(
                name = stringResource(Res.string.overview_usage_total),
                provider = null,
                requests = groups.sumOf { it.successfulRequests }.takeIf { it > 0 }?.toString() ?: "—",
                input = reportedSum(groups.map { it.inputTokens }),
                output = reportedSum(groups.map { it.outputTokens }),
                estimate = statistics?.totalEstimatedCost,
                cacheRead = reportedSum(groups.map { it.cacheReadTokens }),
                cacheWrite = reportedSum(groups.map { it.cacheWriteTokens }),
            )
        }
        UsageCostNote()
    }
}

@Composable
private fun CompactUsageStatsGroup(
    name: String,
    provider: String?,
    requests: String,
    input: Long?,
    output: Long?,
    estimate: Double?,
    cacheRead: Long?,
    cacheWrite: Long?,
) {
    val colors = LocalMederiColors.current
    Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.Tight)) {
        Text(name, color = colors.textPrimary, style = WorkspaceStyle.Title)
        if (provider != null) {
            Text(provider, color = colors.textSecondary, style = WorkspaceStyle.Caption)
        }
        UsageLine(stringResource(Res.string.overview_usage_successful_requests), requests)
        UsageLine(stringResource(Res.string.overview_usage_input), usageCount(input))
        UsageLine(stringResource(Res.string.overview_usage_output), usageCount(output))
        UsageLine(stringResource(Res.string.overview_usage_cost_column), usageCost(estimate))
        UsageCacheDetail(input, cacheRead, cacheWrite)
    }
}

@Composable
private fun UsageCostNote() {
    Text(
        stringResource(Res.string.overview_usage_cost_note),
        color = LocalMederiColors.current.textSecondary,
        style = WorkspaceStyle.Caption,
    )
}

/** 组副行 = 供应商名（缺省回落 ID）+ API Key 标签（默认 / 轮询 / 具体 key）。 */
@Composable
private fun GroupSecondaryLine(group: UsageStatGroup): String {
    val provider = group.providerName ?: group.providerId
    val key = when (group.apiKeyId) {
        null -> stringResource(Res.string.overview_usage_key_default)
        API_KEY_ROTATION_ID -> stringResource(Res.string.overview_usage_key_rotation)
        else -> group.apiKeyName ?: group.apiKeyId
    }
    return listOfNotNull(provider, key).joinToString(" · ")
}

/** 组下小字明细行：无缓存 / 命中 / 写入（未上报显示 —）。 */
@Composable
private fun UsageCacheDetail(input: Long?, read: Long?, write: Long?) {
    Text(
        stringResource(
            Res.string.overview_usage_cache_detail,
            usageCount(uncachedInputTokens(input, read, write)),
            usageCount(read),
            usageCount(write),
        ),
        color = LocalMederiColors.current.textSecondary,
        style = WorkspaceStyle.Caption,
    )
}

/** 只对已上报（非 null）的分组求和；全都没上报时返回 null（UI 显示 —）。 */
private fun reportedSum(values: List<Long?>): Long? {
    val reported = values.filterNotNull()
    return if (reported.isEmpty()) null else reported.sum()
}

@Composable
private fun UsageStatsRow(
    name: String,
    provider: String?,
    requests: String,
    input: String,
    output: String,
    estimate: String,
    emphasized: Boolean = false,
) {
    val colors = LocalMederiColors.current
    val style = if (emphasized) WorkspaceStyle.Title else WorkspaceStyle.Body
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
    ) {
        Column(modifier = Modifier.weight(2f)) {
            Text(name, style = style, color = colors.textPrimary)
            if (provider != null) {
                Text(provider, style = WorkspaceStyle.Caption, color = colors.textSecondary)
            }
        }
        Text(requests, Modifier.weight(1f), textAlign = TextAlign.End, style = style, color = colors.textPrimary)
        Text(input, Modifier.weight(1f), textAlign = TextAlign.End, style = style, color = colors.textPrimary)
        Text(output, Modifier.weight(1f), textAlign = TextAlign.End, style = style, color = colors.textPrimary)
        Text(estimate, Modifier.weight(1.2f), textAlign = TextAlign.End, style = style, color = colors.textPrimary)
    }
}

@Composable
private fun UsageLine(label: String, value: String) {
    val colors = LocalMederiColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = colors.textSecondary, style = WorkspaceStyle.Caption)
        Text(value, color = colors.textPrimary, style = WorkspaceStyle.Title)
    }
}

/** token 数量大时切 k/m/b 单位（两位小数）；无数据显示 —。 */
private fun usageCount(tokens: Long?): String = tokens?.let { formatTokenShort(it) } ?: "—"

private fun usageRate(rate: Double?): String {
    if (rate == null) return "—"
    val tenths = (rate * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}%"
}

private fun usageCost(estimate: Double?): String {
    if (estimate == null) return "—"
    if (estimate > 0.0 && estimate < 0.000001) return "<$0.000001"
    val millionths = (estimate * 1_000_000).roundToLong()
    val decimals = (millionths % 1_000_000).toString().padStart(6, '0').trimEnd('0').padEnd(2, '0')
    return "$${millionths / 1_000_000}.$decimals"
}
