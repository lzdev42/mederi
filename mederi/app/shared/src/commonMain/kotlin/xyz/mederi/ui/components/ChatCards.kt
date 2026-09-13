package xyz.mederi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.TextStyle
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
 * 极简大脑矢量图标（用于思维链/深度思考展示）
 */
val BrainIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Brain",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        val nodes = PathParser().parsePathString(
            "M12 18V5 " +
            "M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4 " +
            "M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5 " +
            "M17.997 5.125a4 4 0 0 1 2.526 5.77 " +
            "M18 18a4 4 0 0 0 2-7.464 " +
            "M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517 " +
            "M6 18a4 4 0 0 1-2-7.464 " +
            "M6.003 5.125a4 4 0 0 0-2.526 5.77"
        ).toNodes()
        addPath(
            pathData = nodes,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }.build()
}

/**
 * 单独思维链/思考过程折叠面板 (ReasoningBlock)
 * 严格还原极简设计：大脑图标胶囊 + 展开后轻量导轨线
 */
@Composable
fun ReasoningBlock(
    text: String,
    durationMs: Long = 0,
    isStreaming: Boolean = false,
    isReasoningActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (text.isBlank() && !isReasoningActive) return

    val colors = LocalMederiColors.current
    var isExpanded by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(150)
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 单行微条：整行可点击展开/收起（去卡片化：无背景无边框）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = BrainIcon,
                contentDescription = null,
                tint = if (isReasoningActive) colors.accentPrimary.copy(alpha = pulseAlpha) else (if (colors.isDark) Color(0xFF94A3B8) else colors.textMuted),
                modifier = Modifier.size(13.dp)
            )

            val durationText = if (durationMs > 0) {
                val sec = durationMs / 1000
                val dec = (durationMs % 1000) / 100
                " (${sec}.${dec}s)"
            } else ""

            val label = when {
                isReasoningActive -> "思考中..."
                durationMs > 0 -> "深度思考$durationText"
                else -> "思考过程"
            }

            Text(
                text = label,
                color = if (colors.isDark) Color(0xFF94A3B8) else colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            // 箭头紧跟文字，两格间距（spacedBy 已提供 6dp，此处再加 2dp 间距通过宽 Spacer 模拟"两个空格"）
            Spacer(modifier = Modifier.width(2.dp))

            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = if (colors.isDark) Color(0xFF94A3B8) else colors.textSecondary,
                modifier = Modifier
                    .size(11.dp)
                    .graphicsLayer { rotationZ = arrowRotation }
            )
        }

        // 展开后的思考旁白内容（左侧细垂直导轨线）
        AnimatedVisibility(
            visible = isExpanded && text.isNotBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(vertical = 3.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(if (colors.isDark) Color(0xFF2E3240) else Color(0xFFD0D5DD))
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
 * 结构化工具调用追踪微栏 (ToolCallsBlock)
 * 统一折叠条：不管1条还是N条都统一显示"工具调用 (N 项)"折叠条，展开后显示清单，去卡片化无框无背景。
 */
@Composable
fun ToolCallsBlock(
    toolCalls: List<ToolCallUi>,
    isStreaming: Boolean = false,
    isRunning: Boolean = false,
    hasFailedTool: Boolean = false,
    toolSummary: String = "",
    modifier: Modifier = Modifier,
) {
    if (toolCalls.isEmpty()) return

    val colors = LocalMederiColors.current
    var isExpanded by remember { mutableStateOf(false) }

    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(150)
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 统一折叠微条（去卡片化：无背景无边框），整行可点击
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    color = Color(0xFFF59E0B),
                    strokeWidth = 1.4.dp,
                    modifier = Modifier.size(12.dp)
                )
            } else {
                Icon(
                    imageVector = FeatherIcons.Zap,
                    contentDescription = null,
                    tint = if (hasFailedTool) colors.accentDanger else Color(0xFFF59E0B),
                    modifier = Modifier.size(12.dp)
                )
            }

            val titleText = when {
                isRunning -> {
                    val active = toolCalls.lastOrNull { it.state is ToolCallState.Running }?.name
                    if (active != null) "工具调用 · 正在执行 $active..." else "工具调用 · 正在执行..."
                }
                hasFailedTool -> "工具调用 (${toolCalls.size} 项，存在失败)"
                else -> "工具调用 (${toolCalls.size} 项)"
            }

            Text(
                text = titleText,
                color = if (hasFailedTool) colors.accentDanger else (if (colors.isDark) Color(0xFF94A3B8) else colors.textSecondary),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            // 箭头紧跟文字，两格间距
            Spacer(modifier = Modifier.width(2.dp))

            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = if (colors.isDark) Color(0xFF94A3B8) else colors.textSecondary,
                modifier = Modifier
                    .size(11.dp)
                    .graphicsLayer { rotationZ = arrowRotation }
            )
        }

        // 展开后的具体工具调用清单
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                toolCalls.forEach { toolCall ->
                    ToolCallItemRow(
                        toolCall = toolCall,
                        colors = colors,
                        isStreaming = isStreaming,
                        showLeadingIcon = false
                    )
                }
            }
        }
    }
}

