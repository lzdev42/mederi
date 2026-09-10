package xyz.mederi.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mederi.core.contract.TerminalManager
import xyz.mederi.core.contract.TerminalSession
import xyz.mederi.core.contract.models.Project
import xyz.mederi.core.ui.appstate.AppState

/**
 * 终端面板 ViewModel：多 tab 终端的会话视图状态机（tab / 未读 / 错误 / 结束标记）。
 *
 * - tab = 会话视图；会话归 [TerminalManager]（hub）所有，这里只持有视图状态
 * - key 约定：`"project:<id>"`（项目主 tab）/ `"project:<id>#<n>"`（同项目多 shell）/
 *   `"tmp:<n>"`（无项目手动新建）
 * - 经 ViewModelStore 管理（窗口级）：dock 面板关闭重开不再销毁重建全部 tab 结构
 * - 关 tab = kill 会话；会话自行退出则标灰 + 可重开；非激活 tab 有新输出 → 未读点
 */
class TerminalViewModel(
    private val appState: AppState,
) : ViewModel() {

    /** null = 当前端无 pty 终端能力（wasm/移动遥控端），UI 渲染占位 */
    val manager: TerminalManager? get() = appState.terminalManager

    private val _tabKeys = mutableStateListOf<String>()

    /** 全部 tab key（快照状态：组合期间读取即建立订阅） */
    val tabKeys: List<String> get() = _tabKeys

    var activeKey by mutableStateOf(""); private set

    private val sessions = mutableStateMapOf<String, TerminalSession>()
    private val errors = mutableStateMapOf<String, String>()
    private val unread = mutableStateMapOf<String, Boolean>()
    private val ended = mutableStateMapOf<String, Boolean>()

    /** 每个 tab 的 watcher 协程（关闭 tab 时取消，避免泄漏） */
    private val watchers = mutableMapOf<String, List<Job>>()

    fun sessionOf(key: String): TerminalSession? = sessions[key]

    /** 会话标题（可能为 hub 写入的自定义标题；tab 显示名经 [terminalTabTitle] 组装） */
    fun sessionTitleOf(key: String): String? = sessions[key]?.title

    fun errorOf(key: String): String? = errors[key]
    fun isUnread(key: String): Boolean = unread[key] == true
    fun isEnded(key: String): Boolean = ended[key] == true

    /** 选中 tab：更新激活态、清未读，并兜底拉起缺失会话 */
    fun selectTab(key: String) {
        activeKey = key
        unread.remove(key)
        ensureSession(key)
    }

    /** 兜底：激活 tab 无会话且无错误（初始 tab / 重试清空后）→ 拉起 */
    fun ensureSession(key: String) {
        if (key.isEmpty() || sessions.containsKey(key) || errors.containsKey(key)) return
        val projects = appState.projects.value
        launchSession(key, terminalCwdOf(key, projects), terminalTabTitle(key, null, projects))
    }

    fun closeTab(key: String) {
        watchers.remove(key)?.forEach { it.cancel() }
        sessions[key]?.kill()
        sessions.remove(key)
        errors.remove(key)
        unread.remove(key)
        ended.remove(key)
        val index = _tabKeys.indexOf(key)
        if (index >= 0) _tabKeys.removeAt(index)
        if (activeKey == key) activeKey = _tabKeys.lastOrNull() ?: ""
    }

    /** + 新建：总是开新 shell（有项目 → 同 cwd 多 shell #n；无项目 → tmp:N） */
    fun addTab() {
        val projects = appState.projects.value
        val key = nextTabKey()
        _tabKeys.add(key)
        activeKey = key
        launchSession(key, terminalCwdOf(key, projects), terminalTabTitle(key, null, projects))
    }

    /** 会话结束后重开：杀干净旧 watcher/会话残留再拉起 */
    fun restartTab(key: String) {
        watchers.remove(key)?.forEach { it.cancel() }
        sessions.remove(key)
        ended.remove(key)
        ensureSession(key)
    }

    /** 启动失败后重试：清错误标记再拉起（ensureSession 会因错误标记存在而跳过） */
    fun retryTab(key: String) {
        errors.remove(key)
        ensureSession(key)
    }

    /**
     * 首次打开且零 tab 时自动开一个（项目 → 项目 cwd；无项目 → home）。
     * 幂等：仅生命周期内第一次生效，用户手动关光 tab 后不再自动补。
     */
    fun bootstrapIfNeeded(project: Project?) {
        if (bootstrapped || _tabKeys.isNotEmpty()) return
        bootstrapped = true
        val key = project?.let { "project:${it.id}" } ?: "tmp:1"
        _tabKeys.add(key)
        activeKey = key
        launchSession(key, project?.directories?.firstOrNull(), project?.name ?: "本地终端")
    }

    private var bootstrapped = false

    // 拉起会话（hub 侧同 key 幂等复用）；pty fork 挪到后台线程（commonMain 用 Default，wasm 无 IO）
    private fun launchSession(key: String, cwd: String?, title: String) {
        val manager = manager ?: return
        errors.remove(key)
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.Default) {
                    manager.getOrCreate(key, cwd, title)
                }
                sessions[key] = session
                watchSession(key, session)
            } catch (e: Exception) {
                errors[key] = e.message ?: "终端启动失败"
            }
        }
    }

    // 每个 tab 后台 watcher：输出 → 未读标记；会话退出 → ended 标记。
    // hub 的读线程始终自持 scrollback，watcher 只影响 UI 标记，不影响数据完整性
    private fun watchSession(key: String, session: TerminalSession) {
        watchers[key] = listOf(
            viewModelScope.launch {
                session.output().collect { if (key != activeKey) unread[key] = true }
            },
            viewModelScope.launch {
                session.isRunning.collect { running -> if (!running) ended[key] = true }
            }
        )
    }

    private fun nextTabKey(): String {
        val projects = appState.projects.value
        val project = appState.selectedProjectId.value?.let { id -> projects.find { it.id == id } }
        if (project != null) {
            val mainKey = "project:${project.id}"
            var n = 2
            while (_tabKeys.contains("$mainKey#$n")) n++
            return if (_tabKeys.contains(mainKey)) "$mainKey#$n" else mainKey
        }
        var n = 1
        while (_tabKeys.contains("tmp:$n")) n++
        return "tmp:$n"
    }

    override fun onCleared() {
        watchers.values.flatten().forEach { it.cancel() }
        // 会话本身归 hub 所有，随宿主进程管理；这里只取消 watcher 协程
        super.onCleared()
    }
}

/** tab key → 项目（projects 由 UI 订阅后传入，保证响应式） */
internal fun terminalProjectOf(key: String, projects: List<Project>): Project? {
    if (!key.startsWith("project:")) return null
    val id = key.removePrefix("project:").substringBefore('#')
    return projects.find { it.id == id }
}

/** tab key → 会话工作目录（项目主目录优先） */
internal fun terminalCwdOf(key: String, projects: List<Project>): String? =
    terminalProjectOf(key, projects)?.directories?.firstOrNull()

/** tab 显示名：会话自定义标题优先；同项目多 shell 加序号区分 */
internal fun terminalTabTitle(key: String, sessionTitle: String?, projects: List<Project>): String {
    sessionTitle?.takeIf { it.isNotBlank() }?.let { return it }
    return when {
        key.startsWith("project:") -> {
            val id = key.removePrefix("project:").substringBefore('#')
            val base = projects.find { it.id == id }?.name ?: "终端"
            val suffix = key.substringAfter('#', "")
            if (suffix.isEmpty()) base else "$base ($suffix)"
        }
        key.startsWith("tmp:") -> "终端"
        else -> "终端"
    }
}
