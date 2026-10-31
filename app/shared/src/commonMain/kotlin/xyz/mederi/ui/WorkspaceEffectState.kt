package xyz.mederi.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.Flow

/** 窗口Route拥有的短命表现状态。只有Route消费命令，叶子仅读状态/发关闭回调。 */
@Stable
internal class WorkspaceEffectState {
    var settingsVisible by mutableStateOf(false)
        private set
    var projectMenuExpanded by mutableStateOf(false)
        private set
    var compactionNotice by mutableStateOf<UiEffect.ShowCompactionNotice?>(null)
        private set

    fun accept(effect: UiEffect) {
        when (effect) {
            UiEffect.OpenSettings -> settingsVisible = true
            UiEffect.OpenProjectMenu -> projectMenuExpanded = true
            is UiEffect.ShowCompactionNotice -> compactionNotice = effect
        }
    }

    fun dismissSettings() { settingsVisible = false }
    fun updateProjectMenuExpanded(expanded: Boolean) { projectMenuExpanded = expanded }
    fun dismissCompactionNotice() { compactionNotice = null }
}

/** 应在窗口Route装配一次；Flow保持原Channel单消费语义，不广播、不按会话重启。 */
@Composable
internal fun rememberWorkspaceEffectState(effects: Flow<UiEffect>): WorkspaceEffectState {
    val state = remember(effects) { WorkspaceEffectState() }
    LaunchedEffect(effects) { effects.collect(state::accept) }
    return state
}
