package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.WorkspaceViewModel.EnvironmentEditorState
import xyz.mederi.ui.WorkspaceViewModel.EnvActionRow

/**
 * S13 环境编辑 / 宿主设置 / 建分支 面板组件。
 * 三个独立 Dialog 内容，由 Workspace.kt 按状态挂载。
 */

// ── 环境编辑 Dialog ──

@Composable
fun EnvironmentEditorPanelContent(
    state: EnvironmentEditorState,
    onSave: (name: String, setupScript: String, actions: List<EnvActionRow>, expectedVersion: Long) -> Unit,
    onRunAction: (environmentId: String, actionId: String, version: Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current

    when (state) {
        is EnvironmentEditorState.Error -> {
            WorkspaceDialogErrorContent(
                title = stringResource(Res.string.workspace_env_edit),
                errorMessage = state.message,
                retryLabel = stringResource(Res.string.workspace_dialog_retry),
                onRetry = onDismiss,
                onDismiss = onDismiss
            )
        }
        is EnvironmentEditorState.Editing -> {
            EnvironmentEditorFormContent(
                environmentId = state.environmentId,
                initialName = state.name,
                initialSetupScript = state.setupScript,
                actions = state.actions,
                version = state.version,
                onSave = { name, setup, acts -> onSave(name, setup, acts, state.version) },
                onRunAction = { actionId -> onRunAction(state.environmentId ?: "", actionId, state.version) },
                onDismiss = onDismiss,
                modifier = modifier
            )
        }
        EnvironmentEditorState.None -> Unit
    }
}

@Composable
private fun EnvironmentEditorFormContent(
    environmentId: String?,
    initialName: String,
    initialSetupScript: String,
    actions: List<EnvActionRow>,
    version: Long,
    onSave: (name: String, setupScript: String, actions: List<EnvActionRow>) -> Unit,
    onRunAction: (actionId: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    var name by remember { mutableStateOf(initialName) }
    var setupScript by remember { mutableStateOf(initialSetupScript) }
    var localActions by remember { mutableStateOf(actions) }
    var newActionName by remember { mutableStateOf("") }
    var newActionScript by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MederiRadius.Card))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Card))
            .padding(MederiSpacing.MD),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
    ) {
        // 头部
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.workspace_env_edit),
                style = WorkspaceStyle.Title.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                color = colors.textPrimary
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = "Close" }
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // 可滚动内容
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
        ) {
            // 名称
            Text(
                text = stringResource(Res.string.workspace_env_edit_name),
                style = WorkspaceStyle.Caption,
                color = colors.textMuted
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .semantics { contentDescription = "env_name_field" },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.workspace_env_edit_name)) }
            )

            // Setup 脚本
            Text(
                text = stringResource(Res.string.workspace_env_setup_script),
                style = WorkspaceStyle.Caption,
                color = colors.textMuted
            )
            OutlinedTextField(
                value = setupScript,
                onValueChange = { setupScript = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .semantics { contentDescription = "env_setup_field" }
            )

            // 动作列表
            Text(
                text = stringResource(Res.string.workspace_env_edit_actions),
                style = WorkspaceStyle.Title,
                color = colors.textPrimary
            )
            if (localActions.isEmpty()) {
                Text(
                    text = stringResource(Res.string.workspace_env_edit_no_actions),
                    style = WorkspaceStyle.Caption,
                    color = colors.textMuted
                )
            }
            localActions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceInput, RoundedCornerShape(MederiRadius.Control))
                        .padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.XS),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = action.name,
                        style = WorkspaceStyle.Body,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onRunAction(action.id) },
                        modifier = Modifier.height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accentPrimary,
                            contentColor = colors.onAccentPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(Res.string.workspace_env_run_action), fontSize = 12.sp)
                    }
                }
            }

            // 添加动作
            Text(
                text = stringResource(Res.string.workspace_env_action_name),
                style = WorkspaceStyle.Caption,
                color = colors.textMuted
            )
            OutlinedTextField(
                value = newActionName,
                onValueChange = { newActionName = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                singleLine = true
            )
            Text(
                text = stringResource(Res.string.workspace_env_action_script),
                style = WorkspaceStyle.Caption,
                color = colors.textMuted
            )
            OutlinedTextField(
                value = newActionScript,
                onValueChange = { newActionScript = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
            )
            OutlinedButton(
                onClick = {
                    if (newActionName.isNotBlank()) {
                        localActions = localActions + EnvActionRow(
                            id = "act_${kotlin.uuid.Uuid.random()}",
                            name = newActionName,
                            script = newActionScript
                        )
                        newActionName = ""
                        newActionScript = ""
                    }
                },
                modifier = Modifier.height(44.dp),
                enabled = newActionName.isNotBlank()
            ) {
                Text("+ Action")
            }
        }

        // 底部保存按钮
        HorizontalDivider(color = colors.divider)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = { onSave(name, setupScript, localActions) },
                modifier = Modifier.height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentPrimary,
                    contentColor = colors.onAccentPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(Res.string.workspace_env_save))
            }
        }
    }
}

