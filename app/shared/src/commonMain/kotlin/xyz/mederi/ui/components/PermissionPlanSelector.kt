package xyz.mederi.ui.components

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.Shield
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.AgentMode
import xyz.mederi.core.contract.models.ExecutionPermissionMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.appstate.ProjectPermissionState
import xyz.mederi.ui.resolvePermissionProjectId
import xyz.mederi.ui.components.atoms.*

/** 输入栏只保留一个图标；手机设置页直接展开同一组内容，不再嵌套菜单。 */
@Composable
internal fun PermissionPlanSelector(viewModel: WorkspaceViewModel, inline: Boolean = false) {
    val selectedProjectId by viewModel.selectedProjectId.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val planMode by viewModel.selectedAgentMode.collectAsState()
    val projectId = resolvePermissionProjectId(viewModel.conversationId, viewModel.snapshot?.conversation, projects, selectedProjectId)
    val store = viewModel.projectPermissionStore
    key(projectId) {
        val state = if (projectId == null || projects.none { it.id == projectId }) ProjectPermissionState()
            else {
                val current by remember(store, projectId) { store.state(projectId) }.collectAsState()
                LaunchedEffect(store, projectId) { store.ensureLoaded(projectId) }
                current
            }
        val onExecution: (ExecutionPermissionMode) -> Unit = { mode -> projectId?.let { store.selectMode(it, mode) } }
        val reload: () -> Unit = { projectId?.let(store::reload) }
        val settings: () -> Unit = { projectId?.let(viewModel::openProjectSettings) }
        if (inline) PermissionPlanSettingsContent(state, planMode, onExecution, viewModel::selectAgentMode, reload, settings)
        else PermissionPlanSelectorContent(state, planMode, onExecution, viewModel::selectAgentMode, reload, settings)
    }
}

@Composable
private fun executionPermissionValue(state: ProjectPermissionState): String = when {
    state.projectId == null -> stringResource(Res.string.execution_permission_select_project)
    state.loading || state.saving -> stringResource(Res.string.settings_sandbox_querying)
    !state.settingsReady || state.settings == null -> stringResource(Res.string.settings_sandbox_unknown)
    state.settings.mode == ExecutionPermissionMode.FULL_ACCESS -> stringResource(Res.string.settings_sandbox_full_access)
    else -> stringResource(Res.string.settings_sandbox_needs_approval)
}

/** 图标的可访问名称/悬停提示包含两个当前状态；常驻工具条不平铺说明文字。 */
@Composable
internal fun PermissionPlanSelectorContent(
    state: ProjectPermissionState,
    planMode: AgentMode,
    onExecution: (ExecutionPermissionMode) -> Unit,
    onPlan: (AgentMode) -> Unit,
    onReload: () -> Unit,
    onProjectSettings: () -> Unit,
) {
    var expanded by remember(state.projectId) { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val label = stringResource(Res.string.permission_plan_button_description, executionPermissionValue(state),
        stringResource(if (planMode == AgentMode.AUTONOMOUS) Res.string.mode_auto_approve else Res.string.mode_manual_approve))
    Box(Modifier.hoverable(source)) {
        WorkspaceIconButton(FeatherIcons.Shield, { expanded = !expanded }, label,
            modifier = Modifier.onFocusChanged { focused = it.hasFocus })
        if ((hovered || focused) && !expanded) WorkspaceTooltipPopup(label, onDismiss = { focused = false })
        WorkspaceDropdownMenu(expanded, { expanded = false }) {
            PermissionPlanSettingsContent(state, planMode,
                onExecution = { expanded = false; onExecution(it) },
                onPlan = { expanded = false; onPlan(it) },
                onReload = { expanded = false; onReload() },
                onProjectSettings = { expanded = false; onProjectSettings() },
                modifier = Modifier.widthIn(max = 300.dp))
        }
    }
}

/** 两个独立组共享同一内容。菜单和手机 sheet 均直接使用这些操作，不保存本地选择副本。 */
@Composable
internal fun PermissionPlanSettingsContent(
    state: ProjectPermissionState,
    planMode: AgentMode,
    onExecution: (ExecutionPermissionMode) -> Unit,
    onPlan: (AgentMode) -> Unit,
    onReload: () -> Unit,
    onProjectSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
        Text(stringResource(Res.string.settings_sandbox_permissions_title),
            modifier = Modifier.padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.XS),
            style = WorkspaceStyle.Title, color = colors.textPrimary)
        Text(stringResource(Res.string.execution_permission_project_scope),
            modifier = Modifier.padding(horizontal = MederiSpacing.MD),
            style = WorkspaceStyle.Caption, color = colors.textMuted)
        if (state.canEdit) {
            ExecutionPermissionMode.entries.forEach { mode ->
                WorkspaceMenuItem(text = {
                    Column {
                        Text(stringResource(if (mode == ExecutionPermissionMode.NEEDS_APPROVAL)
                            Res.string.settings_sandbox_needs_approval else Res.string.settings_sandbox_full_access))
                        Text(stringResource(if (mode == ExecutionPermissionMode.NEEDS_APPROVAL)
                            Res.string.execution_permission_approval_summary else Res.string.execution_permission_full_summary),
                            style = WorkspaceStyle.Caption, color = colors.textMuted)
                    }
                }, selected = state.settings?.mode == mode, twoLines = true, onClick = { onExecution(mode) })
            }
        } else Text(executionPermissionValue(state), color = colors.textSecondary, style = WorkspaceStyle.Body,
            modifier = Modifier.padding(MederiSpacing.MD))
        state.error?.let { error ->
            Text(stringResource(error.key, *error.args.toTypedArray()), style = WorkspaceStyle.Caption,
                color = colors.accentDanger, modifier = Modifier.padding(MederiSpacing.MD))
        }
        if (state.projectId != null) {
            if (!state.loading && !state.saving && !state.settingsReady) WorkspaceMenuItem(
                text = { Text(stringResource(Res.string.settings_sandbox_retry)) }, onClick = onReload)
            WorkspaceMenuItem(text = { Text(stringResource(Res.string.project_settings_title)) }, onClick = onProjectSettings)
        }
        HorizontalDivider(color = colors.divider)
        Row(
            modifier = Modifier.padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.XS),
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FeatherIcons.CheckSquare,
                contentDescription = null,
                tint = colors.textPrimary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                stringResource(Res.string.plan_approval_settings_title),
                style = WorkspaceStyle.Title,
                color = colors.textPrimary
            )
        }
        Text(stringResource(Res.string.plan_approval_independent_description),
            modifier = Modifier.padding(horizontal = MederiSpacing.MD),
            style = WorkspaceStyle.Caption, color = colors.textMuted)
        listOf(AgentMode.AUTONOMOUS, AgentMode.APPROVAL).forEach { mode ->
            WorkspaceMenuItem(text = {
                Text(stringResource(if (mode == AgentMode.AUTONOMOUS) Res.string.mode_auto_approve else Res.string.mode_manual_approve))
            }, selected = planMode == mode, onClick = { onPlan(mode) })
        }
    }
}
