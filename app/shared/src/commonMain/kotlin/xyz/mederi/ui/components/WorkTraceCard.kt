package xyz.mederi.ui.components

import xyz.mederi.ui.components.atoms.WorkspaceLoadingIndicator

import xyz.mederi.theme.LocalWorkspaceMotion

import kotlinx.coroutines.delay
import kotlin.time.TimeSource
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.worktrace_collapse
import mederi.app.shared.generated.resources.worktrace_duration
import mederi.app.shared.generated.resources.worktrace_expand
import mederi.app.shared.generated.resources.worktrace_has_failure
import mederi.app.shared.generated.resources.worktrace_steps_count
import mederi.app.shared.generated.resources.worktrace_summary_title
import mederi.app.shared.generated.resources.status_working
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.WorkTraceActivity
import xyz.mederi.ui.workTraceElapsedMs
import xyz.mederi.ui.chat.ToolActionGroup
import xyz.mederi.ui.chat.classifyToolAction
import mederi.app.shared.generated.resources.status_thinking
import mederi.app.shared.generated.resources.status_generating
import mederi.app.shared.generated.resources.worktrace_waiting_response
import mederi.app.shared.generated.resources.worktrace_waiting_subagents
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.components.atoms.expansionSemantics
import xyz.mederi.ui.components.atoms.ProcessRow

/**
 * 工作过程（WorkTraceCard）展开内容的步骤视口**最大**高度：子步骤在视口内独立上下滚动，
 * 内容超出该上限才出现滚动条；短内容按实际高度收缩（不再撑出固定 320.dp 空盒）。
 * 视口溢出时，容器底部出现「展开/收起」按钮，点击可切换为无限高真实高度（由外层 LazyColumn 滚动看全量步骤），再点缩回限高。
 */
private val WorkTraceMaxContentHeight = 320.dp

/**
 * 结构化多步工作过程聚合栏 (WorkTraceCard)
 * 统一汇总折叠条：将一轮对话中的全部中间步骤（思考过程、过渡语、工具调用）折叠进一个栏目，
 * 默认显示：[Activity图标] 工作过程 (N 项操作 · 耗时 X.Xs)  ›
 * 展开后，在内部按时序渲染各个步骤子项，左侧带有贯通的微导轨线。
 *
 * 汇总头（行高 / 圆角 / hover / 箭头 / 展开区导轨线）统一由 `atoms/ProcessRow.kt` 提供，
 * 本组件只负责「图标（失败/正常）+ 状态色 + 标题拼接 + 展开后的步骤视口」。
 */
