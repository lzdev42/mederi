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
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.viewmodel.compose.viewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Trash2
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.SandboxHooks
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.components.ErrorBoard
import xyz.mederi.ui.components.atoms.*

/** Route仅绑定权威白名单流和状态查询VM。 */
@Composable
fun SandboxSettingsPanel() {
    val appState = LocalAppState.current
    val hooks = appState.aiCore as? SandboxHooks
    val vm: SandboxSettingsViewModel = viewModel { SandboxSettingsViewModel(hooks) }
    LaunchedEffect(vm) { vm.reload() }
    val paths by appState.sandboxExtraPaths.collectAsState()
    SandboxSettingsContent(vm.uiState, paths, vm::reload, { updated ->
        if (hooks != null) appState.setSandboxExtraPaths(updated)
    })
}

/** 纯视图：草稿是短命输入状态，路径列表与宿主状态始终来自调用方。 */
@Composable
internal fun SandboxSettingsContent(
    state: SandboxSettingsUiState,
    paths: List<String>,
    onReload: () -> Unit,
    onPathsChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    var input by remember { mutableStateOf("") }
    val candidate = input.trim()
    val canAdd = state.supported && candidate.isNotEmpty() && candidate !in paths
    val inputLabel = stringResource(Res.string.settings_sandbox_whitelist_placeholder)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.XXL)) {
        SettingsSection(title = when {
            state.loading -> stringResource(Res.string.settings_sandbox_querying)
            state.status == null -> stringResource(Res.string.settings_sandbox_unknown)
            state.status.available -> stringResource(Res.string.settings_sandbox_enabled, state.status.backend)
            else -> stringResource(Res.string.settings_sandbox_unavailable)
        }) {
            if (state.loading) WorkspaceLoadingIndicator(Modifier.size(WorkspaceStyle.IconSize))
            state.error?.let { error ->
                ErrorBoard(errorMessage = stringResource(error.key, *error.args.toTypedArray()),
                    showContinue = state.supported, onContinue = onReload,
                    continueLabel = stringResource(Res.string.settings_sandbox_retry))
            }
            state.status?.let { status ->
                if (status.shell.isNotBlank()) SelectionContainer {
                    Text(stringResource(Res.string.settings_sandbox_shell, status.shell),
                        color = colors.textSecondary, style = WorkspaceStyle.Caption.copy(fontFamily = FontFamily.Monospace))
                }
                if (status.detail.isNotBlank()) SelectionContainer {
                    Text(status.detail, color = colors.textSecondary, style = WorkspaceStyle.Body)
                }
            }
            if (!state.supported) Text(stringResource(Res.string.settings_sandbox_status_unavailable),
                color = colors.textSecondary, style = WorkspaceStyle.Body)
            Text(stringResource(Res.string.settings_sandbox_write_scope), color = colors.textMuted, style = WorkspaceStyle.Caption)
        }
        SettingsSection(title = stringResource(Res.string.settings_sandbox_whitelist_title),
            description = if (state.supported) stringResource(Res.string.settings_sandbox_whitelist_desc) else null) {
            if (!state.supported) Text(stringResource(Res.string.settings_sandbox_remote_hint),
                color = colors.textSecondary, style = WorkspaceStyle.Body)
            // 无宿主hooks时读不到远端白名单，不能把设备偏好冒充服务器配置。
            if (state.supported) {
                if (paths.isEmpty()) Text(stringResource(Res.string.settings_sandbox_whitelist_empty),
                    color = colors.textMuted, style = WorkspaceStyle.Body)
                paths.forEach { path ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
                        SelectionContainer(Modifier.weight(1f).padding(vertical = MederiSpacing.Tight)) {
                            Text(path, color = colors.textPrimary, style = WorkspaceStyle.Body.copy(fontFamily = FontFamily.Monospace))
                        }
                        WorkspaceIconButton(FeatherIcons.Trash2,
                            onClick = { if (state.supported) onPathsChange(paths - path) },
                            contentDescription = stringResource(Res.string.settings_remove), enabled = state.supported)
                    }
                }
                // 输入和动作纵向排列，窄屏/大字体下始终可达。
                WorkspaceTextField(input, { input = it }, modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = inputLabel }, enabled = state.supported,
                    placeholder = inputLabel, monospace = true)
                WorkspaceActionButton(stringResource(Res.string.settings_add), onClick = {
                    if (canAdd) { onPathsChange(paths + candidate); input = "" }
                }, enabled = canAdd, primary = true)
            }
        }
    }
}
