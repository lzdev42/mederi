package xyz.mederi.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Square
import compose.icons.feathericons.Activity
import compose.icons.feathericons.Users
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.subagent_card_title
import mederi.app.shared.generated.resources.subagent_running_count
import mederi.app.shared.generated.resources.subagentui_empty
import mederi.app.shared.generated.resources.subagentui_status_running
import mederi.app.shared.generated.resources.subagentui_stop_task
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.SubagentState
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.MederiRoleTag

/**
 * 概览浮动窗子 Agent 行。
 *
 * 折叠态摘要：有运行中的子代理时显示强调色圆点 + 「N 运行中」（accentText），
 * 否则只显示子代理总数计数（textSecondary 12sp）。
 *
 * 展开态：每个任务渲染一张内嵌卡片（角色标签 + mono 模型名 + 状态图标 + 单行预览 +
 * 运行中红色停止按钮 / 其余灰色 chevron），点击行打开右侧子代理对话面板；
 * 有运行中的子代理时整栏（含行头与展开内容）使用单色呼吸边框，折叠仍可见。
 * 列表倒序展示（最新启动在前）。
 */
@Composable
fun OverviewSubAgentRow(
    viewModel: WorkspaceViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val subagents = viewModel.subagents
    val runningCount = subagents.count { it.status.equals("RUNNING", ignoreCase = true) }

    OverviewAccordionRow(
        icon = FeatherIcons.Users,
        title = stringResource(Res.string.subagent_card_title),
        summary = {
            if (runningCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(colors.accentPrimary)
                    )
                    Text(
                        text = stringResource(Res.string.subagent_running_count, runningCount),
                        color = colors.accentText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    text = subagents.size.toString(),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier.overviewTaskIndicator(runningCount > 0),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        ) {
            if (subagents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MederiSpacing.SM),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(Res.string.subagentui_empty),
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                // 倒序：最新启动的子代理排在最前
                subagents.reversed().forEach { subagent ->
                    SubAgentOverviewCard(
                        subagent = subagent,
                        colors = colors,
                        onClick = { viewModel.openSubagentPanel(subagent) },
                        onStop = { viewModel.stopSubagent(subagent.agentId) },
                    )
                }
            }
        }
    }

}

/**
 * 单个子代理内嵌卡片：surfaceHover 底 + Card 圆角 + 8/10dp 内边距。
 *
 * 左侧：meta 行（MederiRoleTag + mono 模型名(含推理档位) + 状态图标）+ 任务/简报单行预览；
 * 右侧：运行中红底停止按钮，其余状态灰色 chevron。整行点击打开详情。
 */
@Composable
private fun SubAgentOverviewCard(
    subagent: SubagentState,
    colors: MederiColors,
    onClick: () -> Unit,
    onStop: () -> Unit,
) {
    val isRunning = subagent.status.equals("RUNNING", ignoreCase = true)

    val modelLabel = buildString {
        append(subagent.modelName.ifBlank { subagent.modelId })
        if (!subagent.reasoningLevel.isNullOrBlank()) {
            append(" (${subagent.reasoningLevel})")
        }
    }
    val previewText = subagent.task.ifBlank { subagent.briefing.orEmpty() }

    // hover 底色：常态透明，鼠标移入才浮出（对齐 OverviewAccordionRow 行头范式）
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WorkspaceStyle.ObjectRadius))
            .background(if (isHovered) colors.surfaceHover else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 4.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧 grow 区域
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
        ) {
            // meta 行：角色标签 + 模型名(mono) + 状态图标
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MederiSpacing.Tight),
            ) {
                MederiRoleTag(text = subagent.role)
                Text(
                    text = modelLabel,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                SubAgentStatusIcon(subagent = subagent, colors = colors)
            }

            // 预览行：任务命令，缺省回退简报；换行压成空格、单行省略
            if (previewText.isNotBlank()) {
                Text(
                    text = previewText.replace("\n", " "),
                    color = colors.textPrimary,
                    style = WorkspaceStyle.Body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 右侧操作：运行中红色停止按钮，其余灰色 chevron（可点击查看详情）
        if (isRunning) {
            Box(
                modifier = Modifier
                    .size(WorkspaceStyle.IconTarget)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Transparent)
                    .clickable { onStop() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FeatherIcons.Square,
                    contentDescription = stringResource(Res.string.subagentui_stop_task),
                    tint = colors.accentDanger,
                    modifier = Modifier.size(13.dp),
                )
            }
        } else {
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = colors.textMuted.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * 子代理状态图标（20dp 指示器）。
 *
 * RUNNING → 静态 Activity 图标（整栏统一呼吸）；
 * COMPLETED → Check / accentSuccess；ERROR → AlertTriangle / accentDanger；else → Minus / textMuted。
 */
@Composable
private fun SubAgentStatusIcon(
    subagent: SubagentState,
    colors: MederiColors,
) {
    if (subagent.status.equals("RUNNING", ignoreCase = true)) {
        Icon(
            imageVector = FeatherIcons.Activity,
            contentDescription = stringResource(Res.string.subagentui_status_running),
            tint = colors.accentText,
            modifier = Modifier.size(14.dp),
        )
    } else {
        val (icon, tint) = when (subagent.status.uppercase()) {
            "COMPLETED" -> FeatherIcons.Check to colors.accentSuccess
            "ERROR" -> FeatherIcons.AlertTriangle to colors.accentDanger
            else -> FeatherIcons.Minus to colors.textMuted
        }
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}