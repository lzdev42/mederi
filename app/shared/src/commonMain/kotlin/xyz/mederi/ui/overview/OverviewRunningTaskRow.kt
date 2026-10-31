package xyz.mederi.ui.overview

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Square
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Zap
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.BackgroundTaskInfo
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.BackgroundTaskQueryState
import xyz.mederi.ui.UiMessage
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.ErrorBoard
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceIconButton
import xyz.mederi.ui.components.atoms.WorkspaceLoadingIndicator

/** 这里只适配查询投影与动作；查询订阅/轮询由工作区Route负责。 */
@Composable
fun OverviewRunningTaskRow(
    viewModel: WorkspaceViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverviewRunningTaskContent(viewModel.backgroundTaskState, expanded, onToggle,
        viewModel::refreshBackgroundTasks, viewModel::stopBackgroundTask,
        viewModel::clearBackgroundTaskStopError, modifier)
}

/** 纯展示；后台工作与主对话输入互不阻塞。 */
@Composable
internal fun OverviewRunningTaskContent(
    state: BackgroundTaskQueryState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onStop: (String) -> Unit,
    onDismissStopError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    // 无会话不能展示残留目标；有效会话的列表由查询投影提供，不在视图保存副本。
    val tasks = if (state.sessionId == null) emptyList() else state.tasks
    val runningCount = tasks.count { it.status == "running" }
    OverviewAccordionRow(
        icon = FeatherIcons.Zap,
        title = stringResource(Res.string.overview_running_task_title),
        summary = {
            if (state.sessionId != null && (state.queryError != null || state.stopErrors.isNotEmpty()))
                Text(stringResource(Res.string.overview_running_task_feedback), color = colors.dangerText, style = WorkspaceStyle.Caption, maxLines = 1)
            if (runningCount > 0) Text(stringResource(Res.string.overview_running_task_count, runningCount),
                color = colors.accentText, style = WorkspaceStyle.Caption, maxLines = 1)
        },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier.overviewTaskIndicator(runningCount > 0),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.Tight)) {
            if (state.sessionId == null) {
                Text(stringResource(Res.string.overview_running_task_no_session),
                    color = colors.textMuted, style = WorkspaceStyle.Caption)
            } else {
                // 已有快照的定期刷新静默进行，避免每2秒挤动正文并叠加组级动效。
                if (state.loading && !state.loaded) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MederiSpacing.Tight),
                ) {
                    WorkspaceLoadingIndicator(Modifier.size(WorkspaceStyle.IconSize))
                    Text(stringResource(Res.string.overview_running_task_loading),
                        color = colors.textSecondary, style = WorkspaceStyle.Caption)
                }
                state.queryError?.let { error ->
                    ErrorBoard(errorMessage = stringResource(error.key, *error.args.toTypedArray()),
                        showContinue = true,
                        onContinue = onRefresh,
                        continueLabel = stringResource(Res.string.overview_running_task_refresh))
                }
                if (state.queryError == null) WorkspaceActionButton(
                    stringResource(Res.string.overview_running_task_refresh), onClick = onRefresh)
                if (tasks.isEmpty() && state.loaded && !state.loading && state.queryError == null) {
                    Text(stringResource(Res.string.overview_running_task_empty),
                        color = colors.textMuted, style = WorkspaceStyle.Caption)
                }
                tasks.forEach { task ->
                    BackgroundTaskItemRow(task, task.taskId in state.stoppingTaskIds, onStop)
                }
                // 失败以精确ID呈现；目标结束/移除后仍能关闭错误，但不能再停止非运行目标。
                state.stopErrors.forEach { (id, error) ->
                    val canRetry = tasks.any { it.taskId == id && it.status == "running" } && id !in state.stoppingTaskIds
                    BackgroundTaskStopError(error, { onDismissStopError(id) }, if (canRetry) ({ onStop(id) }) else null)
                }
            }
        }
    }
}

@Composable
private fun BackgroundTaskItemRow(task: BackgroundTaskInfo, stopping: Boolean, onStop: (String) -> Unit) {
    val colors = LocalMederiColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = MederiSpacing.Tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.Tight)) {
        Icon(if (task.taskType == "timer") FeatherIcons.Clock else FeatherIcons.Terminal,
            contentDescription = null, tint = if (task.status == "running") colors.accentText else colors.textMuted,
            modifier = Modifier.size(WorkspaceStyle.IconSize))
        Text(task.command ?: task.description ?: task.taskId,
            color = if (task.status == "running") colors.textPrimary else colors.textMuted,
            style = WorkspaceStyle.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (task.status != "running") Text(task.status, color = if (task.status == "error") colors.accentDanger else colors.textMuted,
            style = WorkspaceStyle.Caption)
        if (task.status == "running") {
            if (stopping) WorkspaceLoadingIndicator(Modifier.size(WorkspaceStyle.IconSize))
            WorkspaceIconButton(FeatherIcons.Square, { onStop(task.taskId) },
                stringResource(Res.string.overview_running_task_stop), enabled = !stopping)
        }
    }
}

@Composable
private fun BackgroundTaskStopError(error: UiMessage, onDismiss: () -> Unit, onRetry: (() -> Unit)?) {
    ErrorBoard(errorMessage = stringResource(error.key, *error.args.toTypedArray()),
        onDismiss = onDismiss, showContinue = onRetry != null, onContinue = onRetry,
        continueLabel = stringResource(Res.string.overview_running_task_retry_stop))
}