/**
 * 结构化子 Agent 调用追踪微栏 (SubagentCallsBlock)
 * 独立折叠条：与普通工具调用解耦，显示派发的子 Agent 状态与任务，展开可查看执行详情。
 */
@Composable
fun SubagentCallsBlock(
    subagents: List<ToolCallUi>,
    isStreaming: Boolean = false,
    isRunning: Boolean = false,
    hasFailed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (subagents.isEmpty()) return

    val colors = LocalMederiColors.current
    var isExpanded by remember { mutableStateOf(false) }

    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(150)
    )

    val subagentAccentColor = if (colors.isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 统一折叠微条（去卡片化：无背景无边框），整行可点击
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    color = subagentAccentColor,
                    strokeWidth = 1.4.dp,
                    modifier = Modifier.size(12.dp)
                )
            } else {
                Icon(
                    imageVector = FeatherIcons.Users,
                    contentDescription = null,
                    tint = if (hasFailed) colors.accentDanger else subagentAccentColor,
                    modifier = Modifier.size(12.dp)
                )
            }

            val titleText = when {
                isRunning -> {
                    val active = subagents.lastOrNull { it.state is ToolCallState.Running }
                    val taskName = active?.target?.take(30)
                    if (taskName != null) "子 Agent · 正在执行: $taskName..." else "子 Agent · 正在执行..."
                }
                hasFailed -> "子 Agent (${subagents.size} 项，存在失败)"
                else -> "子 Agent (${subagents.size} 项)"
            }

            Text(
                text = titleText,
                color = if (hasFailed) colors.accentDanger else (if (colors.isDark) Color(0xFF94A3B8) else colors.textSecondary),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            // 箭头紧跟文字，两格间距
            Spacer(modifier = Modifier.width(2.dp))

            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = if (colors.isDark) Color(0xFF94A3B8) else colors.textSecondary,
                modifier = Modifier
                    .size(11.dp)
                    .graphicsLayer { rotationZ = arrowRotation }
            )
        }

        // 展开后的具体子 Agent 任务清单
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                subagents.forEach { subagent ->
                    ToolCallItemRow(
                        toolCall = subagent,
                        colors = colors,
                        isStreaming = isStreaming,
                        showLeadingIcon = false
                    )
                }
            }
        }
    }
}

/**
 * 将工具名称解析为简洁动词
 */
