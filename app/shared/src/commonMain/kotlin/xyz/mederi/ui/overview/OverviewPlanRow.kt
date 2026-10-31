package xyz.mederi.ui.overview

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.FileText
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.dock_plan_approve
import mederi.app.shared.generated.resources.overview_section_plan
import mederi.app.shared.generated.resources.overview_todo_pending
import mederi.app.shared.generated.resources.plan_read_full
import mederi.app.shared.generated.resources.plan_status_completed
import mederi.app.shared.generated.resources.plan_status_in_progress
import mederi.app.shared.generated.resources.plan_status_pending
import mederi.app.shared.generated.resources.plan_status_voided
import mederi.app.shared.generated.resources.step_label
import mederi.app.shared.generated.resources.step_spec
import mederi.app.shared.generated.resources.worktrace_collapse
import mederi.app.shared.generated.resources.worktrace_expand
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.PlanOverviewItem
import xyz.mederi.ui.PlanOverviewStatus
import xyz.mederi.ui.PlanSubtaskOverview
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.ExpandableContent
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceTaskStep
import xyz.mederi.ui.components.atoms.WorkspaceTaskStepState
import xyz.mederi.ui.components.atoms.expansionSemantics

/** 概览计划仍只读planOverviewList，打开计划/Spec的路由继续由VM处理。 */
@Composable
fun OverviewPlanRow(
    viewModel: WorkspaceViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverviewPlanContent(
        plans = viewModel.planOverviewList,
        expanded = expanded,
        onToggle = onToggle,
        onApprove = { viewModel.approvePlan(it) },
        onOpenPlan = viewModel::openPlanItem,
        onOpenSpec = { plan, step ->
            viewModel.openSpecInExtension(plan.id, plan.title, step.index, step.name, step.spec)
        },
        modifier = modifier,
    )
}

@Composable
internal fun OverviewPlanContent(
    plans: List<PlanOverviewItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onApprove: (String) -> Unit,
    onOpenPlan: (PlanOverviewItem) -> Unit,
    onOpenSpec: (PlanOverviewItem, PlanSubtaskOverview) -> Unit,
    modifier: Modifier = Modifier,
    colors: MederiColors = LocalMederiColors.current,
) {
    if (plans.isEmpty()) return
    OverviewAccordionRow(
        icon = FeatherIcons.FileText,
        title = stringResource(Res.string.overview_section_plan),
        summary = { Text(plans.size.toString(), style = WorkspaceStyle.Caption, color = colors.textSecondary) },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MederiSpacing.MD)) {
            plans.forEach { plan ->
                key(plan.id) {
                    OverviewPlanItemContent(
                        item = plan,
                        onApprove = { onApprove(plan.id) },
                        onOpenPlan = { onOpenPlan(plan) },
                        onOpenSpec = { onOpenSpec(plan, it) },
                        colors = colors,
                    )
                }
            }
        }
    }
}

/** 平面计划信息：长标题独占一行，操作可回流；展开偏好按planId隔离。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverviewPlanItemContent(
    item: PlanOverviewItem,
    onApprove: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenSpec: (PlanSubtaskOverview) -> Unit,
    modifier: Modifier = Modifier,
    colors: MederiColors = LocalMederiColors.current,
) {
    var subtasksExpanded by remember(item.id) { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = MederiSpacing.SM),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
    ) {
        Text(item.title, style = WorkspaceStyle.Title, color = colors.textPrimary)
        Text(
            text = stringResource(when (item.status) {
                PlanOverviewStatus.PendingApproval -> Res.string.plan_status_pending
                PlanOverviewStatus.InProgress -> Res.string.plan_status_in_progress
                PlanOverviewStatus.Completed -> Res.string.plan_status_completed
                PlanOverviewStatus.Voided -> Res.string.plan_status_voided
            }),
            style = WorkspaceStyle.Caption,
            color = colors.textMuted,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
        ) {
            WorkspaceActionButton(stringResource(Res.string.plan_read_full), onOpenPlan,
                icon = FeatherIcons.FileText, colors = colors)
            if (item.status == PlanOverviewStatus.PendingApproval) {
                WorkspaceActionButton(
                    text = stringResource(Res.string.dock_plan_approve),
                    onClick = { if (item.status == PlanOverviewStatus.PendingApproval) onApprove() },
                    primary = true, icon = FeatherIcons.Check, colors = colors,
                )
            }
        }
        item.subtasks.take(3).forEach { step ->
            key(step.index) { OverviewPlanStep(step, onOpenSpec, colors) }
        }
        if (item.subtasks.size > 3) {
            ExpandableContent(expanded = subtasksExpanded) {
                item.subtasks.drop(3).forEach { step ->
                    key(step.index) { OverviewPlanStep(step, onOpenSpec, colors) }
                }
            }
            WorkspaceActionButton(
                text = stringResource(if (subtasksExpanded) Res.string.worktrace_collapse else Res.string.worktrace_expand),
                onClick = { subtasksExpanded = !subtasksExpanded },
                modifier = Modifier.expansionSemantics(subtasksExpanded) { subtasksExpanded = it },
                colors = colors,
            )
        }
    }
}

@Composable
private fun OverviewPlanStep(
    step: PlanSubtaskOverview,
    onOpenSpec: (PlanSubtaskOverview) -> Unit,
    colors: MederiColors,
) {
    // 保留计划原有三档图形映射；原始未知状态仍以真实文字展示，不从Todo反推。
    val state = when {
        step.status.equals("COMPLETED", ignoreCase = true) -> WorkspaceTaskStepState.Completed
        step.status.equals("IN_PROGRESS", ignoreCase = true) -> WorkspaceTaskStepState.Running
        else -> WorkspaceTaskStepState.Pending
    }
    val statusLabel = when {
        step.status.equals("COMPLETED", ignoreCase = true) -> stringResource(Res.string.plan_status_completed)
        step.status.equals("IN_PROGRESS", ignoreCase = true) -> stringResource(Res.string.plan_status_in_progress)
        step.status.equals("PENDING", ignoreCase = true) -> stringResource(Res.string.overview_todo_pending)
        else -> step.status
    }
    WorkspaceTaskStep(
        text = "${stringResource(Res.string.step_label, step.index + 1)} ${step.name}",
        statusLabel = statusLabel,
        state = state,
        colors = colors,
    ) {
        if (!step.spec.isNullOrBlank()) {
            WorkspaceActionButton(stringResource(Res.string.step_spec), { onOpenSpec(step) }, colors = colors)
        }
    }
}
