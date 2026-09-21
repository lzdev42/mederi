package xyz.mederi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.copy_done
import mederi.app.shared.generated.resources.subagentui_agent_id
import mederi.app.shared.generated.resources.subagentui_briefing
import mederi.app.shared.generated.resources.subagentui_empty
import mederi.app.shared.generated.resources.subagentui_role_executor
import mederi.app.shared.generated.resources.subagentui_role_researcher
import mederi.app.shared.generated.resources.subagentui_status_completed
import mederi.app.shared.generated.resources.subagentui_status_error
import mederi.app.shared.generated.resources.subagentui_status_running
import mederi.app.shared.generated.resources.subagentui_status_stopped
import mederi.app.shared.generated.resources.subagentui_tracker_title
import mederi.app.shared.generated.resources.worktrace_collapse
import mederi.app.shared.generated.resources.worktrace_expand
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.MarkdownView
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.SubagentState
import xyz.mederi.core.contract.models.SubagentToolResult
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.ui.SubagentReportMarkdown
import xyz.mederi.core.ui.WorkspaceViewModel
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors

/**
 * 格式化 ISO 8601 时间戳为简洁的时间展示（如 "14:25:30"）
 */
private fun formatCompactTime(isoTime: String): String {
    if (isoTime.isBlank()) return ""
    val tIndex = isoTime.indexOf('T')
    if (tIndex >= 0 && tIndex + 8 < isoTime.length) {
        return isoTime.substring(tIndex + 1, tIndex + 9)
    }
    return isoTime.take(19)
}

/**
 * 运行中动态微视觉指示器（平滑顺畅旋转的 Loader，精致且低能耗）
 */
@Composable
private fun RunningStatusIndicator(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = FeatherIcons.Loader,
            contentDescription = "Running",
            tint = color,
            modifier = Modifier.size(12.dp).rotate(rotation)
        )
    }
}

/**
 * 单个子 Agent 任务行（借鉴 GitHub Actions / Linear 极简状态设计，清晰直观）
 */