private fun resolveActionVerb(toolName: String): String = when {
    toolName == "spawn_agent" -> "agent"
    toolName == "spawn_researcher" -> "research"
    toolName.contains("edit") || toolName.contains("patch") || toolName.contains("replace") -> "edit"
    toolName.contains("write") || toolName.contains("create") -> "create"
    toolName.contains("read") || toolName.contains("view") -> "read"
    toolName.contains("run") || toolName.contains("bash") || toolName.contains("exec") || toolName.contains("terminal") -> "run"
    toolName.contains("list") || toolName.contains("dir") || toolName.contains("tree") -> "list"
    toolName.contains("search") || toolName.contains("grep") || toolName.contains("find") -> "search"
    toolName.contains("ask") -> "ask"
    else -> toolName
}

/**
 * 紧凑型子操作行：[✓] 动词 目标参数 + 可展开的紧凑结果微框
 */
@Composable
private fun ToolCallItemRow(
    toolCall: ToolCallUi,
    colors: MederiColors,
    isStreaming: Boolean = false,
    showLeadingIcon: Boolean = false
) {
    val isFailed = toolCall.isFailed
    val isRunning = isStreaming && toolCall.state is ToolCallState.Running
    val result = when (val s = toolCall.state) {
        is ToolCallState.Completed -> s.output
        is ToolCallState.Failed -> s.error
        else -> null
    }?.takeIf { it.isNotBlank() }
    var resultExpanded by remember { mutableStateOf(false) }

    val verb = resolveActionVerb(toolCall.name)
    val rawTarget = toolCall.target?.trim()
    val targetText = when {
        !rawTarget.isNullOrBlank() && rawTarget != "." && rawTarget != toolCall.name -> rawTarget
        verb == "list" -> "directory"
        else -> toolCall.name.removePrefix(verb).removePrefix("_").ifBlank { "action" }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = result != null) { resultExpanded = !resultExpanded }
                .padding(vertical = 3.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (showLeadingIcon) {
                if (isRunning) {
                    CircularProgressIndicator(
                        color = Color(0xFFF59E0B),
                        strokeWidth = 1.4.dp,
                        modifier = Modifier.size(12.dp)
                    )
                } else {
                    Icon(
                        imageVector = FeatherIcons.Zap,
                        contentDescription = null,
                        tint = if (isFailed) colors.accentDanger else Color(0xFFF59E0B),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            // [✓] 或 [✕] 状态微标签
            when {
                isRunning && !showLeadingIcon -> {
                    CircularProgressIndicator(
                        color = Color(0xFFF59E0B),
                        strokeWidth = 1.4.dp,
                        modifier = Modifier.size(11.dp)
                    )
                }
                isFailed -> {
                    Text(
                        text = "[✕]",
                        color = colors.accentDanger,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                else -> {
                    Text(
                        text = "[✓]",
                        color = if (colors.isDark) Color(0xFF10B981) else colors.accentSuccess,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 动词（如 edit, run, list, ask, read）
            Text(
                text = verb,
                color = if (colors.isDark) Color(0xFF94A3B8) else colors.textSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )

            // 目标参数（如 SnapshotReducer.kt, ./gradlew test, directory）
            Text(
                text = targetText,
                color = if (colors.isDark) Color(0xFFCBD5E1) else colors.textPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 若有执行结果，箭头紧跟文字（2dp 间距），动画旋转
            if (result != null) {
                Spacer(modifier = Modifier.width(2.dp))
                val resultArrowRotation by animateFloatAsState(
                    targetValue = if (resultExpanded) 90f else 0f,
                    animationSpec = tween(150)
                )
                Icon(
                    imageVector = FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = if (colors.isDark) Color(0xFF94A3B8) else colors.textSecondary,
                    modifier = Modifier
                        .size(10.dp)
                        .graphicsLayer { rotationZ = resultArrowRotation }
                )
            }
        }

        // 执行输出微框（严格限高 130dp，轻量暗色背景）
        if (result != null && resultExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (colors.isDark) Color(0xFF161822) else Color(0xFFF1F3F5))
                    .border(1.dp, if (colors.isDark) Color(0xFF262936) else Color(0xFFE2E8F0), RoundedCornerShape(4.dp))
                .clickable { resultExpanded = false }
                .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = result,
                    color = if (isFailed) colors.accentDanger else (if (colors.isDark) Color(0xFF94A3B8) else colors.textSecondary),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 130.dp)
                        .verticalScroll(rememberScrollState())
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
 * 3. 选择题/问询交互卡片 (QuestionCard)
 *
 * 无状态组件：当前选中答案经 [selectedAnswers] 由调用方传入（唯一真理源 =
 * WorkspaceViewModel.questionAnswers），点击经 [onAnswer] 单向写回，卡片内不持有副本。
 * 支持三种模式：
 * 1. options 为空 -> 自由文本输入框（OutlinedTextField）
 * 2. options 非空且 multiSelect = false -> 单选列表（RadioButton）
 * 3. options 非空且 multiSelect = true -> 多选列表（Checkbox）
 * 额外支持 allowCustom = true 时的自定义输入框。
 */
@Composable
fun QuestionCard(
    question: QuestionRequest?,
    currentIndex: Int,
    selectedAnswers: List<String>,
    onAnswer: (List<String>) -> Unit,
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
            val typeTag = when {
                qInfo.options.isEmpty() -> " · 自由输入"
                qInfo.multiSelect -> " · 多选"
                else -> " · 单选"
            }
            Text(
                text = "交互提问 [${currentIndex + 1}/${qList.size}]$typeTag",
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

        // Options or Free-text input
        if (qInfo.options.isEmpty()) {
            val freeText = selectedAnswers.firstOrNull() ?: ""
            OutlinedTextField(
                value = freeText,
                onValueChange = { onAnswer(if (it.isBlank()) emptyList() else listOf(it)) },
                placeholder = { Text("请输入您的回答...", fontSize = 11.5.sp, color = colors.textMuted) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 11.5.sp, color = colors.textPrimary),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceWorkspace,
                    unfocusedContainerColor = colors.surfaceWorkspace,
                    focusedBorderColor = colors.accentPrimary,
                    unfocusedBorderColor = colors.divider,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    cursorColor = colors.accentPrimary
                )
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                qInfo.options.forEach { opt ->
                    val isSelected = selectedAnswers.contains(opt)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.15f) else colors.surfaceWorkspace)
                            .border(1.dp, if (isSelected) colors.accentPrimary else colors.divider, RoundedCornerShape(6.dp))
                            .clickable {
                                if (qInfo.multiSelect) {
                                    onAnswer(if (isSelected) selectedAnswers - opt else selectedAnswers + opt)
                                } else {
                                    val nonOptions = if (qInfo.allowCustom) selectedAnswers.filter { it !in qInfo.options } else emptyList()
                                    onAnswer(listOf(opt) + nonOptions)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (qInfo.multiSelect) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = colors.accentPrimary,
                                    checkmarkColor = colors.onAccentPrimary,
                                    uncheckedColor = colors.textMuted
                                )
                            )
                        } else {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = colors.accentPrimary)
                            )
                        }
                        Text(text = opt, color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }

                if (qInfo.allowCustom) {
                    val customValue = selectedAnswers.firstOrNull { it !in qInfo.options } ?: ""
                    OutlinedTextField(
                        value = customValue,
                        onValueChange = { newCustom ->
                            val currentOptions = selectedAnswers.filter { it in qInfo.options }
                            val next = if (newCustom.isBlank()) currentOptions else currentOptions + newCustom
                            onAnswer(next)
                        },
                        placeholder = { Text("其他自定义输入...", fontSize = 11.sp, color = colors.textMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 11.sp, color = colors.textPrimary),
                        shape = RoundedCornerShape(6.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.surfaceWorkspace,
                            unfocusedContainerColor = colors.surfaceWorkspace,
                            focusedBorderColor = colors.accentPrimary,
                            unfocusedBorderColor = colors.divider,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            cursorColor = colors.accentPrimary
                        )
                    )
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
