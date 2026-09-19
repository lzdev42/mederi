package xyz.mederi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Search
import xyz.mederi.core.contract.models.SubagentState
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors

@Composable
fun SubAgentCard(
    subagent: SubagentState,
    colors: MederiColors = LocalMederiColors.current,
    isExpandedDefault: Boolean = false,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(isExpandedDefault) }

    val roleIcon = if (subagent.role.equals("RESEARCHER", ignoreCase = true)) FeatherIcons.Search else FeatherIcons.Cpu
    val roleLabel = if (subagent.role.equals("RESEARCHER", ignoreCase = true)) "Researcher" else "Executor"

    val modelLabel = buildString {
        append(subagent.modelName.ifBlank { subagent.modelId })
        if (!subagent.reasoningLevel.isNullOrBlank()) {
            append(" (${subagent.reasoningLevel})")
        }
    }

    val (statusLabel, statusColor) = when (subagent.status.uppercase()) {
        "RUNNING" -> "运行中" to colors.accentPrimary
        "COMPLETED" -> "已完成" to colors.accentSuccess
        "ERROR" -> "失败" to colors.accentDanger
        "STOPPED" -> "已终止" to colors.textMuted
        else -> subagent.status to colors.textSecondary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.divider, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header Row: [Role Icon + Role/Model] ... [Status Badge + Expand Chevron]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Icon(
                    imageVector = roleIcon,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = roleLabel,
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "·",
                    color = colors.textMuted,
                    fontSize = 12.sp
                )
                Text(
                    text = modelLabel,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusLabel,
                        color = statusColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Icon(
                    imageVector = if (isExpanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = colors.textMuted,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { isExpanded = !isExpanded }
                )
            }
        }

        // Task preview / content
        if (subagent.task.isNotBlank()) {
            Text(
                text = subagent.task,
                color = colors.textPrimary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Expanded Details
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HorizontalDivider(color = colors.divider)

                if (!subagent.briefing.isNullOrBlank()) {
                    Text(
                        text = "简报: ${subagent.briefing}",
                        color = colors.textSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ID: ${subagent.agentId}",
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (subagent.startedAt.isNotBlank()) {
                        Text(
                            text = subagent.startedAt,
                            color = colors.textMuted,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SubAgentTabContent(
    subagents: List<SubagentState>,
    colors: MederiColors = LocalMederiColors.current,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "子 AGENT 任务追踪器 (${subagents.size})",
            color = colors.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )

        if (subagents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 30.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "当前会话暂无派生的子 Agent 任务",
                    color = colors.textMuted,
                    fontSize = 11.sp
                )
            }
        } else {
            subagents.forEach { subagent ->
                SubAgentCard(
                    subagent = subagent,
                    colors = colors,
                    isExpandedDefault = false
                )
            }
        }
    }
}
