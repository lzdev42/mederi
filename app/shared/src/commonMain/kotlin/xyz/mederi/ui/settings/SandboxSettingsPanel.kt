package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.viewmodel.compose.viewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Trash2
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ExecutionPermissionMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.components.ErrorBoard
import xyz.mederi.ui.resolvePermissionProjectId
import xyz.mederi.ui.components.atoms.*

/** 可嵌入全局设置或项目独立入口；显式目标优先，不切换正在聊天的项目。 */
@Composable
fun SandboxSettingsPanel(projectId: String? = null, modifier: Modifier = Modifier) {
    val appState = LocalAppState.current
    val vm: SandboxSettingsViewModel = viewModel(key = "project-permissions-${projectId.orEmpty()}") {
        SandboxSettingsViewModel(appState.projectPermissionStore)
    }
    val projects by appState.projects.collectAsState()
    val selectedProjectId by appState.selectedProjectId.collectAsState()
    val selectedConversationId by appState.selectedConversationId.collectAsState()
    val target = projectId ?: resolvePermissionProjectId(selectedConversationId, null, projects, selectedProjectId)
    val project = projects.firstOrNull { it.id == target }
    LaunchedEffect(vm, project?.id) { vm.bindProject(project?.id) }
    val state = vm.uiState.takeIf { it.projectId == project?.id } ?: SandboxSettingsUiState(projectId = project?.id)
    SandboxSettingsContent(state, vm::reload, vm::selectMode, vm::setExtraPaths, modifier,
        projectName = project?.name,
        onDeniedPathsChange = vm::setDeniedPaths,
        onAllowedCommandsChange = vm::setAllowedCommands,
        onDeniedCommandsChange = vm::setDeniedCommands)
}

