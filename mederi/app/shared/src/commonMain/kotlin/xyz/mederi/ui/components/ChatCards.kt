package xyz.mederi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import xyz.emuci.inkcompose.MarkdownView
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.core.contract.models.QuestionRequest
import xyz.mederi.core.ui.AssistantFooterInfo
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors

/**
 * 严重级别枚举（用于分析结论中结构化标签展示）
 */
enum class IssueSeverity {
    CRITICAL, HIGH, MEDIUM, LOW
}

/**
 * 结构化严重度徽标组件 (SeverityBadge)
 */
@Composable
fun SeverityBadge(
    severity: IssueSeverity,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    val (bgColor, textColor, label) = when (severity) {
        IssueSeverity.CRITICAL -> Triple(colors.accentDanger.copy(alpha = 0.14f), colors.accentDanger, "CRITICAL")
        IssueSeverity.HIGH -> Triple(colors.accentWarning.copy(alpha = 0.16f), colors.accentWarning, "HIGH")
        IssueSeverity.MEDIUM -> Triple(colors.accentSecondary.copy(alpha = 0.14f), colors.accentSecondary, "MEDIUM")
        IssueSeverity.LOW -> Triple(colors.surfaceCardBorder, colors.textMuted, "LOW")
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bgColor)
            .padding(horizontal = 4.5.dp, vertical = 1.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            lineHeight = 10.sp
        )
    }
}

/**
 * 一体化思考过程与工具调用聚合卡片 (ThoughtAndActionsBlock)
 * 视觉规范：过程安静、单容器内展开、去冗余计数徽标、极简单行子列表。
 */
