package xyz.mederi.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.overview_todo_cancelled
import mederi.app.shared.generated.resources.overview_todo_completed
import mederi.app.shared.generated.resources.overview_todo_failed
import mederi.app.shared.generated.resources.overview_todo_in_progress
import mederi.app.shared.generated.resources.overview_todo_pending
import mederi.app.shared.generated.resources.rp_todo_list_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.TodoItem
import xyz.mederi.core.contract.models.TodoStatus
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.WorkspaceTaskStep
import xyz.mederi.ui.components.atoms.WorkspaceTaskStepState

/** VM入口只传入真实Todo，不与计划步骤拼成另一份清单。 */
@Composable
fun OverviewTodoRow(
    viewModel: WorkspaceViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverviewTodoContent(viewModel.todos, expanded, onToggle, modifier)
}

/** 五态均显示明确文字，Cancelled不再落入Pending的无差别灰色分支。 */
@Composable
internal fun OverviewTodoContent(
    todos: List<TodoItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    colors: MederiColors = LocalMederiColors.current,
) {
    if (todos.isEmpty()) return
    val completedCount = todos.count { it.status == TodoStatus.Completed }
    OverviewAccordionRow(
        icon = FeatherIcons.CheckSquare,
        title = stringResource(Res.string.rp_todo_list_title),
        summary = { Text("$completedCount/${todos.size}", style = WorkspaceStyle.Caption, color = colors.textSecondary) },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
            todos.forEach { task ->
                key(task.id) {
                    val state = when (task.status) {
                        TodoStatus.Pending -> WorkspaceTaskStepState.Pending
                        TodoStatus.InProgress -> WorkspaceTaskStepState.Running
                        TodoStatus.Completed -> WorkspaceTaskStepState.Completed
                        TodoStatus.Failed -> WorkspaceTaskStepState.Failed
                        TodoStatus.Cancelled -> WorkspaceTaskStepState.Cancelled
                    }
                    val label = stringResource(when (task.status) {
                        TodoStatus.Pending -> Res.string.overview_todo_pending
                        TodoStatus.InProgress -> Res.string.overview_todo_in_progress
                        TodoStatus.Completed -> Res.string.overview_todo_completed
                        TodoStatus.Failed -> Res.string.overview_todo_failed
                        TodoStatus.Cancelled -> Res.string.overview_todo_cancelled
                    })
                    WorkspaceTaskStep(task.content, label, state, colors = colors)
                }
            }
        }
    }
}
