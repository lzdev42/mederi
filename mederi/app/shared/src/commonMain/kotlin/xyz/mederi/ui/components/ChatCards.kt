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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.action_rollback
import mederi.app.shared.generated.resources.attachment_chars_unit
import mederi.app.shared.generated.resources.attachment_lines
import mederi.app.shared.generated.resources.attachment_meta
import mederi.app.shared.generated.resources.attachment_reader
import mederi.app.shared.generated.resources.attachment_writing
import mederi.app.shared.generated.resources.chat_copy_full_turn
import mederi.app.shared.generated.resources.chat_copy_last_message
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.copy_done
import mederi.app.shared.generated.resources.footer_completed
import mederi.app.shared.generated.resources.footer_reasoning
import mederi.app.shared.generated.resources.input_pasted_text_n
import mederi.app.shared.generated.resources.mode_auto_approve
import mederi.app.shared.generated.resources.mode_manual_approve
import mederi.app.shared.generated.resources.plan_approval_approved
import mederi.app.shared.generated.resources.plan_approval_proceed
import mederi.app.shared.generated.resources.plan_approval_title
import mederi.app.shared.generated.resources.plan_approval_title_default
import mederi.app.shared.generated.resources.question_cancel
import mederi.app.shared.generated.resources.question_custom_placeholder
import mederi.app.shared.generated.resources.question_free_input_tag
import mederi.app.shared.generated.resources.question_input_placeholder
import mederi.app.shared.generated.resources.question_multi_tag
import mederi.app.shared.generated.resources.question_next
import mederi.app.shared.generated.resources.question_prev
import mederi.app.shared.generated.resources.question_single_tag
import mederi.app.shared.generated.resources.question_submit
import mederi.app.shared.generated.resources.question_title
import mederi.app.shared.generated.resources.reasoning_thinking
import mederi.app.shared.generated.resources.reasoning_thinking_for
import mederi.app.shared.generated.resources.reasoning_thought
import mederi.app.shared.generated.resources.reasoning_thought_for
import mederi.app.shared.generated.resources.tool_detail_failed_output
import mederi.app.shared.generated.resources.tool_detail_output
import mederi.app.shared.generated.resources.tool_detail_report
import mederi.app.shared.generated.resources.tool_action_ask_many
import mederi.app.shared.generated.resources.tool_action_ask_one
import mederi.app.shared.generated.resources.tool_action_ask_running
import mederi.app.shared.generated.resources.tool_action_edit_many
import mederi.app.shared.generated.resources.tool_action_edit_one
import mederi.app.shared.generated.resources.tool_action_edit_running
import mederi.app.shared.generated.resources.tool_action_edit_running_target
import mederi.app.shared.generated.resources.tool_action_edit_target
import mederi.app.shared.generated.resources.tool_action_failed_suffix
import mederi.app.shared.generated.resources.tool_action_list_many
import mederi.app.shared.generated.resources.tool_action_list_one
import mederi.app.shared.generated.resources.tool_action_list_running
import mederi.app.shared.generated.resources.tool_action_list_running_target
import mederi.app.shared.generated.resources.tool_action_list_target
import mederi.app.shared.generated.resources.tool_action_mcp_many
import mederi.app.shared.generated.resources.tool_action_mcp_running_target
import mederi.app.shared.generated.resources.tool_action_mcp_target
import mederi.app.shared.generated.resources.tool_action_other_many
import mederi.app.shared.generated.resources.tool_action_other_running
import mederi.app.shared.generated.resources.tool_action_other_running_target
import mederi.app.shared.generated.resources.tool_action_other_target
import mederi.app.shared.generated.resources.tool_action_read_many
import mederi.app.shared.generated.resources.tool_action_read_one
import mederi.app.shared.generated.resources.tool_action_read_running
import mederi.app.shared.generated.resources.tool_action_read_running_target
import mederi.app.shared.generated.resources.tool_action_read_target
import mederi.app.shared.generated.resources.tool_action_run_many
import mederi.app.shared.generated.resources.tool_action_run_one
import mederi.app.shared.generated.resources.tool_action_run_running
import mederi.app.shared.generated.resources.tool_action_run_running_target
import mederi.app.shared.generated.resources.tool_action_run_target
import mederi.app.shared.generated.resources.tool_action_search_many
import mederi.app.shared.generated.resources.tool_action_search_one
import mederi.app.shared.generated.resources.tool_action_search_running
import mederi.app.shared.generated.resources.tool_action_search_running_target
import mederi.app.shared.generated.resources.tool_action_search_target
import mederi.app.shared.generated.resources.tool_action_subagent_many
import mederi.app.shared.generated.resources.tool_action_subagent_one
import mederi.app.shared.generated.resources.tool_action_subagent_running
import mederi.app.shared.generated.resources.tool_action_subagent_running_target
import mederi.app.shared.generated.resources.tool_action_subagent_target
import mederi.app.shared.generated.resources.worktrace_collapse
import mederi.app.shared.generated.resources.worktrace_duration
import mederi.app.shared.generated.resources.worktrace_expand
import mederi.app.shared.generated.resources.worktrace_has_failure
import mederi.app.shared.generated.resources.worktrace_steps_count
import mederi.app.shared.generated.resources.worktrace_summary_title
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.MarkdownView
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.core.contract.models.QuestionRequest
import xyz.mederi.core.ui.AssistantFooterInfo
import xyz.mederi.core.ui.DebugLog
import xyz.mederi.core.ui.ChatListItem
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi
import xyz.mederi.core.ui.SubagentReportMarkdown
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.rememberMederiMarkdownTheme

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
 * 极简终端命令提示符矢量图标（>_）
 */
val TerminalPromptIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "TerminalPrompt",
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f
    ).apply {
        // > 提示符折线
        val nodesChevron = PathParser().parsePathString("M 2.5 4 L 6.5 7.5 L 2.5 11").toNodes()
        addPath(
            pathData = nodesChevron,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
        // _ 下划线
        val nodesUnderscore = PathParser().parsePathString("M 8 11.5 L 13 11.5").toNodes()
        addPath(
            pathData = nodesUnderscore,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round
        )
    }.build()
}

/**
 * 工作过程（WorkTraceCard）展开内容的最大高度：超过后栏内上下滚动，
 * 避免推理/工具步骤把整个聊天内容顶得过长（折叠条仍可点击收起/展开）。
 */
private val WorkTraceMaxContentHeight = 320.dp

/**
 * 顶层推理块（ReasoningBlock）展开内容的默认最大高度：超过后块内上下滚动，
 * 内容末尾的「展开/收起」按钮可切换为无限高度（真实动态高度），再点缩回限高。
 */
private val ReasoningMaxContentHeight = 200.dp

/**
 * 判定 ReasoningBlock 是否应启用内部限高与垂直滚动容器：
 * - 只有外层要求限高 (enforceMaxHeight=true) 且未切换为无界全部展开 (!isUnbounded) 时才启用滚动与限高。
 * - 当 isUnbounded=true（用户点击底部展开按钮）或 enforceMaxHeight=false 时，必须禁用 verticalScroll，
 *   避免在 LazyColumn (垂直无界 Constraints.Infinity) 内挂载 verticalScroll 触发 Compose 崩溃。
 */
fun shouldEnableReasoningScroll(enforceMaxHeight: Boolean, isUnbounded: Boolean): Boolean {
    return enforceMaxHeight && !isUnbounded
}

/**
 * 单独思维链/思考过程折叠面板 (ReasoningBlock)
 * 严格还原极简设计：大脑图标胶囊 + 展开后轻量导轨线。
 *
 * 顶层独立显示（enforceMaxHeight=true）时：展开内容默认限高 [ReasoningMaxContentHeight] 并内部滚动，
 * 内容末尾「展开/收起」按钮可切换为无限高度（真实动态高度）；限高状态下不点按钮也能滚动查看全部。
 * 位于 WorkTraceCard 内（enforceMaxHeight=false）时保持无限高——卡整体已限高滚动，子项不再重复限制。
 */