@Composable
private fun SubAgentTaskRow(
    subagent: SubagentState,
    colors: MederiColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isResearcher = subagent.role.equals("RESEARCHER", ignoreCase = true)
    val roleIcon = if (isResearcher) FeatherIcons.Search else FeatherIcons.Cpu
    val roleLabel = if (isResearcher) stringResource(Res.string.subagentui_role_researcher) else stringResource(Res.string.subagentui_role_executor)

    val modelLabel = buildString {
        append(subagent.modelName.ifBlank { subagent.modelId })
        if (!subagent.reasoningLevel.isNullOrBlank()) {
            append(" (${subagent.reasoningLevel})")
        }
    }

    val timeLabel = remember(subagent.startedAt) { formatCompactTime(subagent.startedAt) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceWorkspace)
            .border(1.dp, colors.divider.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态微视觉（告别冗长笨重的文字框，采用高辨识度精致图形与色彩语义）
        when (subagent.status.uppercase()) {
            "RUNNING" -> RunningStatusIndicator(color = colors.accentPrimary)
            "COMPLETED" -> Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(colors.accentSuccess.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.CheckCircle,
                    contentDescription = null,
                    tint = colors.accentSuccess,
                    modifier = Modifier.size(12.dp)
                )
            }
            "ERROR" -> Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(colors.accentDanger.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.AlertCircle,
                    contentDescription = null,
                    tint = colors.accentDanger,
                    modifier = Modifier.size(12.dp)
                )
            }
            else -> Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(colors.textMuted.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.MinusCircle,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        // 中间文本内容
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // 第一行：角色微标 + 模型标识 + 启动时刻
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 角色徽标
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceCardBorder.copy(alpha = 0.4f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = roleIcon,
                        contentDescription = null,
                        tint = colors.accentPrimary,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = roleLabel,
                        color = colors.textPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 模型
                Text(
                    text = modelLabel,
                    color = colors.textSecondary,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                // 启动时间
                if (timeLabel.isNotBlank()) {
                    Text(
                        text = timeLabel,
                        color = colors.textMuted,
                        fontSize = 10.sp
                    )
                }
            }

            // 第二行：主 AI 派发的任务目标或简报预览
            val previewText = subagent.task.ifBlank { subagent.briefing.orEmpty() }
            if (previewText.isNotBlank()) {
                Text(
                    text = previewText.replace("\n", " "),
                    color = colors.textPrimary.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 右侧提示小箭头
        Icon(
            imageVector = FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = colors.textMuted.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * 点击子 Agent 弹出的大窗详情展示 (Modal Dialog)
 */
@Composable
fun SubAgentDetailDialog(
    subagent: SubagentState,
    viewModel: WorkspaceViewModel,
    colors: MederiColors,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var isCommandCopied by remember { mutableStateOf(false) }
    var isAgentIdCopied by remember { mutableStateOf(false) }

    val isResearcher = subagent.role.equals("RESEARCHER", ignoreCase = true)
    val roleIcon = if (isResearcher) FeatherIcons.Search else FeatherIcons.Cpu
    val roleLabel = if (isResearcher) stringResource(Res.string.subagentui_role_researcher) else stringResource(Res.string.subagentui_role_executor)

    val (statusLabel, statusColor, statusIcon) = when (subagent.status.uppercase()) {
        "RUNNING" -> Triple(stringResource(Res.string.subagentui_status_running), colors.accentPrimary, FeatherIcons.Loader)
        "COMPLETED" -> Triple(stringResource(Res.string.subagentui_status_completed), colors.accentSuccess, FeatherIcons.CheckCircle)
        "ERROR" -> Triple(stringResource(Res.string.subagentui_status_error), colors.accentDanger, FeatherIcons.AlertCircle)
        "STOPPED" -> Triple(stringResource(Res.string.subagentui_status_stopped), colors.textMuted, FeatherIcons.MinusCircle)
        else -> Triple(subagent.status, colors.textSecondary, FeatherIcons.Cpu)
    }

    // 从消息历史中查找对应的汇报 markdown
    val reportMarkdown = remember(subagent.agentId, viewModel.messages) {
        viewModel.messages.asReversed().firstNotNullOfOrNull { msg ->
            msg.blocks.filterIsInstance<ChatBlock.ToolCall>().firstNotNullOfOrNull { tc ->
                if (tc.name in SubagentReportMarkdown.REPORT_TOOL_NAMES) {
                    val st = tc.state
                    if (st is ToolCallState.Completed) {
                        val decoded = SubagentToolResult.decode(st.output)
                        if (decoded?.agentId == subagent.agentId && decoded.status == "COMPLETED") {
                            SubagentReportMarkdown.fromToolResult(tc.name, st.output)
                        } else null
                    } else null
                } else null
            }
        }
    }

    val scrollState = rememberScrollState()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.surfaceCardBorder),
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .padding(vertical = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. 顶部 Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Users,
                            contentDescription = null,
                            tint = colors.accentPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "子 Agent 任务详情",
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        // 状态微标
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(statusColor.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (subagent.status.uppercase() == "RUNNING") {
                                RunningStatusIndicator(color = statusColor, modifier = Modifier.size(12.dp))
                            } else {
                                Icon(
                                    imageVector = statusIcon,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                            Text(
                                text = statusLabel,
                                color = statusColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // 关闭按钮
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = "Close",
                            tint = colors.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                HorizontalDivider(color = colors.divider)

                // 2. 详情滚动区域
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 基本元数据网格
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surfaceWorkspace)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Agent ID 行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Agent ID", color = colors.textMuted, fontSize = 11.sp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = subagent.agentId,
                                    color = colors.textPrimary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            clipboardManager.setText(AnnotatedString(subagent.agentId))
                                            isAgentIdCopied = true
                                        }
                                        .padding(2.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isAgentIdCopied) FeatherIcons.Check else FeatherIcons.Copy,
                                        contentDescription = "Copy ID",
                                        tint = if (isAgentIdCopied) colors.accentSuccess else colors.textMuted,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }

                        // 角色
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "协同角色", color = colors.textMuted, fontSize = 11.sp)
                            Text(text = roleLabel, color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }

                        // 执行模型
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "分配模型", color = colors.textMuted, fontSize = 11.sp)
                            val modelDisplay = buildString {
                                append(subagent.modelName.ifBlank { subagent.modelId })
                                if (!subagent.reasoningLevel.isNullOrBlank()) {
                                    append(" (${subagent.reasoningLevel})")
                                }
                            }
                            Text(text = modelDisplay, color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }

                        // 启动时间
                        if (subagent.startedAt.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "启动时刻", color = colors.textMuted, fontSize = 11.sp)
                                Text(text = subagent.startedAt, color = colors.textPrimary, fontSize = 11.sp)
                            }
                        }
                    }

                    // 主 AI 发送的命令（Task Prompt）
                    if (subagent.task.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "主 AI 下发任务指令",
                                    color = colors.textSecondary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            clipboardManager.setText(AnnotatedString(subagent.task))
                                            isCommandCopied = true
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isCommandCopied) FeatherIcons.Check else FeatherIcons.Copy,
                                        contentDescription = "Copy Command",
                                        tint = if (isCommandCopied) colors.accentSuccess else colors.accentPrimary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = if (isCommandCopied) stringResource(Res.string.copy_done) else stringResource(Res.string.copy),
                                        color = if (isCommandCopied) colors.accentSuccess else colors.accentPrimary,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceWorkspace)
                                    .border(1.dp, colors.divider, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = subagent.task,
                                        color = colors.textPrimary,
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 17.sp
                                    )
                                }
                            }
                        }
                    }

                    // 任务简报 (Briefing)
                    if (!subagent.briefing.isNullOrBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "任务目标与简报",
                                color = colors.textSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceWorkspace.copy(alpha = 0.6f))
                                    .border(1.dp, colors.divider, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = subagent.briefing.orEmpty(),
                                    color = colors.textPrimary,
                                    fontSize = 11.5.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }

                    // 执行汇报产出 (Markdown)
                    if (!reportMarkdown.isNullOrBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "执行汇报成果",
                                color = colors.textSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceWorkspace)
                                    .border(1.dp, colors.divider, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                MarkdownView(
                                    content = reportMarkdown,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 概览面板内的子 Agent 任务管理卡片（支持折叠、实施状态视觉监控与点击大弹窗）
 */
@Composable
fun SubAgentManagementCard(
    subagents: List<SubagentState>,
    viewModel: WorkspaceViewModel,
    colors: MederiColors = LocalMederiColors.current,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }
    var selectedSubagent by remember { mutableStateOf<SubagentState?>(null) }

    val runningCount = subagents.count { it.status.equals("RUNNING", ignoreCase = true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 卡片 Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { isExpanded = !isExpanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.Users,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "子 Agent 任务",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "${subagents.size}",
                    color = colors.accentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                // 运行中微标
                if (runningCount > 0) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.accentPrimary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        RunningStatusIndicator(color = colors.accentPrimary, modifier = Modifier.size(10.dp))
                        Text(
                            text = "$runningCount 运行中",
                            color = colors.accentPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 折叠/展开箭头
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isExpanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // 列表区（支持平滑折叠）
        AnimatedVisibility(visible = isExpanded) {
            if (subagents.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.subagentui_empty),
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // 倒序展示：最新启动的子任务排在最前
                    val sortedList = remember(subagents) { subagents.reversed() }
                    sortedList.forEach { subagent ->
                        SubAgentTaskRow(
                            subagent = subagent,
                            colors = colors,
                            onClick = { selectedSubagent = subagent }
                        )
                    }
                }
            }
        }
    }

    // 详情大弹窗
    selectedSubagent?.let { agent ->
        SubAgentDetailDialog(
            subagent = agent,
            viewModel = viewModel,
            colors = colors,
            onDismiss = { selectedSubagent = null }
        )
    }
}

/**
 * 兼容旧组件定义
 */
@Composable
fun SubAgentCard(
    subagent: SubagentState,
    colors: MederiColors = LocalMederiColors.current,
    isExpandedDefault: Boolean = false,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(isExpandedDefault) }

    val roleIcon = if (subagent.role.equals("RESEARCHER", ignoreCase = true)) FeatherIcons.Search else FeatherIcons.Cpu
    val roleLabel = if (subagent.role.equals("RESEARCHER", ignoreCase = true)) stringResource(Res.string.subagentui_role_researcher) else stringResource(Res.string.subagentui_role_executor)

    val modelLabel = buildString {
        append(subagent.modelName.ifBlank { subagent.modelId })
        if (!subagent.reasoningLevel.isNullOrBlank()) {
            append(" (${subagent.reasoningLevel})")
        }
    }

    val (statusLabel, statusColor) = when (subagent.status.uppercase()) {
        "RUNNING" -> stringResource(Res.string.subagentui_status_running) to colors.accentPrimary
        "COMPLETED" -> stringResource(Res.string.subagentui_status_completed) to colors.accentSuccess
        "ERROR" -> stringResource(Res.string.subagentui_status_error) to colors.accentDanger
        "STOPPED" -> stringResource(Res.string.subagentui_status_stopped) to colors.textMuted
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
                    contentDescription = if (isExpanded) stringResource(Res.string.worktrace_collapse) else stringResource(Res.string.worktrace_expand),
                    tint = colors.textMuted,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { isExpanded = !isExpanded }
                )
            }
        }

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
                        text = stringResource(Res.string.subagentui_briefing, subagent.briefing.orEmpty()),
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
                        text = stringResource(Res.string.subagentui_agent_id, subagent.agentId),
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

/**
 * 兼容旧 Tab 视图调用
 */
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
            text = stringResource(Res.string.subagentui_tracker_title, subagents.size),
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
                    text = stringResource(Res.string.subagentui_empty),
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
