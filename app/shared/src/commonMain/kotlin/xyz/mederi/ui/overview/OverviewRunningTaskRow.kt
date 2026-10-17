package xyz.mederi.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.X
import compose.icons.feathericons.Zap
import kotlinx.coroutines.delay
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.overview_running_task_count
import mederi.app.shared.generated.resources.overview_running_task_empty
import mederi.app.shared.generated.resources.overview_running_task_stop
import mederi.app.shared.generated.resources.overview_running_task_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.BackgroundTaskInfo
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.ui.WorkspaceViewModel

/**
 * 概览浮动窗后台任务行（命令 + 定时器）。
 *
 * 折叠态摘要：有运行中的任务时显示强调色「N 运行中」，否则无摘要。
 *
 * 展开态：逐条渲染 [BackgroundTaskItemRow]（类型图标 + 命令/描述单行省略 + 非运行状态徽标 +
 * 运行中停止按钮）。行被组合期间（窗口可见且未最小化）每 2 秒轮询一次
 * [WorkspaceViewModel.pollBackgroundTasks]——最小化后行不再组合，轮询自动停止。
 */
@Composable
fun OverviewRunningTaskRow(
    viewModel: WorkspaceViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val tasks = viewModel.backgroundTasks
    val runningCount = tasks.count { it.status == "running" }

    // 轮询只在本行组合期间进行（窗口可见 + 未最小化）；切会话时 key 变化重启循环
    LaunchedEffect(viewModel.conversationId) {
        while (true) {
            viewModel.pollBackgroundTasks()
            delay(2000)
        }
    }

    OverviewAccordionRow(
        icon = FeatherIcons.Zap,
        title = stringResource(Res.string.overview_running_task_title),
        summary = {
            if (runningCount > 0) {
                Text(
                    text = stringResource(Res.string.overview_running_task_count, runningCount),
                    color = colors.accentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        if (tasks.isEmpty()) {
            Text(
                text = stringResource(Res.string.overview_running_task_empty),
                color = colors.textMuted,
                fontSize = 11.sp,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.Tight)) {
                tasks.forEach { task ->
                    BackgroundTaskItemRow(
                        task = task,
                        colors = colors,
                        onStop = { viewModel.stopBackgroundTask(task.taskId) },
                    )
                }
            }
        }
    }
}

/**
 * 单条后台任务行：类型图标（command=Terminal / timer=Clock）+ 命令/描述单行省略 +
 * 非运行状态徽标 + 运行中停止（X）按钮。已完成/出错/已停止任务仍在列表瞬间时仅显示徽标。
 */
@Composable
private fun BackgroundTaskItemRow(
    task: BackgroundTaskInfo,
    colors: MederiColors,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 运行中：小转圈动画（替代静态图标，视觉上"正在执行"）
        // 非运行：类型图标（command=Terminal / timer=Clock）
        if (task.status == "running") {
            // 呼吸脉冲：alpha 0.4→1.0 循环，让转圈有"活着"的感觉
            val pulseTransition = rememberInfiniteTransition(label = "bg_task_pulse")
            val pulseAlpha by pulseTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "pulse_alpha",
            )
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp).alpha(pulseAlpha),
                strokeWidth = 1.5.dp,
                color = colors.accentPrimary,
            )
        } else {
            Icon(
                imageVector = if (task.taskType == "timer") FeatherIcons.Clock else FeatherIcons.Terminal,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(12.dp),
            )
        }

        // 命令或描述（运行中亮色 + 呼吸 alpha，非运行暗色）
        val textAlpha = if (task.status == "running") {
            val t = rememberInfiniteTransition(label = "bg_task_text_pulse")
            val a by t.animateFloat(
                initialValue = 0.6f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "text_pulse_alpha",
            )
            a
        } else 1f
        Text(
            text = task.command ?: task.description ?: task.taskId,
            color = if (task.status == "running") colors.textPrimary else colors.textMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alpha(textAlpha),
        )

        // 非运行状态徽标
        if (task.status != "running") {
            Text(
                text = task.status,
                color = if (task.status == "error") colors.accentDanger else colors.textMuted,
                fontSize = 9.sp,
                maxLines = 1,
            )
        }

        // 停止按钮（仅运行中）
        if (task.status == "running") {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(MederiRadius.Badge))
                    .clickable { onStop() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = stringResource(Res.string.overview_running_task_stop),
                    tint = colors.textMuted,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
