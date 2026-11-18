package xyz.mederi.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import kotlin.math.roundToLong
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.close
import mederi.app.shared.generated.resources.overview_usage_cost_note
import mederi.app.shared.generated.resources.overview_usage_cost_column
import mederi.app.shared.generated.resources.overview_usage_input
import mederi.app.shared.generated.resources.overview_usage_input_hit
import mederi.app.shared.generated.resources.overview_usage_input_miss
import mederi.app.shared.generated.resources.overview_usage_input_write
import mederi.app.shared.generated.resources.overview_usage_key_default
import mederi.app.shared.generated.resources.overview_usage_key_rotation
import mederi.app.shared.generated.resources.overview_usage_model
import mederi.app.shared.generated.resources.overview_usage_output
import mederi.app.shared.generated.resources.overview_usage_provider_actual
import mederi.app.shared.generated.resources.overview_usage_request
import mederi.app.shared.generated.resources.overview_usage_successful_requests
import mederi.app.shared.generated.resources.overview_usage_row_cache_rate
import mederi.app.shared.generated.resources.overview_usage_row_count
import mederi.app.shared.generated.resources.overview_usage_row_title
import mederi.app.shared.generated.resources.overview_usage_session
import mederi.app.shared.generated.resources.overview_usage_standard_price
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
            // 双列表格（方案 B 定稿）：同一指标本次请求 / 会话累计并列对照，模型名标注本次列归属。
            selectedModel?.name?.let { name ->
                Text(name, color = colors.textSecondary, style = WorkspaceStyle.Caption)
            }

            val requestInput = request?.inputTokens
            val sessionInput = session.input.takeIf { session.inputReported }
            val requestRead = request?.cachedTokens
            val sessionRead = session.cacheRead
            val requestWrite = request?.cacheWriteTokens
            val sessionWrite = session.cacheWrite
            // 缓存写入两列都未上报时不渲染该行，保持 ├/└ 树形前缀有效。
            val showCacheWrite = requestWrite != null || sessionWrite != null

            Row(modifier = Modifier.fillMaxWidth()) {
                UsageTableCell(
                    text = "",
                    modifier = Modifier.weight(1.3f),
                    color = colors.textMuted,
                    style = UsageTableHeaderStyle,
                )
                UsageTableCell(
                    text = stringResource(Res.string.overview_usage_request),
                    modifier = Modifier.weight(1f),
                    color = colors.textMuted,
                    style = UsageTableHeaderStyle,
                )
                UsageTableCell(
                    text = stringResource(Res.string.overview_usage_session),
                    modifier = Modifier.weight(1f),
                    color = colors.textMuted,
                    style = UsageTableHeaderStyle,
                )
            }
            // 输入是含缓存读写的总量，拆出未命中/写入/命中后各行互不重叠。
            UsageTableRow(
                label = stringResource(Res.string.overview_usage_input),
                request = usageCount(requestInput),
                session = usageCount(sessionInput),
                emphasized = true,
            )
            UsageTableRow(
                label = stringResource(Res.string.overview_usage_input_miss),
                request = usageCount(uncachedInputTokens(requestInput, requestRead, requestWrite)),
                session = usageCount(uncachedInputTokens(sessionInput, sessionRead, sessionWrite)),
            )
            if (showCacheWrite) {
                UsageTableRow(
                    label = stringResource(Res.string.overview_usage_input_write),
                    request = usageCount(requestWrite),
                    session = usageCount(sessionWrite),
                )
            }
            UsageTableRow(
                label = stringResource(Res.string.overview_usage_input_hit),
                request = usageCount(requestRead),
                session = usageCount(sessionRead),
            )
            UsageTableRow(
                label = stringResource(Res.string.overview_usage_output),
                request = usageCount(request?.outputTokens),
                session = usageCount(session.output.takeIf { session.outputReported }),
                emphasized = true,
            )
            UsageTableRow(
                label = stringResource(Res.string.overview_usage_successful_requests),
                request = "1",
                session = viewModel.requestCount.takeIf { it > 0 }?.toString() ?: "—",
            )

            HorizontalDivider(color = colors.divider)
            // 双价格进表格两行：标准价主行 + 该渠道实际次行（供应商名附在标签后），口径注释复用 cost_note。
            UsageTableRow(
                label = stringResource(Res.string.overview_usage_standard_price),
                request = usageCost(viewModel.costForModel(selectedModel?.id)),
                session = usageCost(cost.total),
                emphasized = true,
            )
            UsageTableRow(
                label = stringResource(Res.string.overview_usage_provider_actual) +
                    selectedModel?.provider?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "",
                request = usageCost(viewModel.providerCostForModel(selectedModel?.id)),
                session = usageCost(cost.providerEstimatedTotal),
            )
            UsageCostNote()
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
                providerEstimate = stringResource(Res.string.overview_usage_provider_actual),
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
                    providerEstimate = usageCost(group.providerEstimate),
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
                providerEstimate = usageCost(statistics?.totalProviderEstimatedCost),
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
                providerEstimate = group.providerEstimate,
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
                providerEstimate = statistics?.totalProviderEstimatedCost,
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
    providerEstimate: Double? = null,
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
        // 双价格：标准价主行 + 该渠道实际次行（次级样式；无供应商价显示 —）
        UsageLine(
            stringResource(Res.string.overview_usage_provider_actual),
            usageCost(providerEstimate),
            secondary = true,
        )
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

