package xyz.mederi.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import xyz.mederi.core.contract.models.ExecutionPermissionMode
import xyz.mederi.ui.appstate.ProjectPermissionState
import xyz.mederi.ui.appstate.ProjectPermissionStore

internal typealias SandboxSettingsUiState = ProjectPermissionState

/** 只绑定页面目标，不保存另一份可写配置；输入栏与此页均使用 AppState 的同一个 store。 */
internal class SandboxSettingsViewModel(private val store: ProjectPermissionStore) : ViewModel() {
    var uiState by mutableStateOf(SandboxSettingsUiState())
        private set
    private var observation: Job? = null

    fun bindProject(projectId: String?) {
        if (uiState.projectId == projectId) return
        observation?.cancel()
        uiState = projectId?.let { store.state(it).value } ?: SandboxSettingsUiState()
        if (projectId != null) {
            store.ensureLoaded(projectId)
            observation = viewModelScope.launch {
                store.state(projectId).collect { state ->
                    if (uiState.projectId == projectId) uiState = state
                }
            }
        }
    }
    fun reload() { uiState.projectId?.let(store::reload) }
    fun selectMode(mode: ExecutionPermissionMode) { uiState.projectId?.let { store.selectMode(it, mode) } }
    fun setExtraPaths(paths: List<String>) { uiState.projectId?.let { store.setExtraPaths(it, paths) } }
    fun setDeniedPaths(paths: List<String>) { uiState.projectId?.let { store.setDeniedPaths(it, paths) } }
    fun setAllowedCommands(commands: List<String>) { uiState.projectId?.let { store.setAllowedCommands(it, commands) } }
    fun setDeniedCommands(commands: List<String>) { uiState.projectId?.let { store.setDeniedCommands(it, commands) } }
}
