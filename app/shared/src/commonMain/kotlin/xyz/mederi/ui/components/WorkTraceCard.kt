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

/** 执行中展开过程，交付/等待子代理时收起；正文原样显示，推理与工具独立折叠。 */
@Composable
fun WorkTraceCard(
    workTrace: ChatListItem.WorkTraceBlock,
    modifier: Modifier = Modifier,
    renderStepItem: @Composable (ChatListItem) -> Unit,
) {
    if (workTrace.items.isEmpty() && !workTrace.isRunning) return

    val colors = LocalMederiColors.current
    var userChoice by remember(workTrace.key, workTrace.defaultExpanded) { mutableStateOf<Boolean?>(null) }
    val isExpanded = userChoice ?: workTrace.defaultExpanded

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
    }.joinToString(" · ")
    val activitySummary = if (workTrace.isRunning) when (val activity = workTrace.activity) {
        is WorkTraceActivity.Tool -> toolRunningLabel(ToolActionGroup(classifyToolAction(activity.call.name), listOf(activity.call)))
        WorkTraceActivity.Thinking -> stringResource(Res.string.status_thinking)
        WorkTraceActivity.Generating -> stringResource(Res.string.status_generating)
        is WorkTraceActivity.WaitingSubagents -> stringResource(Res.string.worktrace_waiting_subagents, activity.count)
        else -> stringResource(Res.string.worktrace_waiting_response)
    } else null

    // 汇总折叠栏：整行可点，hover 才浮出 surfaceHover 底色（无常态底色、无描边）。
    ProcessRow(
        expanded = isExpanded && workTrace.items.isNotEmpty(),
        clickable = workTrace.items.isNotEmpty(),
        onExpandedChange = { userChoice = !isExpanded },
        modifier = modifier.fillMaxWidth(),
        icon = FeatherIcons.Activity,
        iconTint = colors.accentPrimary,
        label = titleText,
        labelColor = colors.textSecondary,
        summary = activitySummary,
        leading = if (workTrace.isRunning && (!isExpanded || workTrace.items.isEmpty()) && workTrace.activity !is WorkTraceActivity.WaitingSubagents) {
            { WorkspaceLoadingIndicator(color = colors.accentPrimary, strokeWidth = 1.4.dp,
                modifier = Modifier.size(WorkspaceStyle.IconSize)) }
        } else null,
    ) {
        // 整体不再限高裁剪正文；长推理/工具详情由子项独立折叠，页面统一滚动。
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            workTrace.items.forEach { stepItem ->
                key(stepItem.key) { renderStepItem(stepItem) }
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
