package xyz.mederi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.Loader
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.XCircle
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.WorkspaceOperation
import xyz.mederi.core.contract.models.WorkspaceOperationKind
import xyz.mederi.core.contract.models.WorkspaceOperationState
import xyz.mederi.core.contract.models.WorkspacePhase
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle

/**
 * 工作区操作状态条（对话区内联展示，不冒充工具调用或最终交付）。
 * 展示准备、复制、等批准、初始化、恢复等状态，提供重试与取消操作。
 */
@Composable
fun WorkspaceOperationStatusBar(
    operation: WorkspaceOperation,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRecover: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    val isCancelling = operation.cancelRequested || operation.state == WorkspaceOperationState.CANCELLING
    val isFailed = operation.state == WorkspaceOperationState.FAILED
    val isWaitingApproval = operation.state == WorkspaceOperationState.WAITING_APPROVAL
    val isCancelled = operation.state == WorkspaceOperationState.CANCELLED
    val isRecoveryRequired = operation.state == WorkspaceOperationState.RECOVERY_REQUIRED

    val statusText = when {
        isCancelling -> stringResource(Res.string.workspace_action_cancelling)
        isWaitingApproval -> stringResource(Res.string.workspace_status_waiting_approval)
        isFailed -> {
            val prefix = stringResource(Res.string.workspace_status_failed)
            val msg = operation.error?.message
            if (msg.isNullOrBlank()) prefix else "$prefix: $msg"
        }
        isCancelled -> stringResource(Res.string.workspace_status_cancelled)
        operation.phase == WorkspacePhase.COPY_CONTENT -> stringResource(Res.string.workspace_status_copying)
        operation.phase == WorkspacePhase.SETUP -> stringResource(Res.string.workspace_status_initializing)
        operation.kind == WorkspaceOperationKind.RESTORE -> stringResource(Res.string.workspace_status_restoring)
        else -> stringResource(Res.string.workspace_status_preparing)
    }

    val iconVector = when {
        isCancelling || isCancelled -> FeatherIcons.XCircle
        isFailed -> FeatherIcons.AlertCircle
        isWaitingApproval -> FeatherIcons.AlertTriangle
        else -> FeatherIcons.RefreshCw
    }

    val iconColor = when {
        isFailed -> colors.accentDanger
        isWaitingApproval -> colors.accentWarning
        isCancelling || isCancelled -> colors.textMuted
        else -> colors.accentPrimary
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MederiRadius.Card))
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Card)),
        color = colors.surfaceCard,
        shape = RoundedCornerShape(MederiRadius.Card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.SM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(16.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = statusText,
                    style = WorkspaceStyle.Body.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    color = colors.textPrimary
                )
                if (operation.progressTotal != null && operation.progressTotal > 0) {
                    Text(
                        text = "${operation.progressCompleted} / ${operation.progressTotal}",
                        style = WorkspaceStyle.Caption,
                        color = colors.textMuted
                    )
                }
            }

            // R14：RECOVERY_REQUIRED 操作提供真实恢复入口（打开恢复策略面板）
            if (isRecoveryRequired && onRecover != null) {
                Button(
                    onClick = onRecover,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accentWarning,
                        contentColor = colors.onAccentPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Text(stringResource(Res.string.workspace_restore_action), fontSize = 12.sp)
                }
            } else if (isFailed) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accentPrimary,
                        contentColor = colors.onAccentPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Text(stringResource(Res.string.workspace_action_retry), fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = colors.textSecondary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Text(stringResource(Res.string.workspace_action_cancel), fontSize = 12.sp)
                }
            } else if (!isCancelling && !isCancelled && operation.state != WorkspaceOperationState.SUCCEEDED) {
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = colors.textSecondary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Text(stringResource(Res.string.workspace_action_cancel), fontSize = 12.sp)
                }
            }
        }
    }
}
