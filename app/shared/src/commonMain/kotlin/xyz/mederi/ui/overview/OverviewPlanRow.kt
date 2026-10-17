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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.FileText
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.dock_plan_approve
import mederi.app.shared.generated.resources.overview_section_plan
import mederi.app.shared.generated.resources.plan_read_full
import mederi.app.shared.generated.resources.plan_status_completed
import mederi.app.shared.generated.resources.plan_status_in_progress
import mederi.app.shared.generated.resources.plan_status_none
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
import xyz.mederi.ui.PlanOverviewItem
import xyz.mederi.ui.PlanOverviewStatus
import xyz.mederi.ui.PlanSubtaskOverview
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.MederiPrimaryDecisionButton
import xyz.mederi.ui.components.atoms.MederiStepStatusIcon
import xyz.mederi.ui.components.atoms.StepStatus

/**
 * 概览浮动窗计划行。
 *
 * 折叠态摘要：计划数量。
 * 展开态：每个计划一张紧凑卡（标题 + 状态 + 批准钮 + 阅读全文 + 步骤列表，
 * 步骤 > 3 收起为前 3 步），行为参照原 PlansOverviewCard / PlanOverviewItemCard。
 *
 * 数据来源 [WorkspaceViewModel.planOverviewList]；无计划时整行不渲染
 * （计数为 0 对用户无信息量，避免占用浮动窗空间）。
 */
@Composable
fun OverviewPlanRow(
    viewModel: WorkspaceViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val plans = viewModel.planOverviewList
    // 无计划 → 整行不渲染（不调用 OverviewAccordionRow）
    if (plans.isEmpty()) return

    OverviewAccordionRow(
        icon = FeatherIcons.FileText,
        title = stringResource(Res.string.overview_section_plan),
        summary = {
            Text(
                text = plans.size.toString(),
                color = colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        if (plans.isEmpty()) {
            // 防御分支：外部已在 plans.isEmpty() 时整行不渲染，此处保留空态以对齐
            // 原 PlansOverviewCard 的语义（正常运行时不可达）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MederiSpacing.SM),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.plan_status_none),
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MederiSpacing.MD),
            ) {
                plans.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(color = colors.divider, thickness = 1.dp)
                    }
                    PlanOverviewItemCompactCard(
                        item = item,
                        viewModel = viewModel,
                        colors = colors,
                    )
                }
            }
        }
    }
}

/**
 * 计划紧凑项：扁平排版（无底卡片），
 * 标题行 + 批准钮（仅待批准）+ 阅读全文链接 + 步骤列表（>3 步默认收起）。
 */
@Composable
private fun PlanOverviewItemCompactCard(
    item: PlanOverviewItem,
    viewModel: WorkspaceViewModel,
    colors: MederiColors,
) {
    // 步骤列表默认折叠到前 3 步，可展开全部
    var isSubtasksExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
    ) {
        // 标题行：计划标题（单行省略）+ 状态文字
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.title,
                modifier = Modifier.weight(1f, fill = false),
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (item.status) {
                    PlanOverviewStatus.PendingApproval -> stringResource(Res.string.plan_status_pending)
                    PlanOverviewStatus.InProgress -> stringResource(Res.string.plan_status_in_progress)
                    PlanOverviewStatus.Completed -> stringResource(Res.string.plan_status_completed)
                    PlanOverviewStatus.Voided -> stringResource(Res.string.plan_status_voided)
                },
                color = if (item.status == PlanOverviewStatus.Completed) colors.accentSuccess else colors.textMuted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }

        // 批准按钮：仅待批准
        if (item.status == PlanOverviewStatus.PendingApproval) {
            MederiPrimaryDecisionButton(
                text = stringResource(Res.string.dock_plan_approve),
                onClick = { viewModel.approvePlan(item.id) },
                icon = FeatherIcons.Check,
            )
        }

        // 阅读全文链接（始终显示）
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                // 平台分流归 VM（viewModel.openPlanItem 按 fileTreeProvider 是否存在决定
                // openFile 统一路由 / openPlanFile 内存兜底）；View 只发意图，不读 CompositionLocal
                .clickable { viewModel.openPlanItem(item) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = FeatherIcons.FileText,
                contentDescription = null,
                tint = colors.accentText,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = stringResource(Res.string.plan_read_full),
                color = colors.accentText,
                fontSize = 11.5.sp,
            )
        }

        // 步骤列表（已完成 = 绿勾划线 / 进行中 = accent 高亮 / 待处理 = 灰）
        if (item.subtasks.isNotEmpty()) {
            HorizontalDivider(color = colors.divider, thickness = 1.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val visible = if (isSubtasksExpanded) item.subtasks else item.subtasks.take(3)
                for (st in visible) {
                    val stDone = st.status.equals("COMPLETED", ignoreCase = true)
                    val stRunning = st.status.equals("IN_PROGRESS", ignoreCase = true)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MederiStepStatusIcon(
                            status = when {
                                stDone -> StepStatus.Done
                                stRunning -> StepStatus.Active
                                else -> StepStatus.Todo
                            }
                        )
                        Text(
                            text = "${stringResource(Res.string.step_label, st.index + 1)} ${st.name}",
                            modifier = Modifier.weight(1f, fill = false),
                            fontSize = if (stRunning) 12.5.sp else 12.sp,
                            fontWeight = if (stRunning) FontWeight.Medium else FontWeight.Normal,
                            color = when {
                                stDone -> colors.textMuted
                                stRunning -> colors.textPrimary
                                else -> colors.textSecondary
                            },
                            textDecoration = if (stDone) TextDecoration.LineThrough else TextDecoration.None,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 有关联 spec → [Spec ↗] 链接
                        if (!st.spec.isNullOrBlank()) {
                            Text(
                                text = stringResource(Res.string.step_spec),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        viewModel.openSpecInExtension(
                                            planId = item.id,
                                            planTitle = item.title,
                                            subtaskIndex = st.index,
                                            subtaskName = st.name,
                                            specContent = st.spec,
                                        )
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                color = colors.accentText,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }

                // 步骤数 > 3：展开/收起全部
                if (item.subtasks.size > 3) {
                    Text(
                        text = stringResource(
                            if (isSubtasksExpanded) Res.string.worktrace_collapse else Res.string.worktrace_expand
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { isSubtasksExpanded = !isSubtasksExpanded }
                            .padding(vertical = 4.dp),
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}