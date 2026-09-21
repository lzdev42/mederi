package xyz.mederi.ui.appstate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.err_generic
import mederi.app.shared.generated.resources.skill_install_failed
import mederi.app.shared.generated.resources.skill_refresh_failed
import mederi.app.shared.generated.resources.skill_set_dir_failed
import mederi.app.shared.generated.resources.skill_uninstall_failed
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.SkillItem
import xyz.mederi.ui.DebugLog
import xyz.mederi.ui.UiMessage

/**
 * Skill 唯一真理源（Single Source of Truth）。
 *
 * 集中管理 Skill 列表、当前根目录以及操作状态。
 * 由 [AppState] 统一部署并持有生命周期，概览快捷卡片与后续 Skill 市场等所有 UI 组件
 * 共享此同一实例，实现多端/多视图无缝状态双向同步。
 */
class SkillStore(
    private val aiCore: AiCore,
    private val scope: CoroutineScope,
) {
    private val _skills = MutableStateFlow<List<SkillItem>>(emptyList())
    val skills: StateFlow<List<SkillItem>> = _skills.asStateFlow()

    private val _skillsRoot = MutableStateFlow("")
    val skillsRoot: StateFlow<String> = _skillsRoot.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    private val _errorMessage = MutableStateFlow<UiMessage?>(null)
    val errorMessage: StateFlow<UiMessage?> = _errorMessage.asStateFlow()

    init {
        // 内核就绪后立即触发首次加载
        scope.launch {
            aiCore.isReady.first { it }
            refresh()
        }
    }

    /** 刷新 Skill 列表与根目录。 */
    fun refresh() {
        scope.launch {
            _isRefreshing.value = true
            try {
                DebugLog.info("SkillStore", "Refreshing skills and root directory...")
                val rootRes = aiCore.getSkillsRoot()
                if (rootRes.isSuccess) {
                    _skillsRoot.value = rootRes.getOrDefault("")
                }

                val listRes = aiCore.listSkills()
                if (listRes.isSuccess) {
                    val list = listRes.getOrDefault(emptyList())
                    _skills.value = list
                    DebugLog.info("SkillStore", "Refresh success, found ${list.size} skills")
                } else {
                    val err = listRes.exceptionOrNull()
                    DebugLog.error("SkillStore", "Refresh skills failed: ${err?.message}", err)
                    _errorMessage.value = err?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                        ?: UiMessage(Res.string.skill_refresh_failed)
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** 设置 Skill 根目录（成功后自动刷新列表）。 */
    suspend fun setRootDirectory(path: String): Result<Unit> {
        _isOperating.value = true
        return try {
            val res = aiCore.setSkillsRoot(path)
            if (res.isSuccess) {
                _skillsRoot.value = path
                refresh()
            } else {
                _errorMessage.value = res.exceptionOrNull()?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                    ?: UiMessage(Res.string.skill_set_dir_failed)
            }
            res
        } finally {
            _isOperating.value = false
        }
    }

    /** 安装 Skill（Zip URL，成功后自动刷新列表）。 */
    suspend fun install(url: String): Result<SkillItem> {
        _isOperating.value = true
        return try {
            val res = aiCore.installSkill(url)
            if (res.isSuccess) {
                DebugLog.info("SkillStore", "Install skill success: ${res.getOrNull()?.name}")
                refresh()
            } else {
                _errorMessage.value = res.exceptionOrNull()?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                    ?: UiMessage(Res.string.skill_install_failed)
            }
            res
        } finally {
            _isOperating.value = false
        }
    }

    /** 卸载 Skill（成功后自动刷新列表）。 */
    suspend fun uninstall(name: String): Result<Unit> {
        _isOperating.value = true
        return try {
            val res = aiCore.uninstallSkill(name)
            if (res.isSuccess) {
                DebugLog.info("SkillStore", "Uninstall skill success: $name")
                refresh()
            } else {
                _errorMessage.value = res.exceptionOrNull()?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                    ?: UiMessage(Res.string.skill_uninstall_failed)
            }
            res
        } finally {
            _isOperating.value = false
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
