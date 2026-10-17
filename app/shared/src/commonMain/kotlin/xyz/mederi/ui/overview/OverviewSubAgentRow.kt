package xyz.mederi.ui.overview

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import compose.icons.feathericons.Loader
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Users
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.subagent_card_title
import mederi.app.shared.generated.resources.subagent_running_count
import mederi.app.shared.generated.resources.subagentui_empty
import mederi.app.shared.generated.resources.subagentui_stop_task
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.SubagentState
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.SubAgentDetailDialog
import xyz.mederi.ui.components.atoms.MederiRoleTag
import xyz.mederi.ui.components.atoms.WorkingAnimationStyle
import xyz.mederi.ui.components.atoms.workingAnimation

/**
 * 概览浮动窗子 Agent 行。
 *
 * 折叠态摘要：有运行中的子代理时显示强调色呼吸圆点 + 「N 运行中」（accentText），
 * 否则只显示子代理总数计数（textSecondary 12sp）。
 *
 * 展开态：每个任务渲染一张内嵌卡片（角色标签 + mono 模型名 + 状态图标 + 单行预览 +
 * 运行中红色停止按钮 / 其余灰色 chevron），点击行打开复用的 [SubAgentDetailDialog]；
 * 有运行中的子代理时整块列表区套 BorderBeam 流光边框（浅层余光提示，参照 SubAgentManagementCard）。
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

    var selectedSubagent by remember { mutableStateOf<SubagentState?>(null) }

    OverviewAccordionRow(
        icon = FeatherIcons.Users,
        title = stringResource(Res.string.subagent_card_title),
        summary = {
            if (runningCount > 0) {
                // 呼吸圆点：0.35~1.0 opacity，1600ms Reverse（同 FloatingOverviewWindow 胶囊脉冲）
                val pulseTransition = rememberInfiniteTransition(label = "overview_subagent_pulse")
                val dotAlpha by pulseTransition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
                    label = "overview_subagent_pulse_alpha",
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(colors.accentPrimary.copy(alpha = dotAlpha))
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
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .workingAnimation(
                    style = WorkingAnimationStyle.BorderBeam,
                    enabled = runningCount > 0,
                    shape = RoundedCornerShape(8.dp),
                    primaryColor = colors.accentPrimary,
                    secondaryColor = colors.accentSecondary,
                ),
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
                        onClick = { selectedSubagent = subagent },
                        onStop = { viewModel.stopSubagent(subagent.agentId) },
                    )
                }
            }
        }
    }

    // 详情大弹窗（复用公共组件，不重写）
    selectedSubagent?.let { sub ->
        SubAgentDetailDialog(
            subagent = sub,
            viewModel = viewModel,
            colors = colors,
            onDismiss = { selectedSubagent = null },
        )
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
            .background(if (isHovered) colors.surfaceHover else Color.Transparent)
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
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 右侧操作：运行中红色停止按钮，其余灰色 chevron（可点击查看详情）
        if (isRunning) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.accentDanger.copy(alpha = 0.12f))
                    .clickable { onStop() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
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
 * 子代理状态图标（20dp 指示器，参照 SubAgentComponents.RunningStatusIndicator 的视觉语义）。
 *
 * RUNNING → 旋转 Loader（自绘等效 spinner；RunningStatusIndicator 是 private 函数不可复用）；
 * COMPLETED → Check / accentSuccess；ERROR → AlertTriangle / accentDanger；else → Minus / textMuted。
 */
@Composable
private fun SubAgentStatusIcon(
    subagent: SubagentState,
    colors: MederiColors,
) {
    if (subagent.status.equals("RUNNING", ignoreCase = true)) {
        val infiniteTransition = rememberInfiniteTransition(label = "overview_subagent_spinner")
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "overview_subagent_spinner_rotation",
        )
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FeatherIcons.Loader,
                contentDescription = null,
                tint = colors.accentPrimary,
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
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