@Composable
fun ThoughtAndActionsBlock(
    reasoningParts: List<ChatBlock.Reasoning>,
    toolCalls: List<ToolCallUi>,
    isStreaming: Boolean = false,
    durationMs: Long = 0,
    modifier: Modifier = Modifier,
    // 预计算派生数据（由 WorkspaceViewModel.recomputeChatItems 提供）
    toolSummary: String = "",
    hasFailedTool: Boolean = false,
    isRunning: Boolean = false,
    headerSummary: String = "",
    isReasoningActive: Boolean = false,
) {
    if (reasoningParts.isEmpty() && toolCalls.isEmpty()) return

    val colors = LocalMederiColors.current
    var isExpanded by remember { mutableStateOf(false) }
    // 推理进行中自动展开，推理结束（正文出现/轮次推进）自动折叠。
    // 手动开合不被覆盖：仅在 isReasoningActive 翻转时重置（流式中手动折叠会保持折叠直到推理结束）。
    LaunchedEffect(isReasoningActive) {
        isExpanded = isReasoningActive
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(150)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.thoughtBackground)
            .border(
                1.dp,
                if (hasFailedTool) colors.accentDanger.copy(alpha = 0.35f)
                else colors.thoughtBorder,
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 顶栏汇总条 (Quiet Bar)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // 折叠展开图标（平滑旋转动画）
                Icon(
                    imageVector = FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = arrowRotation }
                )

                // 左侧极简状态指示
                if (isRunning) {
                    CircularProgressIndicator(
                        color = colors.thoughtAccent,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(11.dp)
                    )
                } else {
                    Icon(
                        imageVector = if (toolCalls.isNotEmpty() && reasoningParts.isEmpty()) FeatherIcons.Terminal else FeatherIcons.Cpu,
                        contentDescription = null,
                        tint = if (hasFailedTool) colors.accentDanger else colors.thoughtAccent,
                        modifier = Modifier.size(14.dp)
                    )
                }

                // 汇总说明文字（无字符数冗余）
                Text(
                    text = headerSummary,
                    color = if (hasFailedTool) colors.accentDanger else colors.thoughtText,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = if (toolCalls.isNotEmpty() && reasoningParts.isEmpty()) FontFamily.Monospace else FontFamily.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (durationMs > 0) {
                    Text(
                        text = "(${durationMs.toFloat() / 1000f}s)",
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
            }

            if (hasFailedTool) {
                Text(
                    text = "Failed",
                    color = colors.accentDanger,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 展开内容区 (在同一个容器内部无缝展示，去多层嵌套)
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 1. 思考过程：左侧细导轨线 (Quote Rail) + 自然语言 Markdown（次要文字颜色）
                if (reasoningParts.isNotEmpty()) {
                    val combinedReasoningText = reasoningParts.joinToString("\n\n") { it.text }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(colors.thoughtAccent.copy(alpha = 0.45f))
                        )
                        MarkdownView(
                            content = combinedReasoningText,
                            modifier = Modifier.fillMaxWidth(),
                            enableScrollOverride = false
                        )
                    }
                }

                // 2. 工具调用列表：紧凑无外框列表
                if (toolCalls.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (reasoningParts.isNotEmpty()) 4.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        toolCalls.forEach { toolCall ->
                            ToolCallItemRow(toolCall = toolCall, colors = colors, isStreaming = isStreaming)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 紧凑型子操作行：单行纯文本 + 极弱化状态，高信息密度。
 * 目标参数与失败标记由 ViewModel 预计算（ToolCallUi）。
 */
@Composable
private fun ToolCallItemRow(
    toolCall: ToolCallUi,
    colors: MederiColors,
    isStreaming: Boolean = false
) {
    val isFailed = toolCall.isFailed
    val isRunning = isStreaming && toolCall.state is ToolCallState.Running

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 22.dp)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 极简状态图标：成功弱化为淡灰勾号，运行中微型转圈，失败红叹号
        when {
            isRunning -> {
                CircularProgressIndicator(
                    color = colors.accentPrimary,
                    strokeWidth = 1.2.dp,
                    modifier = Modifier.size(9.dp)
                )
            }
            isFailed -> {
                Icon(
                    imageVector = FeatherIcons.AlertCircle,
                    contentDescription = null,
                    tint = colors.accentDanger,
                    modifier = Modifier.size(11.dp)
                )
            }
            else -> {
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = null,
                    tint = colors.textMuted.copy(alpha = 0.7f),
                    modifier = Modifier.size(11.dp)
                )
            }
        }

        // 工具名（等宽弱化）
        Text(
            text = toolCall.name,
            color = colors.textMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )

        // 核心目标参数（ViewModel 预计算）
        if (!toolCall.target.isNullOrBlank()) {
            Text(
                text = toolCall.target,
                color = colors.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        // 仅在失败时在尾部显示错误提示
        if (isFailed) {
            Text(
                text = "Failed",
                color = colors.accentDanger,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 单独思维链/思考过程折叠面板 (ReasoningBlock)
 */
@Composable
fun ReasoningBlock(
    text: String,
    durationMs: Long = 0,
    isExpandedDefault: Boolean = false,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    var internalExpanded by remember { mutableStateOf(isExpandedDefault) }
    val isExpanded = expanded ?: internalExpanded
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(150)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.thoughtBackground)
            .border(1.dp, colors.thoughtBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (onToggle != null) onToggle()
                    else internalExpanded = !internalExpanded
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = arrowRotation }
                )
                Icon(
                    imageVector = FeatherIcons.Cpu,
                    contentDescription = null,
                    tint = colors.thoughtAccent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Thought Process",
                    color = colors.thoughtText,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Normal
                )
                if (durationMs > 0) {
                    Text(
                        text = "(${durationMs.toFloat() / 1000f}s)",
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(colors.thoughtAccent.copy(alpha = 0.45f))
                )
                MarkdownView(
                    content = text,
                    modifier = Modifier.fillMaxWidth(),
                    enableScrollOverride = false
                )
            }
        }
    }
}

/**
 * 2. 工具调用胶囊 (ToolPill)
 */
@Composable
fun ToolPill(
    toolName: String,
    stateText: String = "Executing...",
    isSuccess: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = FeatherIcons.Terminal,
            contentDescription = null,
            tint = colors.accentSecondary,
            modifier = Modifier.size(11.dp)
        )
        Text(
            text = toolName,
            color = colors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (isSuccess) colors.accentSuccess.copy(alpha = 0.15f) else colors.accentWarning.copy(alpha = 0.15f))
                .padding(horizontal = 5.dp, vertical = 1.5.dp)
        ) {
            Text(
                text = stateText,
                color = if (isSuccess) colors.accentSuccess else colors.accentWarning,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 3. 选择题交互卡片 (QuestionCard)
 *
 * 无状态组件：当前选中答案经 [selectedAnswer] 由调用方传入（唯一真理源 =
 * WorkspaceViewModel.questionAnswers），点击经 [onAnswer] 单向写回，卡片内不持有副本。
 */
@Composable
fun QuestionCard(
    question: QuestionRequest?,
    currentIndex: Int,
    selectedAnswer: String,
    onAnswer: (String, String) -> Unit, // (questionText, selectedAnswer)
    onNextPage: () -> Unit = {},
    onPrevPage: () -> Unit = {},
    onSubmit: () -> Unit = {},
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (question == null) return
    val colors = LocalMederiColors.current
    val qList = question.questions
    val qInfo = qList.getOrNull(currentIndex) ?: qList.firstOrNull() ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.accentPrimary, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "交互提问 [${currentIndex + 1}/${qList.size}]",
                color = colors.accentPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = qInfo.prompt,
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )

        // Options
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            qInfo.options.forEach { opt ->
                val optText = opt
                val isSelected = selectedAnswer == optText
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.15f) else colors.surfaceWorkspace)
                        .border(1.dp, if (isSelected) colors.accentPrimary else colors.divider, RoundedCornerShape(6.dp))
                        .clickable {
                            onAnswer(qInfo.prompt, optText)
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = colors.accentPrimary)
                    )
                    Text(text = optText, color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Action Buttons (支持多题翻页 [上一步] / [下一步/提交])
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("取消", color = colors.textMuted, fontSize = 11.5.sp)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentIndex > 0) {
                    OutlinedButton(
                        onClick = onPrevPage,
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("上一步", fontSize = 11.5.sp, color = colors.textPrimary)
                    }
                }

                if (currentIndex < qList.size - 1) {
                    Button(
                        onClick = onNextPage,
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.buttonSecondary)
                    ) {
                        Text("下一步", fontSize = 11.5.sp, color = colors.textPrimary)
                    }
                } else {
                    Button(
                        onClick = onSubmit,
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary)
                    ) {
                        Text("提交全部答案", fontSize = 11.5.sp, color = colors.onAccentPrimary)
                    }
                }
            }
        }
    }
}