/** 纯视图：配置来自同一 store 的 core 投影，只有未提交草稿属于 View。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SandboxSettingsContent(
    state: SandboxSettingsUiState,
    onReload: () -> Unit,
    onModeChange: (ExecutionPermissionMode) -> Unit,
    onPathsChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    projectName: String? = null,
    onDeniedPathsChange: (List<String>) -> Unit = {},
    onAllowedCommandsChange: (List<String>) -> Unit = {},
    onDeniedCommandsChange: (List<String>) -> Unit = {},
) {
    val colors = LocalMederiColors.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.XXL)) {
        if (state.projectId == null) {
            Text(stringResource(Res.string.settings_project_select_first), color = colors.textSecondary, style = WorkspaceStyle.Body)
            return@Column
        }
        Text(stringResource(Res.string.settings_project_name, projectName ?: state.projectId),
            color = colors.textPrimary, style = WorkspaceStyle.Title)
        SettingsSection(title = stringResource(Res.string.settings_sandbox_permissions_title),
            description = stringResource(Res.string.settings_sandbox_permissions_scope, projectName ?: state.projectId)) {
            state.settings?.let { settings ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
                    verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
                    ExecutionPermissionMode.entries.forEach { mode ->
                        WorkspaceActionButton(
                            text = stringResource(if (mode == ExecutionPermissionMode.NEEDS_APPROVAL)
                                Res.string.settings_sandbox_needs_approval else Res.string.settings_sandbox_full_access),
                            onClick = { onModeChange(mode) }, enabled = state.canEdit,
                            primary = settings.mode == mode,
                            modifier = Modifier.semantics { selected = settings.mode == mode })
                    }
                }
                Text(stringResource(if (settings.mode == ExecutionPermissionMode.NEEDS_APPROVAL)
                    Res.string.settings_sandbox_needs_approval_desc else Res.string.settings_sandbox_full_access_desc),
                    color = colors.textSecondary, style = WorkspaceStyle.Body)
            }
            if (state.saving) WorkspaceLoadingIndicator(Modifier.size(WorkspaceStyle.IconSize))
        }
        state.error?.let { error ->
            ErrorBoard(errorMessage = stringResource(error.key, *error.args.toTypedArray()),
                showContinue = !state.saving, onContinue = onReload,
                continueLabel = stringResource(Res.string.settings_sandbox_retry))
        }
        SettingsSection(title = when {
            state.loading -> stringResource(Res.string.settings_sandbox_querying)
            state.settings?.mode == ExecutionPermissionMode.FULL_ACCESS -> stringResource(Res.string.settings_sandbox_bypassed)
            state.status == null -> stringResource(Res.string.settings_sandbox_unknown)
            state.status.available -> stringResource(Res.string.settings_sandbox_enabled, state.status.backend)
            else -> stringResource(Res.string.settings_sandbox_unavailable)
        }) {
            if (state.loading) WorkspaceLoadingIndicator(Modifier.size(WorkspaceStyle.IconSize))
            state.status?.let { status ->
                if (status.shell.isNotBlank()) SelectionContainer {
                    Text(stringResource(Res.string.settings_sandbox_shell, status.shell),
                        color = colors.textSecondary, style = WorkspaceStyle.Caption.copy(fontFamily = FontFamily.Monospace))
                }
                if (status.detail.isNotBlank()) SelectionContainer {
                    Text(status.detail, color = colors.textSecondary, style = WorkspaceStyle.Body)
                }
            }
        }
        state.settings?.let { settings ->
            PermissionRuleListEditor(state.projectId, settings.extraWritablePaths,
                stringResource(Res.string.settings_sandbox_whitelist_title),
                stringResource(Res.string.settings_sandbox_whitelist_desc),
                stringResource(Res.string.settings_sandbox_whitelist_placeholder), state.canEdit, state.error != null, onPathsChange)
            PermissionRuleListEditor(state.projectId, settings.deniedPaths,
                stringResource(Res.string.settings_sandbox_denied_paths_title),
                stringResource(Res.string.settings_sandbox_denied_paths_desc),
                stringResource(Res.string.settings_sandbox_denied_paths_placeholder), state.canEdit, state.error != null, onDeniedPathsChange)
            PermissionRuleListEditor(state.projectId, settings.allowedCommands,
                stringResource(Res.string.settings_sandbox_allowed_commands_title),
                stringResource(Res.string.settings_sandbox_allowed_commands_desc),
                stringResource(Res.string.settings_sandbox_allowed_commands_placeholder), state.canEdit, state.error != null, onAllowedCommandsChange)
            PermissionRuleListEditor(state.projectId, settings.deniedCommands,
                stringResource(Res.string.settings_sandbox_denied_commands_title),
                stringResource(Res.string.settings_sandbox_denied_commands_desc),
                stringResource(Res.string.settings_sandbox_denied_commands_placeholder), state.canEdit, state.error != null, onDeniedCommandsChange)
        }
    }
}

/** 四类规则共用可达的列表编辑器；草稿按项目和字段身份隔离。 */
@Composable
internal fun PermissionRuleListEditor(
    projectId: String,
    values: List<String>,
    title: String,
    description: String,
    placeholder: String,
    enabled: Boolean,
    failed: Boolean,
    onChange: (List<String>) -> Unit,
) {
    val colors = LocalMederiColors.current
    var input by remember(projectId, placeholder) { mutableStateOf("") }
    var submittedCandidate by remember(projectId, placeholder) { mutableStateOf<String?>(null) }
    val candidate = input.trim()
    LaunchedEffect(values, submittedCandidate) {
        val submitted = submittedCandidate
        if (submitted != null && submitted in values) {
            if (input.trim() == submitted) input = ""
            submittedCandidate = null
        }
    }
    LaunchedEffect(failed) { if (failed) submittedCandidate = null }
    SettingsSection(title = title, description = description) {
        if (values.isEmpty()) Text(stringResource(Res.string.settings_permission_rules_empty),
            color = colors.textMuted, style = WorkspaceStyle.Body)
        values.forEach { value ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
                SelectionContainer(Modifier.weight(1f).padding(vertical = MederiSpacing.Tight)) {
                    Text(value, color = colors.textPrimary, style = WorkspaceStyle.Body.copy(fontFamily = FontFamily.Monospace))
                }
                WorkspaceIconButton(FeatherIcons.Trash2, { onChange(values - value) },
                    stringResource(Res.string.settings_remove), enabled = enabled)
            }
        }
        WorkspaceTextField(input, { input = it }, modifier = Modifier.fillMaxWidth()
            .semantics { contentDescription = placeholder }, enabled = enabled, placeholder = placeholder, monospace = true)
        WorkspaceActionButton(stringResource(Res.string.settings_add), onClick = {
            if (enabled && candidate.isNotEmpty() && candidate !in values) {
                submittedCandidate = candidate
                onChange(values + candidate)
            }
        }, enabled = enabled && candidate.isNotEmpty() && candidate !in values, primary = true)
    }
}