@Composable
fun WorkTraceCard(
    workTrace: ChatListItem.WorkTraceBlock,
    modifier: Modifier = Modifier,
    renderStepItem: @Composable (ChatListItem) -> Unit,
) {
    if (workTrace.items.isEmpty() && !workTrace.isRunning) return

    val colors = LocalMederiColors.current
    var userChoice by remember(workTrace.key) { mutableStateOf<Boolean?>(null) }
    val isExpanded = userChoice ?: false

    // 底部按钮可解除内部限高，由外层列表滚动；再次点击恢复原限高。
    var isUnbounded by remember(workTrace.key) { mutableStateOf(false) }

    // 起终点来自Core；运行中使用单调时钟递增，结束后只读持久化终点。
    var now by remember(workTrace.key) { mutableLongStateOf(xyz.mederi.currentTimeMillis()) }
    LaunchedEffect(workTrace.key, workTrace.isRunning, workTrace.startedAt, workTrace.completedAt) {
        val origin = xyz.mederi.currentTimeMillis()
        val ticking = TimeSource.Monotonic.markNow()
        now = origin
        if (workTrace.isRunning && workTrace.startedAt != null && workTrace.completedAt == null) {
            while (true) {
                delay(1_000)
                now = origin + ticking.elapsedNow().inWholeMilliseconds
            }
        }
    }
    val elapsedMs = workTraceElapsedMs(workTrace.startedAt, workTrace.completedAt, workTrace.isRunning, now)
    val durationStr = elapsedMs?.let { elapsed ->
        val sec = elapsed / 1_000
        if (sec >= 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
    }
    val titleText = buildList {
        add(stringResource(if (workTrace.isRunning) Res.string.status_working else Res.string.worktrace_summary_title))
        if (!workTrace.isRunning && workTrace.totalToolsCount > 0) {
            add(stringResource(Res.string.worktrace_steps_count, workTrace.totalToolsCount))
        }
        durationStr?.let { add(stringResource(Res.string.worktrace_duration, it)) }
        if (workTrace.hasFailedTool) add(stringResource(Res.string.worktrace_has_failure))
    }.joinToString(" · ")
    val activitySummary = if (workTrace.isRunning) when (val activity = workTrace.activity) {
        is WorkTraceActivity.Tool -> toolRunningLabel(ToolActionGroup(classifyToolAction(activity.call.name), listOf(activity.call)))
        WorkTraceActivity.Thinking -> stringResource(Res.string.status_thinking)
        WorkTraceActivity.Generating -> stringResource(Res.string.status_generating)
        is WorkTraceActivity.WaitingSubagents -> stringResource(Res.string.worktrace_waiting_subagents, activity.count)
        else -> stringResource(Res.string.worktrace_waiting_response)
    } else null

    // 步骤视口滚动状态提到 content 之外：底部按钮的显隐需要读 maxValue（内容是否溢出视口），
    // 必须在组合期求值，不能是展开区内部的局部变量
    val traceScrollState = rememberScrollState()
    val showHeightToggle = shouldShowHeightToggle(
        enforceMaxHeight = true,
        isUnbounded = isUnbounded,
        hasOverflow = !isUnbounded && traceScrollState.maxValue > 0,
    )

    // 汇总折叠栏：整行可点，hover 才浮出 surfaceHover 底色（无常态底色、无描边）。
    ProcessRow(
        expanded = isExpanded,
        clickable = workTrace.items.isNotEmpty(),
        onExpandedChange = { userChoice = !isExpanded },
        modifier = modifier.fillMaxWidth(),
        icon = if (workTrace.hasFailedTool) FeatherIcons.AlertCircle else FeatherIcons.Activity,
        iconTint = if (workTrace.hasFailedTool) colors.accentDanger else colors.accentPrimary,
        label = titleText,
        labelColor = if (workTrace.hasFailedTool) colors.accentDanger else colors.textSecondary,
        summary = activitySummary,
        leading = if (workTrace.isRunning && !isExpanded && workTrace.activity !is WorkTraceActivity.WaitingSubagents) {
            { WorkspaceLoadingIndicator(color = colors.accentPrimary, strokeWidth = 1.4.dp,
                modifier = Modifier.size(WorkspaceStyle.IconSize)) }
        } else null,
    ) {
        // 展开后的完整工作轨迹子项（导轨线由 ProcessRow 统一绘制）。
        // 容器高度自适应内容（不设固定高），上限 320.dp 由可滚动的步骤视口自己承担：
        // 步骤少时按实际高度收缩，避免推理/工具步骤把整个聊天顶得过长；
        // 内容溢出时底部出现「展开/收起」按钮（不随内容滚动），点击切换为无限高真实高度，再点缩回。
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 步骤列表视口：高度自适应内容，上限 WorkTraceMaxContentHeight 由本节点自己承担
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (!isUnbounded) {
                            Modifier
                                .containScroll()
                                .heightIn(max = WorkTraceMaxContentHeight)
                                .verticalScroll(traceScrollState)
                        } else {
                            Modifier
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                workTrace.items.forEach { stepItem ->
                    renderStepItem(stepItem)
                }
            }

            // 底部切换按钮：仅步骤溢出视口时出现（展开态下恒显示），不随步骤内容滚动
            // 与独立推理共用轻量开关；热区、焦点与无障碍语义一致。
            if (showHeightToggle) {
                ProcessHeightToggle(
                    expanded = isUnbounded,
                    onExpandedChange = { isUnbounded = it },
                )
            }
        }
    }
}


/** 过程与推理共用的内容限高开关，只改变呈现高度，不改变滚动归属。 */
@Composable
internal fun ProcessHeightToggle(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (hovered || focused) colors.surfaceHover else Color.Transparent,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "processHeightToggleFeedback",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WorkspaceStyle.ControlHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .expansionSemantics(expanded, onExpandedChange)
            .clickable(interactionSource = interactionSource, role = Role.Button) { onExpandedChange(!expanded) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            imageVector = if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(WorkspaceStyle.IconSize),
        )
        Text(
            text = stringResource(if (expanded) Res.string.worktrace_collapse else Res.string.worktrace_expand),
            color = colors.textSecondary,
            style = WorkspaceStyle.Caption,
        )
    }
}