/** 组下树形明细行：输入 / ├ 未命中 / ├ 缓存写入 / └ 缓存命中（未上报显示 —）。 */
private val UsageTableHeaderStyle = TextStyle(fontSize = 10.sp, lineHeight = 14.sp)

/** 双列表格单元格：数字右对齐；空文本仅占位。 */
@Composable
private fun UsageTableCell(text: String, modifier: Modifier, color: Color, style: TextStyle) {
    Text(text, modifier = modifier, textAlign = TextAlign.End, color = color, style = style)
}

/**
 * 双列表格数据行：标签列 + 本次 + 会话累计（右对齐）。
 * 主行（输入/输出/标准价）= 13sp Medium textPrimary；次行 = 11sp textSecondary；
 * 标签 11sp，主行 textSecondary、次行 textMuted（字重只用 Regular/Medium，层级靠字号+灰度）。
 */
@Composable
private fun UsageTableRow(label: String, request: String, session: String, emphasized: Boolean = false) {
    val colors = LocalMederiColors.current
    val valueStyle = if (emphasized) {
        TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    } else {
        TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        UsageTableCell(
            text = label,
            modifier = Modifier.weight(1.3f),
            color = if (emphasized) colors.textSecondary else colors.textMuted,
            style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
        )
        UsageTableCell(
            text = request,
            modifier = Modifier.weight(1f),
            color = if (emphasized) colors.textPrimary else colors.textSecondary,
            style = valueStyle,
        )
        UsageTableCell(
            text = session,
            modifier = Modifier.weight(1f),
            color = if (emphasized) colors.textPrimary else colors.textSecondary,
            style = valueStyle,
        )
    }
}

@Composable
private fun UsageCacheDetail(input: Long?, read: Long?, write: Long?) {
    val colors = LocalMederiColors.current
    // 树形明细：父行输入 + ├ 未命中 / ├ 缓存写入（write == null 时不渲染）/ └ 缓存命中（前缀在文案资源内）
    Column {
        Text(
            stringResource(Res.string.overview_usage_input) + " " + usageCount(input),
            color = colors.textSecondary,
            style = WorkspaceStyle.Caption,
        )
        Text(
            stringResource(Res.string.overview_usage_input_miss) + " " +
                usageCount(uncachedInputTokens(input, read, write)),
            color = colors.textSecondary,
            style = WorkspaceStyle.Caption,
        )
        if (write != null) {
            Text(
                stringResource(Res.string.overview_usage_input_write) + " " + usageCount(write),
                color = colors.textSecondary,
                style = WorkspaceStyle.Caption,
            )
        }
        Text(
            stringResource(Res.string.overview_usage_input_hit) + " " + usageCount(read),
            color = colors.textSecondary,
            style = WorkspaceStyle.Caption,
        )
    }
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
    // estimate 单元格次行（11sp textSecondary，该渠道实际）；表头传口径标签
    providerEstimate: String,
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
        Column(
            modifier = Modifier.weight(1.2f),
            horizontalAlignment = Alignment.End,
        ) {
            Text(estimate, textAlign = TextAlign.End, style = style, color = colors.textPrimary)
            Text(
                providerEstimate,
                textAlign = TextAlign.End,
                color = colors.textSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun UsageLine(label: String, value: String, secondary: Boolean = false) {
    val colors = LocalMederiColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 次级样式（双价格该渠道实际行）：label muted + value 11sp textSecondary；主行保持 Title
        Text(
            label,
            Modifier.weight(1f),
            color = if (secondary) colors.textMuted else colors.textSecondary,
            style = WorkspaceStyle.Caption,
        )
        Text(
            value,
            color = if (secondary) colors.textSecondary else colors.textPrimary,
            style = if (secondary) TextStyle(fontSize = 11.sp, lineHeight = 15.sp) else WorkspaceStyle.Title,
        )
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
