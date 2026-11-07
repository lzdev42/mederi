package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import compose.icons.feathericons.Archive
import compose.icons.feathericons.Folder
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.RecoveryStrategy
import xyz.mederi.core.contract.models.WorkspaceInfo
import xyz.mederi.core.contract.models.WorkspaceKind
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle

/**
 * 工作副本管理面板内容（320dp/280dp 窄屏可用，触控热区 >= 44dp）。
 */
@Composable
fun WorkspaceManagementPanelContent(
    worktrees: List<WorkspaceInfo>,
    onEnterSession: (String) -> Unit,
    onArchiveWorkspace: (String) -> Unit,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MederiRadius.Card))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Card))
            .padding(MederiSpacing.MD),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.workspace_management_title),
                style = WorkspaceStyle.Title.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                color = colors.textPrimary
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(WorkspaceStyle.ControlHeight)
                    .semantics { contentDescription = "Close Management" }
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (errorMessage != null) {
            WorkspaceDialogErrorBanner(errorMessage)
        }

        if (worktrees.isEmpty()) {
            Text(
                text = stringResource(Res.string.workspace_management_empty),
                style = WorkspaceStyle.Body,
                color = colors.textMuted,
                modifier = Modifier.padding(vertical = MederiSpacing.MD)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
            ) {
                worktrees.forEach { info ->
                    val isLocal = info.workspace.kind == WorkspaceKind.LOCAL
                    val kindLabel = if (isLocal) stringResource(Res.string.workspace_kind_local) else stringResource(Res.string.workspace_kind_worktree)
                    val branchLabel = info.branch ?: info.workspace.taskBranchName ?: "HEAD"

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surfaceInput, RoundedCornerShape(MederiRadius.Control))
                            .padding(MederiSpacing.SM),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$kindLabel: $branchLabel",
                                style = WorkspaceStyle.Title,
                                color = colors.textPrimary
                            )
                            Text(
                                text = stringResource(Res.string.workspace_disk_usage_unknown),
                                style = WorkspaceStyle.Caption,
                                color = colors.textMuted
                            )
                        }

                        Text(
                            text = info.workspace.checkoutRoot,
                            style = WorkspaceStyle.Caption,
                            color = colors.textSecondary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            info.workspace.associatedSessionId?.let { sid ->
                                OutlinedButton(
                                    onClick = { onEnterSession(sid) },
                                    modifier = Modifier.heightIn(min = 44.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(stringResource(Res.string.workspace_enter_session))
                                }
                                Spacer(modifier = Modifier.width(MederiSpacing.SM))
                            }
                            Button(
                                onClick = { onArchiveWorkspace(info.workspace.id) },
                                modifier = Modifier.heightIn(min = 44.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accentPrimary,
                                    contentColor = colors.onAccentPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(stringResource(Res.string.workspace_archive_action))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 已归档会话列表内容。
 */
@Composable
fun WorkspaceArchivedListContent(
    archivedConversations: List<Conversation>,
    onRestore: (Conversation) -> Unit,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MederiRadius.Card))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Card))
            .padding(MederiSpacing.MD),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.workspace_archived_list_title),
                style = WorkspaceStyle.Title.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                color = colors.textPrimary
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(WorkspaceStyle.ControlHeight)
                    .semantics { contentDescription = "Close Archived List" }
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (errorMessage != null) {
            WorkspaceDialogErrorBanner(errorMessage)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)
        ) {
            archivedConversations.forEach { conversation ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceInput, RoundedCornerShape(MederiRadius.Control))
                        .padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.SM),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = conversation.title,
                            style = WorkspaceStyle.Body,
                            color = colors.textPrimary
                        )
                        conversation.archivedAt?.let {
                            Text(
                                text = "Archived at: $it",
                                style = WorkspaceStyle.Caption,
                                color = colors.textMuted
                            )
                        }
                    }
                    Button(
                        onClick = { onRestore(conversation) },
                        modifier = Modifier.heightIn(min = 44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accentPrimary,
                            contentColor = colors.onAccentPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(Res.string.workspace_restore_action))
                    }
                }
            }
        }
    }
}

/**
 * 崩溃与故障恢复面板内容。
 */
@Composable
fun WorkspaceRecoveryPanelContent(
    sourcePath: String,
    targetPath: String,
    onKeepCurrent: () -> Unit,
    onRestoreToNew: () -> Unit,
    loading: Boolean = false,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MederiRadius.Card))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Card))
            .padding(MederiSpacing.MD),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.MD)
    ) {
        Text(
            text = stringResource(Res.string.workspace_recovery_title),
            style = WorkspaceStyle.Title.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
            color = colors.textPrimary
        )

        Text(
            text = stringResource(Res.string.workspace_recovery_paths, sourcePath, targetPath),
            style = WorkspaceStyle.Body,
            color = colors.textSecondary
        )

        if (errorMessage != null) {
            WorkspaceDialogErrorBanner(errorMessage)
        }

        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.accentPrimary
                )
                Text(
                    text = stringResource(Res.string.workspace_status_restoring),
                    style = WorkspaceStyle.Caption,
                    color = colors.textSecondary
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onKeepCurrent,
                enabled = !loading,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .padding(end = MederiSpacing.SM),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(stringResource(Res.string.workspace_recovery_keep_current))
            }

            Button(
                onClick = onRestoreToNew,
                enabled = !loading,
                modifier = Modifier.heightIn(min = 44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentPrimary,
                    contentColor = colors.onAccentPrimary
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(stringResource(Res.string.workspace_recovery_restore_new))
            }
        }
    }
}

/**
 * 工作区面板通用错误横幅（错误保持可重试，不阻断面板其余操作）。
 */
@Composable
private fun WorkspaceDialogErrorBanner(errorMessage: String) {
    val colors = LocalMederiColors.current
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

/**
 * 预览失败的对话框内容（R15）：无预览数据可用时以错误态打开面板，提供重试与关闭。
 */
@Composable
fun WorkspaceDialogErrorContent(
    title: String,
    errorMessage: String,
    retryLabel: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MederiRadius.Card))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Card))
            .padding(MederiSpacing.MD),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.MD)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = WorkspaceStyle.Title.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                color = colors.textPrimary
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(WorkspaceStyle.ControlHeight)
                    .semantics { contentDescription = "Close Dialog" }
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        WorkspaceDialogErrorBanner(errorMessage)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(stringResource(Res.string.cancel))
            }
            Spacer(modifier = Modifier.width(MederiSpacing.SM))
            Button(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentPrimary,
                    contentColor = colors.onAccentPrimary
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(retryLabel)
            }
        }
    }
}
