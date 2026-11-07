package xyz.mederi.ui.appstate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.settings_sandbox_load_failed
import mederi.app.shared.generated.resources.settings_sandbox_save_failed
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.SandboxStatusInfo
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.ExecutionPermissionMode
import xyz.mederi.core.contract.models.SandboxSettings
import xyz.mederi.ui.UiMessage

/** 项目权限的只读 UI 投影。Core 是配置真理源；输入栏和项目设置共享这个投影。 */
internal data class ProjectPermissionState(
    val projectId: String? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val settings: SandboxSettings? = null,
    val settingsReady: Boolean = settings != null,
    val status: SandboxStatusInfo? = null,
    val error: UiMessage? = null,
    /** 请求身份只用于拒绝晚返回，不是配置版本，也不进入持久化。 */
    internal val requestToken: Any? = null,
    internal val refreshAfterSave: Boolean = false,
) {
    val canEdit: Boolean get() = projectId != null && settingsReady && settings != null && !loading && !saving
}

/** AppState 生命周期内每项目一份投影；事件和用户操作使用同一原子状态，不依赖线程恰好相同。 */
internal class ProjectPermissionStore(private val aiCore: AiCore, private val scope: CoroutineScope) {
    private class Entry(projectId: String) {
        val state = MutableStateFlow(ProjectPermissionState(projectId = projectId))
        val readOnly = state.asStateFlow()
    }
    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    private fun entry(projectId: String): Entry {
        entries.value[projectId]?.let { return it }
        val proposed = Entry(projectId)
        return entries.updateAndGet { if (projectId in it) it else it + (projectId to proposed) }.getValue(projectId)
    }
    fun state(projectId: String): StateFlow<ProjectPermissionState> = entry(projectId).readOnly

    init {
        scope.launch {
            aiCore.events().filter { it.type == CoreEventType.SANDBOX_SETTINGS_CHANGED }.collect { event ->
                event.payload["projectId"]?.takeIf { it in entries.value }?.let(::reload)
            }
        }
    }

    fun ensureLoaded(projectId: String) = startRead(projectId, force = false)
    fun reload(projectId: String) = startRead(projectId, force = true)

    private fun startRead(projectId: String, force: Boolean) {
        val entry = entry(projectId)
        val ticket = Any()
        val accepted = entry.state.updateAndGet { old -> when {
            old.saving -> old.copy(refreshAfterSave = true)
            !force && (old.settingsReady || old.loading) -> old
            else -> old.copy(loading = true, settingsReady = false, error = null,
                requestToken = ticket, refreshAfterSave = false)
        } }
        if (accepted.requestToken !== ticket) return
        scope.launch {
            try {
                val settings = aiCore.getSandboxSettings(projectId).getOrThrow()
                entry.state.update { if (it.requestToken === ticket) it.copy(settings = settings, settingsReady = true) else it }
                val status = aiCore.getSandboxStatus(projectId).getOrThrow()
                entry.state.update { if (it.requestToken === ticket) it.copy(
                    loading = false, settings = settings, settingsReady = true, status = status, error = null) else it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                entry.state.update { if (it.requestToken === ticket) it.copy(loading = false,
                    error = UiMessage(Res.string.settings_sandbox_load_failed, listOf(failure.message.orEmpty()))) else it }
            }
        }
    }

    fun selectMode(projectId: String, mode: ExecutionPermissionMode) = update(projectId) { it.copy(mode = mode) }
    fun setExtraPaths(projectId: String, paths: List<String>) = update(projectId) { it.copy(extraWritablePaths = paths) }
    fun setDeniedPaths(projectId: String, paths: List<String>) = update(projectId) { it.copy(deniedPaths = paths) }
    fun setAllowedCommands(projectId: String, commands: List<String>) = update(projectId) { it.copy(allowedCommands = commands) }
    fun setDeniedCommands(projectId: String, commands: List<String>) = update(projectId) { it.copy(deniedCommands = commands) }

    private fun update(projectId: String, transform: (SandboxSettings) -> SandboxSettings) {
        val entry = entry(projectId)
        val current = entry.state.value
        val settings = current.settings ?: return
        if (!current.canEdit) return
        val next = transform(settings)
        if (next == settings) return
        val ticket = Any()
        // 双击或与配置事件并发时，只能有一个操作从同一确认快照取得提交权。
        if (!entry.state.compareAndSet(current, current.copy(saving = true, error = null,
                requestToken = ticket, refreshAfterSave = false))) return
        scope.launch {
            try {
                val saved = aiCore.updateSandboxSettings(projectId, next).getOrThrow()
                entry.state.update { if (it.requestToken === ticket) it.copy(saving = false, settings = saved) else it }
                if (entry.state.value.requestToken === ticket) reload(projectId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                entry.state.update { if (it.requestToken === ticket) it.copy(saving = false,
                    error = UiMessage(Res.string.settings_sandbox_save_failed, listOf(failure.message.orEmpty()))) else it }
                if (entry.state.value.requestToken === ticket && entry.state.value.refreshAfterSave) reload(projectId)
            }
        }
    }
}
