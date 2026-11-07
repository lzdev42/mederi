package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.ArrowRight
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.HandoffPreview
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.CopyButton

/**
 * Handoff 迁移面板内容。
 * 支持桌面 Dialog 与手机 320dp/280dp 窄屏全屏/BottomSheet 展示。
 * 关键动作位于底部，内容可独立垂直滚动；禁止提供针对 dirty 目标的强制覆盖按钮。
 */
@Composable
fun HandoffPanelContent(
    preview: HandoffPreview,
    stopAffectedTasks: Boolean,
    onToggleStopAffectedTasks: (Boolean) -> Unit,
    onExecuteHandoff: () -> Unit,
    onRefreshPreview: () -> Unit,
    onDismiss: () -> Unit,
    loading: Boolean = false,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    val hasConflicts = preview.conflicts.isNotEmpty()
    val isTargetDirty = preview.target.dirty
    val canExecute = !isTargetDirty && !hasConflicts && !loading

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MederiRadius.Card))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Card))
            .padding(MederiSpacing.MD),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
    ) {
        // 头部：标题与关闭按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.handoff_dialog_title),
                style = WorkspaceStyle.Title.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                color = colors.textPrimary
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(WorkspaceStyle.ControlHeight)
                    .semantics { contentDescription = "Close Handoff" }
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // 可滚动内容区域
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
        ) {
            // 路径展示：源 -> 目标
            Text(
                text = stringResource(Res.string.handoff_source_path),
                style = WorkspaceStyle.Caption,
                color = colors.textMuted
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceInput, RoundedCornerShape(MederiRadius.Control))
                    .padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.XS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = preview.source.workspace.checkoutRoot,
                    style = WorkspaceStyle.Caption,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                CopyButton(preview.source.workspace.checkoutRoot)
            }

            Text(
                text = stringResource(Res.string.handoff_target_path),
                style = WorkspaceStyle.Caption,
                color = colors.textMuted
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceInput, RoundedCornerShape(MederiRadius.Control))
                    .padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.XS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = preview.target.workspace.checkoutRoot,
                    style = WorkspaceStyle.Caption,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                CopyButton(preview.target.workspace.checkoutRoot)
            }

            // 分支与变更摘要
            Text(
                text = stringResource(Res.string.handoff_base_task_branch, preview.taskBranch),
                style = WorkspaceStyle.Body,
                color = colors.textPrimary
            )
            Text(
                text = stringResource(
                    Res.string.handoff_changes_summary,
                    preview.fingerprints.size,
                    if (preview.source.dirty) 1 else 0,
                    0
                ),
                style = WorkspaceStyle.Caption,
                color = colors.textSecondary
            )

            // 受影响运行中的任务
            if (preview.affectedSessions.isNotEmpty()) {
                HorizontalDivider(color = colors.divider)
                Text(
                    text = stringResource(Res.string.handoff_affected_sessions_title),
                    style = WorkspaceStyle.Title,
                    color = colors.textPrimary
                )
                preview.affectedSessions.forEach { session ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS)
                    ) {
                        Text(
                            text = session.title,
                            style = WorkspaceStyle.Body,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        if (session.running) {
                            Text(
                                text = "Running",
                                style = WorkspaceStyle.Caption,
                                color = colors.accentWarning
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MederiRadius.Control))
                        .clickable { onToggleStopAffectedTasks(!stopAffectedTasks) }
                        .padding(vertical = MederiSpacing.XS),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
                ) {
                    Checkbox(
                        checked = stopAffectedTasks,
                        onCheckedChange = { onToggleStopAffectedTasks(it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = colors.accentPrimary,
                            checkmarkColor = colors.onAccentPrimary
                        )
                    )
                    Text(
                        text = stringResource(Res.string.handoff_stop_affected_tasks),
                        style = WorkspaceStyle.Body,
                        color = colors.textPrimary
                    )
                }
            }

            // 冲突与脏目标警告
            if (isTargetDirty) {
                HorizontalDivider(color = colors.divider)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.accentDanger.copy(alpha = 0.1f), RoundedCornerShape(MederiRadius.Control))
                        .padding(MederiSpacing.SM),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
                ) {
                    Icon(
                        imageVector = FeatherIcons.AlertTriangle,
                        contentDescription = null,
                        tint = colors.accentDanger,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(Res.string.handoff_target_dirty_warning),
                        style = WorkspaceStyle.Caption,
                        color = colors.accentDanger
                    )
                }
            }

            if (hasConflicts) {
                HorizontalDivider(color = colors.divider)
                Text(
                    text = stringResource(Res.string.handoff_conflicts_title),
                    style = WorkspaceStyle.Title,
                    color = colors.accentDanger
                )
                preview.conflicts.forEach { conflict ->
                    Text(
                        text = "${conflict.path} (${conflict.reason})",
                        style = WorkspaceStyle.Caption,
                        color = colors.accentDanger,
                        modifier = Modifier.padding(start = MederiSpacing.SM)
                    )
                }
            }
        }

        // R15：提交/预览失败的错误回显（保留可重试状态，执行按钮不置灰）
        if (errorMessage != null) {
            HorizontalDivider(color = colors.divider)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.accentDanger.copy(alpha = 0.1f), RoundedCornerShape(MederiRadius.Control))
                    .padding(MederiSpacing.SM),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
            ) {
                Icon(
                    imageVector = FeatherIcons.AlertTriangle,
                    contentDescription = null,
                    tint = colors.accentDanger,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(Res.string.workspace_dialog_error, errorMessage),
                    style = WorkspaceStyle.Caption,
                    color = colors.accentDanger,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 底部动作行（手机上触控热区 >= 44dp）
        HorizontalDivider(color = colors.divider)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.accentPrimary
                )
                Spacer(modifier = Modifier.width(MederiSpacing.SM))
                Text(
                    text = stringResource(Res.string.handoff_executing),
                    style = WorkspaceStyle.Caption,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.width(MederiSpacing.MD))
            }

            OutlinedButton(
                onClick = onRefreshPreview,
                enabled = !loading,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .padding(end = MederiSpacing.SM),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.RefreshCw,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(Res.string.handoff_refresh_preview))
            }

            Button(
                onClick = onExecuteHandoff,
                enabled = canExecute,
                modifier = Modifier.heightIn(min = 44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentPrimary,
                    contentColor = colors.onAccentPrimary,
                    disabledContainerColor = colors.surfaceHover,
                    disabledContentColor = colors.textMuted
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(Res.string.handoff_execute_action))
            }
        }
    }
}
