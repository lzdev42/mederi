package xyz.mederi.ui.appstate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.browser_check_update_failed
import mederi.app.shared.generated.resources.browser_install_failed
import mederi.app.shared.generated.resources.browser_refresh_failed
import mederi.app.shared.generated.resources.browser_save_failed
import mederi.app.shared.generated.resources.err_generic
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.BrowserStatus
import xyz.mederi.core.contract.models.CamoufoxSettings
import xyz.mederi.core.contract.models.CamoufoxUpdate
import xyz.mederi.core.contract.models.UpdateCamoufoxSettingsInput
import xyz.mederi.ui.DebugLog
import xyz.mederi.ui.UiMessage

/**
 * Camoufox 浏览器设置唯一真理源（Single Source of Truth）。
 *
 * 集中管理浏览器设置、运行状态与已安装版本，由 [AppState] 统一部署并持有生命周期，
 * 设置页 BROWSER Tab 及后续浏览器自动化相关 UI 共享此同一实例。
 */
class BrowserSettingsStore(
    private val aiCore: AiCore,
    private val scope: CoroutineScope,
) {
    private val _settings = MutableStateFlow(CamoufoxSettings())
    val settings: StateFlow<CamoufoxSettings> = _settings.asStateFlow()

    private val _status = MutableStateFlow<BrowserStatus?>(null)
    val status: StateFlow<BrowserStatus?> = _status.asStateFlow()

    private val _installedVersions = MutableStateFlow<List<String>>(emptyList())
    val installedVersions: StateFlow<List<String>> = _installedVersions.asStateFlow()

    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    private val _errorMessage = MutableStateFlow<UiMessage?>(null)
    val errorMessage: StateFlow<UiMessage?> = _errorMessage.asStateFlow()

    init {
        // 内核就绪后立即触发首次加载
        scope.launch {
            aiCore.isReady.first { it }
            refreshAll()
        }
    }

    /** 刷新设置、浏览器状态与已安装版本。 */
    fun refreshAll() {
        scope.launch {
            try {
                val settingsRes = aiCore.getCamoufoxSettings()
                if (settingsRes.isSuccess) {
                    _settings.value = settingsRes.getOrDefault(CamoufoxSettings())
                } else {
                    val err = settingsRes.exceptionOrNull()
                    DebugLog.error("BrowserSettingsStore", "Refresh settings failed: ${err?.message}", err)
                    _errorMessage.value = err?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                        ?: UiMessage(Res.string.browser_refresh_failed)
                }

                val statusRes = aiCore.getCamoufoxStatus()
                if (statusRes.isSuccess) {
                    _status.value = statusRes.getOrNull()
                } else {
                    val err = statusRes.exceptionOrNull()
                    DebugLog.error("BrowserSettingsStore", "Refresh status failed: ${err?.message}", err)
                    _errorMessage.value = err?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                        ?: UiMessage(Res.string.browser_refresh_failed)
                }

                val versionsRes = aiCore.listInstalledCamoufoxVersions()
                if (versionsRes.isSuccess) {
                    _installedVersions.value = versionsRes.getOrDefault(emptyList())
                } else {
                    val err = versionsRes.exceptionOrNull()
                    DebugLog.error("BrowserSettingsStore", "Refresh installed versions failed: ${err?.message}", err)
                    _errorMessage.value = err?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                        ?: UiMessage(Res.string.browser_refresh_failed)
                }
            } finally {
                // 无需 isRefreshing 状态（与 SandboxSettingsPanel 同层级面板，操作态走 isOperating）
            }
        }
    }

    /** 保存浏览器设置（成功后自动刷新）。 */
    suspend fun save(settings: CamoufoxSettings): Result<Unit> {
        return try {
            val res = aiCore.updateCamoufoxSettings(UpdateCamoufoxSettingsInput(settings))
            if (res.isSuccess) {
                _settings.value = settings
                refreshAll()
            } else {
                val err = res.exceptionOrNull()
                DebugLog.error("BrowserSettingsStore", "Save settings failed: ${err?.message}", err)
                _errorMessage.value = err?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                    ?: UiMessage(Res.string.browser_save_failed)
            }
            res.map { }
        } catch (e: Exception) {
            _errorMessage.value = UiMessage(Res.string.err_generic, listOf(e.message.orEmpty()))
            Result.failure(e)
        }
    }

    /** 安装 Camoufox（versionTag 为空 = 安装最新；成功后自动刷新）。 */
    suspend fun install(versionTag: String? = null): Result<Unit> {
        _isOperating.value = true
        return try {
            val res = aiCore.installCamoufox(versionTag)
            if (res.isSuccess) {
                DebugLog.info("BrowserSettingsStore", "Install camoufox success: ${versionTag ?: "latest"}")
                refreshAll()
            } else {
                val err = res.exceptionOrNull()
                DebugLog.error("BrowserSettingsStore", "Install camoufox failed: ${err?.message}", err)
                _errorMessage.value = err?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                    ?: UiMessage(Res.string.browser_install_failed)
            }
            res
        } finally {
            _isOperating.value = false
        }
    }

    /** 检查 Camoufox 更新（成功后把结果映射进 [status]）。 */
    suspend fun checkUpdate(): Result<CamoufoxUpdate> {
        return try {
            val res = aiCore.checkCamoufoxUpdate()
            if (res.isSuccess) {
                val update = res.getOrThrow()
                _status.value = BrowserStatus(
                    configured = update.configured,
                    installedVersion = update.installedVersion,
                    latestVersion = update.latestVersion,
                    hasUpdate = update.hasUpdate,
                    supported = update.supported,
                    reason = update.reason
                )
            } else {
                val err = res.exceptionOrNull()
                DebugLog.error("BrowserSettingsStore", "Check update failed: ${err?.message}", err)
                _errorMessage.value = err?.message?.let { UiMessage(Res.string.err_generic, listOf(it)) }
                    ?: UiMessage(Res.string.browser_check_update_failed)
            }
            res
        } catch (e: Exception) {
            _errorMessage.value = UiMessage(Res.string.err_generic, listOf(e.message.orEmpty()))
            Result.failure(e)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