/**
 * 4.5. 计划审批卡片 (PlanApprovalCard)
 *
 * 对标 Proceed 极简设计：
 * - 顶栏：Implementation Plan（点击在右侧扩展窗口打开完整文档）
 * - 正文：AI 生成的 1-2 句精炼摘要
 * - 底部：单只 Proceed 按钮，不批准直接在输入框继续对话
 *
 * @param request 计划审批请求数据
 * @param onApprove 批准并开始执行回调
 * @param onOpenInExtension 在右侧扩展窗口打开完整 Markdown 计划
 */
@Composable
fun PlanApprovalCard(
    request: PlanApprovalRequest?,
    onApprove: () -> Unit,
    onOpenInExtension: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (request == null) return
    val colors = LocalMederiColors.current
    val isApproved = request.status.equals("APPROVED", ignoreCase = true)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. 顶栏 Header（点击在右侧扩展窗口打开完整计划）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { onOpenInExtension() }
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FeatherIcons.FileText,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (request.title.isNotBlank()) "Implementation Plan: ${request.title}" else "Implementation Plan",
                    color = colors.textPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (request.id.isNotBlank()) {
                Text(
                    text = request.id,
                    color = colors.textMuted,
                    fontSize = 11.sp
                )
            }
        }

        // 2. AI 生成的 1-2 句精炼摘要
        val displayText = request.summary.ifBlank { request.title }
        if (displayText.isNotBlank()) {
            Text(
                text = displayText,
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }

        // 3. 底部操作：单个 Proceed 按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onApprove,
                enabled = !isApproved,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentPrimary,
                    disabledContainerColor = colors.buttonSecondary
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = if (isApproved) "已批准" else "Proceed",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isApproved) colors.textMuted else colors.onAccentPrimary
                    )
                    if (!isApproved) {
                        Text(
                            text = "⌘↵",
                            fontSize = 10.5.sp,
                            color = colors.onAccentPrimary.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 用户消息中的大段文本折叠卡片 (UserPastedTextCard)
 */
@Composable
fun UserPastedTextCard(
    attachment: xyz.mederi.core.contract.models.PastedTextAttachment,
    onOpenInExtension: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    var isExpanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(150)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceWorkspace)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clickable { isExpanded = !isExpanded }
            ) {
                Icon(
                    imageVector = FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier
                        .size(13.dp)
                        .graphicsLayer { rotationZ = arrowRotation }
                )
                Icon(
                    imageVector = FeatherIcons.File,
                    contentDescription = null,
                    tint = colors.accentSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "粘贴文本 #${attachment.index}",
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "(${attachment.lineCount} 行 · ${attachment.charCount} 字符)",
                    color = colors.textMuted,
                    fontSize = 11.sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onOpenInExtension != null) {
                    Text(
                        text = "阅读器",
                        color = colors.accentSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onOpenInExtension() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = if (isExpanded) "收起" else "展开",
                    color = colors.accentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { isExpanded = !isExpanded }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceCode)
                    .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = attachment.text,
                        color = colors.onSurfaceCode,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    )
                }
            }
        }
    }
}