// ── 宿主设置 Dialog ──

@Composable
fun WorktreeSettingsPanelContent(
    rootPath: String,
    max: Int,
    autoCleanup: Boolean,
    errorMessage: String?,
    onSave: (rootPath: String, max: Int, autoCleanup: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    var path by remember { mutableStateOf(rootPath) }
    var maxVal by remember { mutableStateOf(max.toString()) }
    var cleanup by remember { mutableStateOf(autoCleanup) }

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
                text = stringResource(Res.string.workspace_worktree_settings),
                style = WorkspaceStyle.Title.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                color = colors.textPrimary
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = "Close" }
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
            Text(
                text = stringResource(Res.string.workspace_worktree_root),
                style = WorkspaceStyle.Caption,
                color = colors.textMuted
            )
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .semantics { contentDescription = "worktree_root_field" },
                singleLine = true
            )

            Text(
                text = stringResource(Res.string.workspace_worktree_max),
                style = WorkspaceStyle.Caption,
                color = colors.textMuted
            )
            OutlinedTextField(
                value = maxVal,
                onValueChange = { maxVal = it.filter { c -> c.isDigit() } },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .semantics { contentDescription = "worktree_max_field" },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(MederiRadius.Control))
                    .clickable { cleanup = !cleanup }
                    .padding(vertical = MederiSpacing.XS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
            ) {
                Checkbox(
                    checked = cleanup,
                    onCheckedChange = { cleanup = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.accentPrimary,
                        checkmarkColor = colors.onAccentPrimary
                    )
                )
                Text(
                    text = stringResource(Res.string.workspace_worktree_autocleanup),
                    style = WorkspaceStyle.Body,
                    color = colors.textPrimary
                )
            }
        }

        if (errorMessage != null) {
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
                    text = errorMessage,
                    style = WorkspaceStyle.Caption,
                    color = colors.accentDanger,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider(color = colors.divider)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = {
                    val parsedMax = maxVal.toIntOrNull() ?: 15
                    onSave(path, parsedMax, cleanup)
                },
                modifier = Modifier.height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentPrimary,
                    contentColor = colors.onAccentPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(Res.string.workspace_host_settings_save))
            }
        }
    }
}

// ── 建分支 Dialog ──

@Composable
fun CreateBranchPanelContent(
    errorMessage: String?,
    onCreate: (name: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    var branchName by remember { mutableStateOf("") }

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
                text = stringResource(Res.string.workspace_create_branch_title),
                style = WorkspaceStyle.Title.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                color = colors.textPrimary
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = "Close" }
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Text(
            text = stringResource(Res.string.workspace_create_branch_name),
            style = WorkspaceStyle.Caption,
            color = colors.textMuted
        )
        OutlinedTextField(
            value = branchName,
            onValueChange = { branchName = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .semantics { contentDescription = "branch_name_field" },
            singleLine = true,
            placeholder = { Text(stringResource(Res.string.workspace_create_branch_name)) }
        )

        if (errorMessage != null) {
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
                    text = errorMessage,
                    style = WorkspaceStyle.Caption,
                    color = colors.accentDanger,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider(color = colors.divider)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = { onCreate(branchName) },
                modifier = Modifier.height(44.dp),
                enabled = branchName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentPrimary,
                    contentColor = colors.onAccentPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(Res.string.workspace_create_branch_create))
            }
        }
    }
}