@Composable
fun ReasoningBlock(
    text: String,
    durationMs: Long = 0,
    isStreaming: Boolean = false,
    isReasoningActive: Boolean = false,
    modifier: Modifier = Modifier,
    enforceMaxHeight: Boolean = true,
    contentKey: String? = null,
) {
    if (text.isBlank() && !isReasoningActive) return

    val colors = LocalMederiColors.current
    var localExpanded by remember { mutableStateOf(false) }
    val isExpanded = localExpanded
    // 内容是否无限高：默认限高 + 块内滚动，点内容末尾按钮切换为全部摊开，再点缩回限高
    var isUnbounded by remember(contentKey) { mutableStateOf(false) }
    val shouldScroll = shouldEnableReasoningScroll(enforceMaxHeight, isUnbounded)
    DebugLog.debug(
        "UI",
        "ReasoningBlock compose: contentKey=$contentKey, enforceMaxHeight=$enforceMaxHeight, isUnbounded=$isUnbounded, shouldScroll=$shouldScroll"
    )

    // 限高滚动容器 + 自动贴底（stick-to-bottom，与聊天列表同一套语义）：
    // 默认自动滚动到底部（流式时跟随最新推理），用户手动滚开即停止；滚回底部恢复跟随。
    val contentScroll = rememberScrollState()
    var stickToBottom by remember(contentKey) { mutableStateOf(true) }
    // 首次自动滚底完成前禁用位置跟踪：否则初始布局在顶部时跟踪 effect 会立刻把 stickToBottom
    // 打成 false，与"打开即贴底"互相打架（与聊天列表 bottomTrackingEnabled 同源防抖）。
    var bottomTrackingEnabled by remember(contentKey) { mutableStateOf(false) }

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
                .clickable { localExpanded = !isExpanded }
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
                if (sec > 0) "${sec}s" else "${durationMs}ms"
            } else ""

            val label = when {
                isReasoningActive && durationText.isNotBlank() -> stringResource(Res.string.reasoning_thinking_for, durationText)
                isReasoningActive -> stringResource(Res.string.reasoning_thinking)
                durationText.isNotBlank() -> stringResource(Res.string.reasoning_thought_for, durationText)
                else -> stringResource(Res.string.reasoning_thought)
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

        // 位置跟踪：滚动落定后按"是否仍在底部"更新贴底意图。
        // 用户滚动离开底部 → !canScrollForward=false → stickToBottom=false（停止跟随）；
        // 用户滚回底部 → canScrollForward=false → stickToBottom=true（恢复跟随）。
        LaunchedEffect(contentKey, shouldScroll) {
            if (!shouldScroll) return@LaunchedEffect
            snapshotFlow { contentScroll.value }
                .collect { _ ->
                    if (!bottomTrackingEnabled) return@collect
                    if (contentScroll.isScrollInProgress) return@collect
                    stickToBottom = !contentScroll.canScrollForward
                }
        }

        // 跟随滚动（tail -f）：贴底期间内容增长（流式推理持续变高 / maxValue 增大）自动吸附到底部。
        // 首次滚底落地后启用位置跟踪。scrollTo 是同步瞬时操作，落定后的下一帧 maxValue 无变化即停。
        LaunchedEffect(contentKey, shouldScroll) {
            if (!shouldScroll) return@LaunchedEffect
            snapshotFlow { contentScroll.maxValue }
                .collect { max ->
                    if (stickToBottom && !contentScroll.isScrollInProgress && contentScroll.canScrollForward) {
                        contentScroll.scrollTo(max)
                        stickToBottom = true
                        bottomTrackingEnabled = true
                    }
                }
        }

        // 展开后的思考旁白内容（左侧细垂直导轨线）。
        // enforceMaxHeight：默认限高 + 块内滚动，内容末尾「展开/收起」按钮切换无限高度（真实动态高度），
        // 限高状态下不点按钮也能上下滚动查看全部；WorkTraceCard 内子项（enforceMaxHeight=false）保持无限高。
        AnimatedVisibility(
            visible = isExpanded && text.isNotBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val railColor = if (colors.isDark) Color(0xFF2E3240) else Color(0xFFD0D5DD)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (shouldScroll) {
                            Modifier
                                .heightIn(max = ReasoningMaxContentHeight)
                                .verticalScroll(contentScroll)
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = 3.dp, horizontal = 4.dp)
                    .drawBehind {
                        val strokeWidth = 2.dp.toPx()
                        drawLine(
                            color = railColor,
                            start = Offset(strokeWidth / 2f, 0f),
                            end = Offset(strokeWidth / 2f, size.height),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MarkdownView(
                    content = text,
                    modifier = Modifier.fillMaxWidth(),
                    enableScrollOverride = false,
                    markdownTheme = rememberMederiMarkdownTheme(compact = true)
                )

                if (enforceMaxHeight) {
                    // 底部切换按钮：位于内容末尾、随内容滚动，恒显示
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                isUnbounded = !isUnbounded
                                DebugLog.debug(
                                    "UI",
                                    "ReasoningBlock isUnbounded toggled to: $isUnbounded, contentKey=$contentKey"
                                )
                            }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isUnbounded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                            contentDescription = null,
                            tint = if (colors.isDark) Color(0xFF94A3B8) else colors.textMuted,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                if (isUnbounded) Res.string.worktrace_collapse else Res.string.worktrace_expand
                            ),
                            color = if (colors.isDark) Color(0xFF94A3B8) else colors.textMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * 工具动作大类枚举（用于时序行图标与聚合展示）
 */
enum class ToolActionKind {
    COMMAND, READ, EDIT, SEARCH, LIST, SUBAGENT, MCP, ASK, OTHER
}

/**
 * 依据工具名分类动作大类
 */
fun classifyToolAction(name: String): ToolActionKind {
    val lower = name.lowercase()
    return when {
        lower.contains("command") || lower == "bash" || lower.contains("exec") || lower.contains("terminal") -> ToolActionKind.COMMAND
        lower.contains("read") || lower.contains("view") || lower == "cat" -> ToolActionKind.READ
        lower.contains("edit") || lower.contains("patch") || lower.contains("replace") || lower.contains("write") || lower.contains("create") -> ToolActionKind.EDIT
        lower.contains("search") || lower.contains("grep") || lower.contains("find") -> ToolActionKind.SEARCH
        lower.contains("list") || lower.contains("dir") || lower.contains("tree") -> ToolActionKind.LIST
        lower == "subagent" || lower.contains("agent") -> ToolActionKind.SUBAGENT
        lower.contains("mcp") -> ToolActionKind.MCP
        lower.contains("ask") -> ToolActionKind.ASK
        else -> ToolActionKind.OTHER
    }
}

/**
 * 连续同类工具动作聚合组
 */
data class ToolActionGroup(
    val kind: ToolActionKind,
    val calls: List<ToolCallUi>,
)

/**
 * 将同批连续的同类工具调用聚合为动作组
 */
fun groupToolCallsByAction(calls: List<ToolCallUi>): List<ToolActionGroup> {
    if (calls.isEmpty()) return emptyList()
    val groups = mutableListOf<ToolActionGroup>()
    var currentKind = classifyToolAction(calls.first().name)
    var currentList = mutableListOf(calls.first())
    for (i in 1 until calls.size) {
        val call = calls[i]
        val kind = classifyToolAction(call.name)
        if (kind == currentKind) {
            currentList.add(call)
        } else {
            groups.add(ToolActionGroup(currentKind, currentList))
            currentKind = kind
            currentList = mutableListOf(call)
        }
    }
    if (currentList.isNotEmpty()) {
        groups.add(ToolActionGroup(currentKind, currentList))
    }
    return groups
}

/**
 * 工具调用时间线 (ToolCallsBlock)：按动作类别连续聚合为极简动作行。
 *
 * - 单条：`>_ Ran command <command>` / `Read file <file>`
 * - 多条：`>_ Ran 2 commands` / `Read 2 files`
 * - 展开：展示 `$ <command>` 命令行，`OUTPUT` 标题，及黑色终端风格执行输出框。
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

    val groups = remember(toolCalls) { groupToolCallsByAction(toolCalls) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        groups.forEach { group ->
            key(group.calls.firstOrNull()?.id ?: group.kind.name) {
                ToolActionGroupRow(
                    group = group,
                    isStreaming = isStreaming,
                    isRunning = isRunning,
                )
            }
        }
    }
}

/**
 * 单个动作组的时间线行：折叠态单行图标+摘要，展开态展示命令原文及终端输出代码框。
 */
@Composable
fun ToolActionGroupRow(
    group: ToolActionGroup,
    isStreaming: Boolean,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val isFailed = group.calls.any { it.isFailed }
    val isGroupRunning = (isRunning || isStreaming) && group.calls.any { it.state is ToolCallState.Running }

    // 所有工具动作行严格默认不展开（即使执行失败也保持折叠，需要点击才展开）；用户点击后以用户状态为准
    var userChoice by remember(group.calls.map { it.id }) { mutableStateOf(false) }
    val isExpanded = userChoice

    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(150)
    )

    val count = group.calls.size
    val firstCall = group.calls.first()
    val singleTarget = firstCall.target?.trim()?.takeIf { it.isNotBlank() }

    val defaultMutedColor = if (colors.isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    // 图标与颜色
    val (iconVector, iconTint) = when {
        isFailed -> Pair(FeatherIcons.AlertCircle, colors.accentDanger)
        group.kind == ToolActionKind.COMMAND -> Pair(TerminalPromptIcon, defaultMutedColor)
        group.kind == ToolActionKind.READ -> Pair(FeatherIcons.FileText, defaultMutedColor)
        group.kind == ToolActionKind.EDIT -> Pair(FeatherIcons.Edit2, defaultMutedColor)
        group.kind == ToolActionKind.SEARCH -> Pair(FeatherIcons.Search, defaultMutedColor)
        group.kind == ToolActionKind.LIST -> Pair(FeatherIcons.Folder, defaultMutedColor)
        group.kind == ToolActionKind.SUBAGENT -> Pair(FeatherIcons.Users, if (colors.isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED))
        group.kind == ToolActionKind.MCP -> Pair(FeatherIcons.Cpu, defaultMutedColor)
        group.kind == ToolActionKind.ASK -> Pair(FeatherIcons.HelpCircle, defaultMutedColor)
        else -> Pair(FeatherIcons.Zap, defaultMutedColor)
    }

    // 标题文本（全部走资源化，三类：执行中 / 单条 / 多条；带目标时用 _target/_running_target 格式化）
    val titleText = run {
        val base = when {
            isGroupRunning -> when (group.kind) {
                ToolActionKind.COMMAND -> singleTarget?.let { stringResource(Res.string.tool_action_run_running_target, it) }
                    ?: stringResource(Res.string.tool_action_run_running)
                ToolActionKind.READ -> singleTarget?.let { stringResource(Res.string.tool_action_read_running_target, it) }
                    ?: stringResource(Res.string.tool_action_read_running)
                ToolActionKind.EDIT -> singleTarget?.let { stringResource(Res.string.tool_action_edit_running_target, it) }
                    ?: stringResource(Res.string.tool_action_edit_running)
                ToolActionKind.SEARCH -> singleTarget?.let { stringResource(Res.string.tool_action_search_running_target, it) }
                    ?: stringResource(Res.string.tool_action_search_running)
                ToolActionKind.LIST -> singleTarget?.let { stringResource(Res.string.tool_action_list_running_target, it) }
                    ?: stringResource(Res.string.tool_action_list_running)
                ToolActionKind.SUBAGENT -> singleTarget?.let { stringResource(Res.string.tool_action_subagent_running_target, it) }
                    ?: stringResource(Res.string.tool_action_subagent_running)
                ToolActionKind.MCP -> stringResource(Res.string.tool_action_mcp_running_target, singleTarget ?: firstCall.name)
                ToolActionKind.ASK -> stringResource(Res.string.tool_action_ask_running)
                else -> singleTarget?.let { stringResource(Res.string.tool_action_other_running_target, it) }
                    ?: stringResource(Res.string.tool_action_other_running)
            }
            count == 1 -> when (group.kind) {
                ToolActionKind.COMMAND -> singleTarget?.let { stringResource(Res.string.tool_action_run_target, it) }
                    ?: stringResource(Res.string.tool_action_run_one)
                ToolActionKind.READ -> singleTarget?.let { stringResource(Res.string.tool_action_read_target, it) }
                    ?: stringResource(Res.string.tool_action_read_one)
                ToolActionKind.EDIT -> singleTarget?.let { stringResource(Res.string.tool_action_edit_target, it) }
                    ?: stringResource(Res.string.tool_action_edit_one)
                ToolActionKind.SEARCH -> singleTarget?.let { stringResource(Res.string.tool_action_search_target, it) }
                    ?: stringResource(Res.string.tool_action_search_one)
                ToolActionKind.LIST -> singleTarget?.let { stringResource(Res.string.tool_action_list_target, it) }
                    ?: stringResource(Res.string.tool_action_list_one)
                ToolActionKind.SUBAGENT -> singleTarget?.let { stringResource(Res.string.tool_action_subagent_target, it) }
                    ?: stringResource(Res.string.tool_action_subagent_one)
                ToolActionKind.MCP -> stringResource(Res.string.tool_action_mcp_target, singleTarget ?: firstCall.name)
                ToolActionKind.ASK -> stringResource(Res.string.tool_action_ask_one)
                else -> stringResource(Res.string.tool_action_other_target, singleTarget ?: firstCall.name)
            }
            else -> when (group.kind) {
                ToolActionKind.COMMAND -> stringResource(Res.string.tool_action_run_many, count)
                ToolActionKind.READ -> stringResource(Res.string.tool_action_read_many, count)
                ToolActionKind.EDIT -> stringResource(Res.string.tool_action_edit_many, count)
                ToolActionKind.SEARCH -> stringResource(Res.string.tool_action_search_many, count)
                ToolActionKind.LIST -> stringResource(Res.string.tool_action_list_many, count)
                ToolActionKind.SUBAGENT -> stringResource(Res.string.tool_action_subagent_many, count)
                ToolActionKind.MCP -> stringResource(Res.string.tool_action_mcp_many, count)
                ToolActionKind.ASK -> stringResource(Res.string.tool_action_ask_many, count)
                else -> stringResource(Res.string.tool_action_other_many, count)
            }
        }
        if (isFailed) "$base ${stringResource(Res.string.tool_action_failed_suffix)}" else base
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // 折叠微条（无卡片背景与硬边框，整行可点击）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable {
                    userChoice = !isExpanded
                    DebugLog.event("UI", "ToolActionGroupRow clicked: kind=${group.kind}, count=${group.calls.size}, isExpanded=$userChoice")
                }
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isGroupRunning) {
                CircularProgressIndicator(
                    color = Color(0xFFF59E0B),
                    strokeWidth = 1.4.dp,
                    modifier = Modifier.size(12.dp)
                )
            } else {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(13.dp)
                )
            }

            Text(
                text = titleText,
                color = if (isFailed) colors.accentDanger else defaultMutedColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(2.dp))

            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = defaultMutedColor,
                modifier = Modifier
                    .size(11.dp)
                    .graphicsLayer { rotationZ = arrowRotation }
            )
        }

        // 展开后的具体执行明细
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 2.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                group.calls.forEach { call ->
                    val isCallFailed = call.isFailed
                    val completedState = call.state as? ToolCallState.Completed
                    val isReportTool = call.name in SubagentReportMarkdown.REPORT_TOOL_NAMES
                    val reportMarkdown = if (isReportTool && completedState != null) {
                        SubagentReportMarkdown.fromToolResult(call.name, completedState.output)
                    } else null
                    val output = when (val s = call.state) {
                        is ToolCallState.Completed -> s.output
                        is ToolCallState.Failed -> s.error
                        else -> null
                    }?.trim()

                    if (reportMarkdown != null) {
                        // 子代理任务报告 Markdown（默认不展开，点击 REPORT 展开）
                        ToolCallReportBlock(
                            callId = call.id,
                            reportMarkdown = reportMarkdown,
                            colors = colors,
                        )
                    } else if (group.kind == ToolActionKind.COMMAND) {
                        // 命令行展开：命令行展示 + 输出点击展开（默认不展开）
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (!call.target.isNullOrBlank()) {
                                Text(
                                    text = "$ ${call.target}",
                                    color = if (colors.isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                                )
                            }
                            if (!output.isNullOrBlank()) {
                                ToolCallOutputBlock(
                                    callId = call.id,
                                    output = output,
                                    isFailed = isCallFailed,
                                    colors = colors,
                                    maxHeight = 280.dp,
                                )
                            }
                        }
                    } else {
                        // 通用工具调用（读取/编辑/检索等）：目标显示 + 输出点击展开（默认不展开）
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (!call.target.isNullOrBlank()) {
                                Text(
                                    text = call.target,
                                    color = if (colors.isDark) Color(0xFFCBD5E1) else colors.textPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                                )
                            }
                            if (!output.isNullOrBlank()) {
                                ToolCallOutputBlock(
                                    callId = call.id,
                                    output = output,
                                    isFailed = isCallFailed,
                                    colors = colors,
                                    maxHeight = 240.dp,
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
 * 工具调用输出块：严格按要求默认不展开，用户点击 "OUTPUT" 或 "FAILED" 才展开，
 * 内部带有终端/代码输出框，支持滚动与长输出保护。
 */
@Composable
private fun ToolCallOutputBlock(
    callId: String,
    output: String,
    isFailed: Boolean,
    colors: xyz.mederi.theme.MederiColors,
    maxHeight: Dp = 280.dp,
) {
    var isOutputExpanded by remember(callId) { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (isOutputExpanded) 90f else 0f,
        animationSpec = tween(150)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable {
                    isOutputExpanded = !isOutputExpanded
                    DebugLog.event("UI", "ToolCallOutputBlock clicked: callId=$callId, isExpanded=$isOutputExpanded, isFailed=$isFailed")
                }
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(if (isFailed) Res.string.tool_detail_failed_output else Res.string.tool_detail_output),
                color = if (isFailed) colors.accentDanger else (if (colors.isDark) Color(0xFF64748B) else Color(0xFF94A3B8)),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = if (isFailed) colors.accentDanger else (if (colors.isDark) Color(0xFF64748B) else Color(0xFF94A3B8)),
                modifier = Modifier
                    .size(10.dp)
                    .graphicsLayer { rotationZ = arrowRotation }
            )
        }

        AnimatedVisibility(
            visible = isOutputExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (colors.isDark) Color(0xFF0D1117) else Color(0xFF161B22))
                    .border(
                        1.dp,
                        if (isFailed) colors.accentDanger.copy(alpha = 0.4f) else Color(0xFF21262D),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = output,
                        color = if (isFailed) Color(0xFFFFA198) else Color(0xFFC9D1D9),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

/**
 * 子代理任务报告 Markdown 展开块：默认不展开，点击 "REPORT" 才展开展示富文本。
 */
@Composable
private fun ToolCallReportBlock(
    callId: String,
    reportMarkdown: String,
    colors: xyz.mederi.theme.MederiColors,
) {
    var isReportExpanded by remember(callId) { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (isReportExpanded) 90f else 0f,
        animationSpec = tween(150)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable {
                    isReportExpanded = !isReportExpanded
                    DebugLog.event("UI", "ToolCallReportBlock clicked: callId=$callId, isExpanded=$isReportExpanded")
                }
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(Res.string.tool_detail_report),
                color = if (colors.isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = if (colors.isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                modifier = Modifier
                    .size(10.dp)
                    .graphicsLayer { rotationZ = arrowRotation }
            )
        }

        AnimatedVisibility(
            visible = isReportExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (colors.isDark) Color(0xFF161822) else Color(0xFFF8FAFC))
                    .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                MarkdownView(
                    content = reportMarkdown,
                    modifier = Modifier.fillMaxWidth(),
                    enableScrollOverride = false,
                    markdownTheme = rememberMederiMarkdownTheme()
                )
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
    ToolCallsBlock(
        toolCalls = subagents,
        isStreaming = isStreaming,
        isRunning = isRunning,
        hasFailedTool = hasFailed,
        modifier = modifier,
    )
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
                qInfo.options.isEmpty() -> stringResource(Res.string.question_free_input_tag)
                qInfo.multiSelect -> stringResource(Res.string.question_multi_tag)
                else -> stringResource(Res.string.question_single_tag)
            }
            Text(
                text = stringResource(Res.string.question_title, currentIndex + 1, qList.size) + " · $typeTag",
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
                placeholder = { Text(stringResource(Res.string.question_input_placeholder), fontSize = 11.5.sp, color = colors.textMuted) },
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
                        placeholder = { Text(stringResource(Res.string.question_custom_placeholder), fontSize = 11.sp, color = colors.textMuted) },
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
                Text(stringResource(Res.string.question_cancel), color = colors.textMuted, fontSize = 11.5.sp)
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
                        Text(stringResource(Res.string.question_prev), fontSize = 11.5.sp, color = colors.textPrimary)
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
                        Text(stringResource(Res.string.question_next), fontSize = 11.5.sp, color = colors.textPrimary)
                    }
                } else {
                    Button(
                        onClick = onSubmit,
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary)
                    ) {
                        Text(stringResource(Res.string.question_submit), fontSize = 11.5.sp, color = colors.onAccentPrimary)
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
                    text = if (request.title.isNotBlank()) {
                        stringResource(Res.string.plan_approval_title, request.title)
                    } else {
                        stringResource(Res.string.plan_approval_title_default)
                    },
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
                        text = if (isApproved) stringResource(Res.string.plan_approval_approved) else stringResource(Res.string.plan_approval_proceed),
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
                    text = stringResource(Res.string.input_pasted_text_n, attachment.index),
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(Res.string.attachment_meta, attachment.lineCount, attachment.charCount),
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
                        text = stringResource(Res.string.attachment_reader),
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
                    text = stringResource(if (isExpanded) Res.string.worktrace_collapse else Res.string.worktrace_expand),
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
                contentDescription = stringResource(Res.string.action_rollback),
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
                contentDescription = stringResource(if (copied) Res.string.copy_done else Res.string.copy),
                tint = if (copied) colors.accentSuccess else colors.textMuted,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/**
 * assistant 消息底部 footer：模型名 · 审批/自主 · 推理档 · 消耗时长 · 回复结束时间，
 * 以及本轮回复的复制操作按钮（只复制最后一条回复 / 复制本轮完整内容）。
 */
@Composable
fun AssistantMessageFooter(
    footer: AssistantFooterInfo,
    lastMessageText: String = "",
    fullTurnText: String = "",
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val clipboardManager = LocalClipboardManager.current
    var copiedLast by remember { mutableStateOf(false) }
    var copiedFull by remember { mutableStateOf(false) }

    LaunchedEffect(copiedLast) {
        if (copiedLast) {
            delay(1500)
            copiedLast = false
        }
    }
    LaunchedEffect(copiedFull) {
        if (copiedFull) {
            delay(1500)
            copiedFull = false
        }
    }

    val segments = mutableListOf<String>()

    val autoApproveStr = stringResource(Res.string.mode_auto_approve)
    val manualApproveStr = stringResource(Res.string.mode_manual_approve)
    footer.modelName?.takeIf { it.isNotBlank() }?.let { segments.add(it) }
    footer.agentMode?.let {
        segments.add(
            when (it) {
                "AUTONOMOUS" -> autoApproveStr
                "APPROVAL" -> manualApproveStr
                else -> it
            }
        )
    }
    footer.thinkingLevel?.takeIf { it.isNotBlank() && it != "NONE" }?.let { segments.add(stringResource(Res.string.footer_reasoning, it)) }
    footer.durationMs?.let { ms ->
        if (ms > 0) segments.add(formatSeconds(ms))
    }
    footer.completedAtMs?.let { ms ->
        if (ms > 0) segments.add(stringResource(Res.string.footer_completed, xyz.mederi.formatMessageTime(ms)))
    }

    if (segments.isEmpty() && lastMessageText.isBlank() && fullTurnText.isBlank()) return

    Row(
        modifier = modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：模型元数据
        Row(
            modifier = Modifier.weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (segments.isNotEmpty()) {
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

        // 右侧：复制按钮
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (lastMessageText.isNotBlank()) {
                val textLast = if (copiedLast) stringResource(Res.string.copy_done) else stringResource(Res.string.chat_copy_last_message)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable {
                            clipboardManager.setText(AnnotatedString(lastMessageText))
                            copiedLast = true
                        }
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (copiedLast) FeatherIcons.Check else FeatherIcons.Copy,
                        contentDescription = textLast,
                        tint = if (copiedLast) colors.accentSuccess else colors.textMuted.copy(alpha = 0.8f),
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = textLast,
                        color = if (copiedLast) colors.accentSuccess else colors.textMuted.copy(alpha = 0.8f),
                        fontSize = 10.5.sp
                    )
                }
            }

            if (fullTurnText.isNotBlank()) {
                val textFull = if (copiedFull) stringResource(Res.string.copy_done) else stringResource(Res.string.chat_copy_full_turn)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable {
                            clipboardManager.setText(AnnotatedString(fullTurnText))
                            copiedFull = true
                        }
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (copiedFull) FeatherIcons.Check else FeatherIcons.Copy,
                        contentDescription = textFull,
                        tint = if (copiedFull) colors.accentSuccess else colors.textMuted.copy(alpha = 0.8f),
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = textFull,
                        color = if (copiedFull) colors.accentSuccess else colors.textMuted.copy(alpha = 0.8f),
                        fontSize = 10.5.sp
                    )
                }
            }
        }
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

/**
 * 独立长文 Markdown 产物卡片 (DocumentArtifactCard)
 *
 * 遵循 Mederi 设计语言：
 * - 圆角 8dp，surfaceCard 背景，细边框 surfaceCardBorder，鼠标 hover 高亮；
 * - 左侧 34dp 图标容器（accentPrimary 强调背景 + FileText 图标，生成中伴随呼吸动画）；
 * - 中间两行：主标题（13sp SemiBold） + 元信息（生成时间 / 实时行数与字符数跳动）；
 * - 底部：1.5dp 流动光效指示条（仅在 isStreaming 且未完成时以动画横向流动展现）；
 * - 右侧：紧凑型“阅读器”胶囊按钮（带有 FeatherIcons.Sidebar）；
 * - 点击整张卡片即可滑出右侧扩展窗口并流式排版渲染。
 */
@Composable
fun DocumentArtifactCard(
    title: String,
    lineCount: Int,
    charCount: Int,
    createdAt: Long,
    isStreaming: Boolean,
    isCompleted: Boolean,
    onOpenInExtension: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val isRunning = isStreaming && !isCompleted

    // 呼吸脉冲动画（生成中时图标微光）
    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by if (isRunning) {
        infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    // 底部流动光条平移动画
    val shimmerProgress by if (isRunning) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
            .clickable { onOpenInExtension() }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 左侧图标 + 中间标题与元数据
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. 图标容器
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.accentPrimary.copy(alpha = if (isRunning) 0.18f * pulseAlpha else 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = FeatherIcons.FileText,
                            contentDescription = null,
                            tint = colors.accentPrimary.copy(alpha = if (isRunning) pulseAlpha else 1f),
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    // 2. 中间文本信息
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = title,
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    color = Color(0xFFF59E0B),
                                    strokeWidth = 1.3.dp,
                                    modifier = Modifier.size(10.dp)
                                )
                                Text(
                                    text = stringResource(Res.string.attachment_writing) + " · " +
                                        stringResource(Res.string.attachment_lines, lineCount) + " · " +
                                        formatCharCount(charCount),
                                    color = colors.textMuted,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                val timeStr = if (createdAt > 0) xyz.mederi.formatMessageTime(createdAt) else ""
                                val meta = listOfNotNull(
                                    timeStr.takeIf { it.isNotBlank() },
                                    stringResource(Res.string.attachment_lines, lineCount),
                                    formatCharCount(charCount)
                                ).joinToString(" · ")
                                Text(
                                    text = meta,
                                    color = colors.textMuted,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // 右侧“阅读器”胶囊按钮
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.buttonSecondary)
                        .padding(horizontal = 8.dp, vertical = 4.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = FeatherIcons.Sidebar,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = stringResource(Res.string.attachment_reader),
                        color = colors.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 底部 1.5dp 流动光条
            if (isRunning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.5.dp)
                        .drawBehind {
                            val barWidth = size.width * 0.4f
                            val startX = (size.width + barWidth) * shimmerProgress - barWidth
                            drawRect(
                                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        colors.accentPrimary,
                                        colors.accentSecondary,
                                        Color.Transparent
                                    ),
                                    startX = startX,
                                    endX = startX + barWidth
                                )
                            )
                        }
                )
            }
        }
    }
}

@Composable
private fun formatCharCount(count: Int): String {
    val unit = stringResource(Res.string.attachment_chars_unit)
    return if (count >= 1000) {
        val k = count / 1000
        val dec = (count % 1000) / 100
        "${k}.${dec}k $unit"
    } else {
        "$count $unit"
    }
}

/**
 * 结构化多步工作过程聚合栏 (WorkTraceCard)
 * 统一汇总折叠条：将一轮对话中的全部中间步骤（思考过程、过渡语、工具调用）折叠进一个栏目，
 * 默认显示：[Activity图标] 工作过程 (N 项操作 · 耗时 X.Xs)  ›
 * 展开后，在内部按时序渲染各个步骤子项，左侧带有贯通的微导轨线。
 */
@Composable
fun WorkTraceCard(
    workTrace: ChatListItem.WorkTraceBlock,
    modifier: Modifier = Modifier,
    renderStepItem: @Composable (ChatListItem) -> Unit,
) {
    if (workTrace.items.isEmpty()) return

    val colors = LocalMederiColors.current
    var userChoice by remember(workTrace.key) { mutableStateOf<Boolean?>(null) }
    val autoExpanded = false
    val isExpanded = userChoice ?: autoExpanded

    // 内容是否无限高：默认限高 + 栏内滚动，点块底部按钮切换为全部摊开，再点缩回限高
    var isUnbounded by remember(workTrace.key) { mutableStateOf(false) }

    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(150)
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 汇总微栏整行可点击
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { userChoice = !isExpanded }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (workTrace.hasFailedTool) {
                Icon(
                    imageVector = FeatherIcons.AlertCircle,
                    contentDescription = null,
                    tint = colors.accentDanger,
                    modifier = Modifier.size(13.dp)
                )
            } else {
                Icon(
                    imageVector = FeatherIcons.Activity,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(13.dp)
                )
            }

            val durationStr = if (workTrace.totalDurationMs > 0) {
                val sec = workTrace.totalDurationMs / 1000
                if (sec >= 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
            } else ""

            val titleText = run {
                val title = stringResource(Res.string.worktrace_summary_title)
                val details = mutableListOf<String>()
                if (workTrace.totalToolsCount > 0) {
                    details.add(stringResource(Res.string.worktrace_steps_count, workTrace.totalToolsCount))
                }
                if (durationStr.isNotBlank()) {
                    details.add(stringResource(Res.string.worktrace_duration, durationStr))
                }
                if (workTrace.hasFailedTool) {
                    details.add(stringResource(Res.string.worktrace_has_failure))
                }
                if (details.isNotEmpty()) "$title (${details.joinToString(" · ")})" else title
            }

            Text(
                text = titleText,
                color = if (workTrace.hasFailedTool) colors.accentDanger else (if (colors.isDark) Color(0xFF94A3B8) else colors.textSecondary),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold
            )

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

        // 展开后的完整工作轨迹子项（左侧细微导轨线）。
        // 默认最高高度限制：内容超高时栏内上下滚动，避免推理/工具步骤把整个聊天顶得过长；
        // 内容末尾的「展开/收起」按钮随内容滚动、永远位于块的最底部：
        // 点击切换为无限高度（全部摊开，不再限高），再点缩回限高。
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val railColor = if (colors.isDark) Color(0xFF2E3240) else Color(0xFFD0D5DD)
            val traceScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (!isUnbounded) {
                            Modifier
                                .heightIn(max = WorkTraceMaxContentHeight)
                                .verticalScroll(traceScrollState)
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = 2.dp, horizontal = 4.dp)
                    .drawBehind {
                        val strokeWidth = 1.5.dp.toPx()
                        drawLine(
                            color = railColor,
                            start = Offset(strokeWidth / 2f, 0f),
                            end = Offset(strokeWidth / 2f, size.height),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                workTrace.items.forEach { stepItem ->
                    renderStepItem(stepItem)
                }

                // 底部切换按钮：位于内容末尾、随内容滚动（永远在块的最底部），恒显示
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            isUnbounded = !isUnbounded
                            DebugLog.debug("UI", "WorkTraceCard isUnbounded toggled to: $isUnbounded")
                        }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isUnbounded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                        contentDescription = null,
                        tint = if (colors.isDark) Color(0xFF94A3B8) else colors.textMuted,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(
                            if (isUnbounded) Res.string.worktrace_collapse else Res.string.worktrace_expand
                        ),
                        color = if (colors.isDark) Color(0xFF94A3B8) else colors.textMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