/**
 * 用户消息底栏：常驻时间戳、回退操作、复制操作。
 */
@Composable
fun UserMessageFooter(
    createdAt: Long,
    onRollback: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }

    Row(
        modifier = modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. 时间戳（时钟图标 + HH:mm）
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FeatherIcons.Clock,
                contentDescription = null,
                tint = colors.textMuted.copy(alpha = 0.7f),
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = xyz.mederi.formatMessageTime(createdAt),
                color = colors.textMuted.copy(alpha = 0.8f),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // 2. 退回并重新编辑按钮（回退一步 = 撤回本条及后续记录，内容粘贴回输入框，可切换模型/模式后再发）
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .clickable { onRollback() }
                .padding(3.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = FeatherIcons.CornerUpLeft,
                contentDescription = "退回并重新编辑",
                tint = colors.textMuted,
                modifier = Modifier.size(12.dp)
            )
        }

        // 3. 复制按钮
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .clickable {
                    onCopy()
                    copied = true
                }
                .padding(3.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                contentDescription = if (copied) "已复制" else "复制",
                tint = if (copied) colors.accentSuccess else colors.textMuted,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/**
 * assistant 消息底部 footer：模型名 · 审批/自主 · 推理档 · 消耗时长 · 回复结束时间。
 *
 * 全部元数据缺失（历史消息无诊断字段）时不渲染。
 */
@Composable
fun AssistantMessageFooter(
    footer: AssistantFooterInfo,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val segments = mutableListOf<String>()

    footer.modelName?.takeIf { it.isNotBlank() }?.let { segments.add(it) }
    footer.agentMode?.let {
        segments.add(
            when (it) {
                "AUTONOMOUS" -> "自主"
                "APPROVAL" -> "审批"
                else -> it
            }
        )
    }
    footer.thinkingLevel?.takeIf { it.isNotBlank() && it != "NONE" }?.let { segments.add("推理 $it") }
    footer.durationMs?.let { ms ->
        if (ms > 0) segments.add(formatSeconds(ms))
    }
    footer.completedAtMs?.let { ms ->
        if (ms > 0) segments.add("完成 ${xyz.mederi.formatMessageTime(ms)}")
    }

    if (segments.isEmpty()) return

    Row(
        modifier = modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = FeatherIcons.Info,
            contentDescription = null,
            tint = colors.textMuted.copy(alpha = 0.6f),
            modifier = Modifier.size(11.dp)
        )
        Text(
            text = segments.joinToString(" · "),
            color = colors.textMuted.copy(alpha = 0.8f),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatSeconds(ms: Long): String =
    if (ms >= 10_000) {
        // KMP 兼容的一位小数秒（wasmJs 无 String.format）
        val secs = ms / 1000
        val tenths = (ms % 1000) / 100
        "${secs}.${tenths}s"
    } else {
        "${ms}ms"
    }
