package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Minimize2
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.dock_compact_context
import mederi.app.shared.generated.resources.dock_context_max
import mederi.app.shared.generated.resources.dock_context_metrics
import mederi.app.shared.generated.resources.dock_context_unset
import mederi.app.shared.generated.resources.dock_context_used
import mederi.app.shared.generated.resources.dock_cost_title
import mederi.app.shared.generated.resources.dock_requests_count
import mederi.app.shared.generated.resources.dock_requests_title
import mederi.app.shared.generated.resources.rp_todo_list_title
import mederi.app.shared.generated.resources.rp_tokens_used
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.TodoItem
import xyz.mederi.core.contract.models.TodoStatus
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.CardHeader
import xyz.mederi.ui.components.atoms.MederiCompactStrokeButton
import xyz.mederi.ui.components.atoms.PanelCard

@Composable
internal fun ContextMetricsCard(
    usedTokens: Long,
    maxTokens: Int,
    requestCount: Int,
    costUsd: Double,
    onCompact: () -> Unit,
    colors: MederiColors
) {
    // 模型未配置 contextWindow 时（OPENAI_CHAT 拉模型只返回 id，无元数据），无法计算占比。
    // 不兜底造数据：百分比显示 "--"，进度条不渲染，并提示去供应商设置里补 contextWindow。
    val hasWindow = maxTokens > 0
    val progressRatio = if (hasWindow) (usedTokens.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f) else 0f
    val percentText = if (hasWindow) "${(progressRatio * 100).toInt()}%" else "--"

    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Card Header
        CardHeader(
            icon = null,
            title = stringResource(Res.string.dock_context_metrics),
            count = {
                Text(
                    text = stringResource(Res.string.dock_context_used, percentText),
                    color = colors.accentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        )

        // Progress Bar (更细小 4dp)；无 contextWindow 时不渲染，避免误读为 0% 已用
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (hasWindow) {
                LinearProgressIndicator(
                    progress = { progressRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = colors.accentPrimary,
                    trackColor = colors.buttonSecondary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(Res.string.rp_tokens_used, usedTokens),
                    color = colors.textSecondary,
                    fontSize = 10.sp
                )
                Text(
                    text = if (hasWindow) stringResource(Res.string.dock_context_max, maxTokens)
                           else stringResource(Res.string.dock_context_unset),
                    color = colors.textMuted,
                    fontSize = 10.sp
                )
            }
        }

        // Metrics Grid: Request count & Estimated cost
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 数字小卡内联保留：无描边 / surfaceWorkspace 底 / 13sp 紧凑字号，与 MederiMetricCard（border + 20sp Metric）视觉不一致，待配色迁移时对齐
            // Request Count Box
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceWorkspace)
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(text = stringResource(Res.string.dock_requests_title), color = colors.textMuted, fontSize = 10.sp)
                Text(
                    text = stringResource(Res.string.dock_requests_count, requestCount),
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Estimated Cost Box
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceWorkspace)
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(text = stringResource(Res.string.dock_cost_title), color = colors.textMuted, fontSize = 10.sp)
                Text(
                    text = "$${formatCost(costUsd)}",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Compact Context Button（收敛为 MederiCompactStrokeButton：紧凑描边变体，icon + 11sp 文本）
        MederiCompactStrokeButton(
            text = stringResource(Res.string.dock_compact_context),
            onClick = onCompact,
            icon = FeatherIcons.Minimize2,
            modifier = Modifier.fillMaxWidth(),
        )
        }
    }
}

@Composable
internal fun TodoListCard(
    todoList: List<TodoItem>,
    colors: MederiColors
) {
    val completedCount = todoList.count { it.status == TodoStatus.Completed }

    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardHeader(
            icon = null,
            title = stringResource(Res.string.rp_todo_list_title),
            count = {
                Text(
                    text = "$completedCount/${todoList.size}",
                    color = colors.textSecondary,
                    fontSize = 10.sp
                )
            }
        )

        // Tasks list（四态：Pending 灰 / InProgress 高亮 / Completed 绿勾划线 / Failed 红）
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            todoList.forEach { task ->
                val isDone = task.status == TodoStatus.Completed
                val isRunning = task.status == TodoStatus.InProgress
                val isFailed = task.status == TodoStatus.Failed
                val boxColor = when {
                    isDone -> colors.accentPrimary
                    isRunning -> colors.accentPrimary
                    isFailed -> colors.accentDanger
                    else -> colors.buttonSecondary
                }
                val textColor = when {
                    isDone -> colors.textMuted
                    isFailed -> colors.accentDanger
                    else -> colors.textPrimary
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceWorkspace)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (isRunning) boxColor.copy(alpha = 0.25f) else boxColor
                            )
                            .border(
                                1.dp,
                                boxColor,
                                RoundedCornerShape(3.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDone) {
                            Icon(
                                imageVector = FeatherIcons.Check,
                                contentDescription = null,
                                tint = colors.onAccentPrimary,
                                modifier = Modifier.size(10.dp)
                            )
                        } else if (isRunning) {
                            // 进行中：实心圆点（不引图标，点即"正在做"的视觉焦点）
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colors.accentPrimary)
                            )
                        }
                    }

                    Text(
                        text = task.content,
                        color = textColor,
                        fontSize = 11.sp,
                        fontWeight = if (isRunning) FontWeight.SemiBold else FontWeight.Normal,
                        textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        }
    }
}

private fun formatCost(cost: Double): String {
    return if (cost < 0.001) "0.000" else (kotlin.math.round(cost * 1000) / 1000.0).toString()
}