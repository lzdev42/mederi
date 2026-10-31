package xyz.mederi.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.*
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.*
import xyz.mederi.ui.UiMessage

/** 配置快照只由 Core 回读；加载失败不制造默认配置。两类设置独立工作。 */
internal data class AgentSettingsUiState(
    val configs: List<SubagentConfigItem> = emptyList(),
    val global: SubagentGlobalSettings? = null,
    val loadingConfigs: Boolean = true,
    val loadingGlobal: Boolean = true,
    val savingRole: String? = null,
    val savingGlobal: Boolean = false,
    val configsReady: Boolean = false,
    val configsError: UiMessage? = null,
    val globalError: UiMessage? = null,
) {
    val rolesEnabled get() = configsReady && !loadingConfigs && savingRole == null
    val concurrencyEnabled get() = global != null && !loadingGlobal && !savingGlobal
}

internal class AgentSettingsViewModel(private val core: AiCore) : ViewModel() {
    var uiState by mutableStateOf(AgentSettingsUiState())
        private set

    private var readingConfigs = false
    private var readingGlobal = false

    init { reloadConfigs(); reloadGlobal() }

    fun reloadConfigs() {
        if (uiState.savingRole != null || readingConfigs) return
        readingConfigs = true
        uiState = uiState.copy(loadingConfigs = true, configsError = null)
        viewModelScope.launch { try { readConfigs() } finally { readingConfigs = false } }
    }

    fun reloadGlobal() {
        if (uiState.savingGlobal || readingGlobal) return
        readingGlobal = true
        uiState = uiState.copy(loadingGlobal = true, globalError = null)
        viewModelScope.launch { try { readGlobal() } finally { readingGlobal = false } }
    }

    private suspend fun readConfigs() {
        core.listSubagentConfigs().fold(onSuccess = {
            uiState = uiState.copy(configs = it, loadingConfigs = false, configsReady = true)
        }, onFailure = {
            uiState = uiState.copy(loadingConfigs = false, configsReady = false,
                configsError = UiMessage(Res.string.settings_agents_load_failed, listOf(it.message.orEmpty())))
        })
    }

    private suspend fun readGlobal() {
        core.getSubagentGlobalSettings().fold(onSuccess = {
            uiState = uiState.copy(global = it, loadingGlobal = false)
        }, onFailure = {
            // 保存成功但回读失败时不允许沿用旧值继续覆盖。
            uiState = uiState.copy(global = null, loadingGlobal = false,
                globalError = UiMessage(Res.string.settings_agents_load_failed, listOf(it.message.orEmpty())))
        })
    }

    fun setConcurrency(value: Int) {
        val current = uiState.global?.maxConcurrentAgents ?: return
        // Core 允许更大上限；保留既有大值向下调整能力，不偷偷把它改成 16。
        if (!uiState.concurrencyEnabled || value < 1 || value == current ||
            (value > 16 && value != current - 1)) return
        uiState = uiState.copy(savingGlobal = true, globalError = null)
        viewModelScope.launch {
            core.updateSubagentGlobalSettings(UpdateSubagentGlobalSettingsInput(value)).fold(onSuccess = {
                readGlobal()
            }, onFailure = {
                uiState = uiState.copy(globalError = UiMessage(Res.string.settings_agents_save_failed, listOf(it.message.orEmpty())))
            })
            uiState = uiState.copy(savingGlobal = false)
        }
    }

    fun selectModel(role: String, model: ModelOption?) {
        val item = uiState.configs.find { it.role == role } ?: return
        val reasoning = item.reasoningLevel?.takeIf { model?.supportsThinking == true && it in model.reasoningLevels }
        update(role, if (model == null) UpdateSubagentConfigInput() else
            UpdateSubagentConfigInput(model.id, reasoning, item.apiKeyId))
    }

    fun selectReasoning(role: String, level: String?) {
        val item = uiState.configs.find { it.role == role } ?: return
        update(role, UpdateSubagentConfigInput(item.modelId, level, item.apiKeyId))
    }

    fun selectKey(role: String, keyId: String?) {
        val item = uiState.configs.find { it.role == role } ?: return
        update(role, UpdateSubagentConfigInput(item.modelId, item.reasoningLevel, keyId))
    }

    private fun update(role: String, input: UpdateSubagentConfigInput) {
        if (!uiState.rolesEnabled) return
        // 同一角色的三个字段是一次全量提交，回读前不接受下一次写，避免陈旧快照覆盖。
        uiState = uiState.copy(savingRole = role, configsError = null)
        viewModelScope.launch {
            core.updateSubagentConfig(role, input).fold(onSuccess = {
                readConfigs()
            }, onFailure = {
                uiState = uiState.copy(configsError = UiMessage(Res.string.settings_agents_save_failed, listOf(it.message.orEmpty())))
            })
            uiState = uiState.copy(savingRole = null)
        }
    }
}
