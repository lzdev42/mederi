package xyz.mederi.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.settings_sandbox_load_failed
import xyz.mederi.core.contract.SandboxHooks
import xyz.mederi.core.contract.SandboxStatusInfo
import xyz.mederi.ui.UiMessage

/** 仅查询宿主状态；白名单继续由AppState流负责，不在VM保存副本。 */
internal data class SandboxSettingsUiState(
    val supported: Boolean,
    val loading: Boolean = false,
    val status: SandboxStatusInfo? = null,
    val error: UiMessage? = null,
)

internal class SandboxSettingsViewModel(private val hooks: SandboxHooks?) : ViewModel() {
    var uiState by mutableStateOf(SandboxSettingsUiState(supported = hooks != null))
        private set

    fun reload() {
        val source = hooks ?: return
        if (uiState.loading) return
        uiState = uiState.copy(loading = true, status = null, error = null)
        viewModelScope.launch {
            try {
                val status = withContext(Dispatchers.Default) { source.sandboxStatus() }
                uiState = uiState.copy(loading = false, status = status)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                uiState = uiState.copy(loading = false, status = null,
                    error = UiMessage(Res.string.settings_sandbox_load_failed, listOf(failure.message.orEmpty())))
            }
        }
    }
}
