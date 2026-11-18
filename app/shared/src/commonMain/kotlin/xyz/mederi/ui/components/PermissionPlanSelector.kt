package xyz.mederi.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

/** 输入栏显示两个独立菜单，手机设置页直接展示同样的选项。 */
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

@Composable
private fun planApprovalValue(mode: AgentMode): String =
    stringResource(if (mode == AgentMode.APPROVAL) Res.string.plan_approval_manual else Res.string.plan_approval_automatic)

@Composable
internal fun PermissionPlanSelectorContent(
    state: ProjectPermissionState,
    planMode: AgentMode,
    onExecution: (ExecutionPermissionMode) -> Unit,
    onPlan: (AgentMode) -> Unit,
    onReload: () -> Unit,
    onProjectSettings: () -> Unit,
) {
    val sandboxLabel = stringResource(Res.string.composer_sandbox_mode, executionPermissionValue(state))
    val planLabel = stringResource(Res.string.composer_plan_approval_mode, planApprovalValue(planMode))
    PermissionMenu(sandboxLabel, FeatherIcons.Shield) { close ->
        ExecutionPermissionOptions(
            state = state,
            onExecution = { close(); onExecution(it) },
            onReload = { close(); onReload() },
            onProjectSettings = { close(); onProjectSettings() },
        )
    }
    PermissionMenu(planLabel, FeatherIcons.CheckSquare) { close ->
        PlanApprovalOptions(planMode, onPlan = { close(); onPlan(it) })
    }
}

@Composable
private fun PermissionMenu(
    label: String,
    icon: ImageVector,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ContextToolChip(icon = icon, label = label, onClick = { expanded = !expanded })
        WorkspaceDropdownMenu(expanded, { expanded = false }, Modifier.widthIn(max = 300.dp)) {
            content { expanded = false }
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
        ExecutionPermissionOptions(state, onExecution, onReload, onProjectSettings)
        HorizontalDivider(color = colors.divider)
        PlanApprovalOptions(planMode, onPlan)
    }
}

@Composable
private fun ExecutionPermissionOptions(
    state: ProjectPermissionState,
    onExecution: (ExecutionPermissionMode) -> Unit,
    onReload: () -> Unit,
    onProjectSettings: () -> Unit,
) {
    val colors = LocalMederiColors.current
    Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
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
    }
}

@Composable
private fun PlanApprovalOptions(planMode: AgentMode, onPlan: (AgentMode) -> Unit) {
    val colors = LocalMederiColors.current
    Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
        Row(
            modifier = Modifier.padding(horizontal = MederiSpacing.MD, vertical = MederiSpacing.XS),
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FeatherIcons.CheckSquare,
                contentDescription = null,
                tint = colors.textPrimary,
                modifier = Modifier.size(WorkspaceStyle.IconSize)
            )
            Text(
                stringResource(Res.string.composer_plan_approval_title),
                style = WorkspaceStyle.Title,
                color = colors.textPrimary
            )
        }
        Text(stringResource(Res.string.plan_approval_independent_description),
            modifier = Modifier.padding(horizontal = MederiSpacing.MD),
            style = WorkspaceStyle.Caption, color = colors.textMuted)
        listOf(AgentMode.APPROVAL, AgentMode.AUTONOMOUS).forEach { mode ->
            WorkspaceMenuItem(text = {
                Text(planApprovalValue(mode))
            }, selected = planMode == mode, onClick = { onPlan(mode) })
        }
    }
}
