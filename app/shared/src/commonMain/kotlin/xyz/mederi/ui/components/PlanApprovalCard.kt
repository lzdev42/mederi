package xyz.mederi.ui.components

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.plan_approval_approved
import mederi.app.shared.generated.resources.plan_approval_proceed
import mederi.app.shared.generated.resources.plan_approval_rejected
import mederi.app.shared.generated.resources.plan_approval_status_other
import mederi.app.shared.generated.resources.plan_approval_title
import mederi.app.shared.generated.resources.plan_approval_title_default
import mederi.app.shared.generated.resources.plan_read_full
import mederi.app.shared.generated.resources.plan_status_completed
import mederi.app.shared.generated.resources.plan_status_in_progress
import mederi.app.shared.generated.resources.plan_status_voided
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.ChatLayout
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceActionSurface
import xyz.mederi.ui.components.atoms.WorkspaceActionTone

/**
 * 计划是可查看的内容，批准是独立且只在 pending 可用的动作。
 * 整卡保留查看/键盘入口；内部按钮消费自己的点击，不把批准冒泡成查看。
 * 不把所有非 pending 状态都标为“已批准”，未知状态保留真实值。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanApprovalCard(
    request: PlanApprovalRequest?,
    onApprove: () -> Unit,
    onOpenInExtension: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (request == null) return
    val colors = LocalMederiColors.current
    val isPending = request.status.equals("PENDING", ignoreCase = true) ||
        request.status.equals("PENDING_APPROVAL", ignoreCase = true)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val titleColor by animateColorAsState(
        targetValue = if (hovered || focused) colors.accentText else colors.textPrimary,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "planTitleColor",
    )
    val openLabel = stringResource(Res.string.plan_read_full)
    WorkspaceActionSurface(
        tone = if (isPending) WorkspaceActionTone.Accent else WorkspaceActionTone.Neutral,
        modifier = modifier.widthIn(max = ChatLayout.actionCardMaxWidth)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button,
                onClickLabel = openLabel, onClick = onOpenInExtension),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        ) {
            Icon(FeatherIcons.FileText, contentDescription = null, tint = colors.textSecondary,
                modifier = Modifier.size(WorkspaceStyle.IconSize))
            Text(
                text = if (request.title.isNotBlank()) {
                    stringResource(Res.string.plan_approval_title, request.title)
                } else stringResource(Res.string.plan_approval_title_default),
                style = WorkspaceStyle.Title,
                color = titleColor,
                modifier = Modifier.weight(1f),
            )
        }
        if (request.id.isNotBlank()) {
            Text(request.id, style = WorkspaceStyle.Caption, color = colors.textMuted)
        }
        val summary = request.summary.ifBlank { request.title }
        if (summary.isNotBlank()) {
            Text(summary, style = WorkspaceStyle.Body, color = colors.textSecondary)
        }
        if (!isPending) {
            val statusLabel = when (request.status.uppercase()) {
                "APPROVED", "AUTO_APPROVED" -> stringResource(Res.string.plan_approval_approved)
                "IN_PROGRESS" -> stringResource(Res.string.plan_status_in_progress)
                "COMPLETED" -> stringResource(Res.string.plan_status_completed)
                "VOIDED" -> stringResource(Res.string.plan_status_voided)
                "REJECTED" -> stringResource(Res.string.plan_approval_rejected)
                else -> stringResource(Res.string.plan_approval_status_other, request.status)
            }
            Text(statusLabel, style = WorkspaceStyle.Caption, color = colors.textSecondary)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
        ) {
            WorkspaceActionButton(text = openLabel, onClick = onOpenInExtension)
            if (isPending) {
                WorkspaceActionButton(
                    text = stringResource(Res.string.plan_approval_proceed),
                    onClick = { if (isPending) onApprove() },
                    primary = true,
                )
            }
        }
    }
}
