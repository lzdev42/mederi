package xyz.mederi.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import xyz.mederi.core.contract.models.ApiKeyOption
import xyz.mederi.core.contract.models.ModelOption
import xyz.mederi.core.contract.models.ModelOrigin
import xyz.mederi.core.contract.models.ProtocolType
import xyz.mederi.core.contract.models.ProviderConfig
import xyz.mederi.core.contract.models.ProviderType
import xyz.mederi.core.contract.dto.CreateCustomProviderInput
import xyz.mederi.core.contract.dto.ProviderUpdateInput
import xyz.mederi.core.contract.dto.ReasoningConfigInput
import xyz.mederi.core.ui.appstate.AppState

/**
 * 供应商模型同步状态。
 */
sealed class ModelsSyncStatus {
    /** 初始状态 / 空闲 */
    data object Idle : ModelsSyncStatus()

    /** 正在请求 /models 路由拉取模型列表 */
    data object Syncing : ModelsSyncStatus()

    /**
     * 自动同步成功。
     * @param count 同步到的模型数量
     * @param source 数据来源提示（如 "/models"）
     */
    data class Success(val count: Int, val source: String = "/models") : ModelsSyncStatus()

    /**
     * 供应商端点不支持 /models 路由（如 404、无权限），需要用户手动添加模型。
     */
    data object UnsupportedEndpoint : ModelsSyncStatus()

    /**
     * 远端返回了空的模型列表。需要用户手动添加模型。
     */
    data object Empty : ModelsSyncStatus()

    /** 同步发生错误 */
    data class Error(val message: String) : ModelsSyncStatus()
}

/**
 * 单个模型的 UI 状态项。
 *
 * @param id core 生成的模型唯一 ID（如 "mdl_xxx"）
 * @param providerModelId 供应商侧模型实际标识（如 "claude-3-5-sonnet-20241022"）
 * @param name 显示名称（用户可自定义或默认与 ID 相同）
 * @param isEnabled 是否启用（勾选在主聊天输入框的模型列表中显示）
 * @param supportsThinking 是否支持思考/Reasoning
 * @param supportsImages 是否支持图片（Image）多模态
 * @param origin 元数据所有权：FETCHED = 目录/端点权威（元数据只读）；MANUAL = 用户权威（可编辑）
 * @param isFree 是否为免费模型（core 未建模，恒为 false）
 * @param contextWindow 上下文窗口大小（tokens），null 表示未知
 * @param maxTokens 最大输出大小（tokens），null 表示未设置
 * @param reasoningLevels 支持的思考等级列表（如 ["LOW", "HIGH"]），与 core ReasoningLevel 枚举名对应
 * @param isRichMetadata 是否为富元数据模型（core 未建模，恒为 false）
 */
data class ModelItemUiState(
    val id: String,
    val providerModelId: String,
    val name: String,
    val isEnabled: Boolean = true,
    val supportsThinking: Boolean = false,
    val supportsImages: Boolean = false,
    /** 图片能力用户覆盖：null = 未覆盖（随目录同步）；非 null = 用户显式设置（同步永不覆盖）。 */
    val supportsImagesOverride: Boolean? = null,
    val origin: ModelOrigin = ModelOrigin.FETCHED,
    val isFree: Boolean = false,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val reasoningLevels: List<String> = emptyList(),
    val isRichMetadata: Boolean = false
)

/**
 * 单个供应商的 UI 状态项。
 *
 * @param id core 生成的供应商唯一 ID（如 "prov_xxx"）
 * @param name 供应商显示名称
 * @param isBuiltin 是否为内置供应商预设
 * @param isConnected 是否已连接（至少有一个 API Key）
 * @param protocolType 协议类型，与 core ProviderType 枚举名对应
 * @param baseUrl 端点 Base URL
 * @param apiKey 默认 Key 的脱敏值（仅用于列表行的摘要展示；多 Key 管理请用 [apiKeys]）
 * @param apiKeys 该供应商全部 API Key 列表（脱敏值），多 Key 管理界面的数据源
 * @param reasoningLevels 供应商推理参数配置（v6：级别名 → 请求体 JSON 片段/null，含 NONE 档）
 * @param responseSanitization 是否需要响应清洗（vLLM 兼容端点）
 * @param models 该供应商包含的模型列表
 * @param syncStatus 模型同步状态
 */
data class ProviderItemUiState(
    val id: String,
    val name: String,
    val isBuiltin: Boolean,
    val isConnected: Boolean,
    val protocolType: ProtocolType = ProtocolType.DEFAULT,
    val baseUrl: String = "",
    val apiKey: String = "",
    val apiKeys: List<ApiKeyOption> = emptyList(),
    val reasoningLevels: Map<String, String?> = emptyMap(),
    val responseSanitization: Boolean = false,
    val models: List<ModelItemUiState> = emptyList(),
    val syncStatus: ModelsSyncStatus = ModelsSyncStatus.Idle
) {
    /** 掩码后的 API Key，仅用于列表展示 */
    val maskedApiKey: String
        get() = if (apiKey.isBlank()) "" else {
            if (apiKey.length <= 8) "••••••••" else "${apiKey.take(3)}••••${apiKey.takeLast(4)}"
        }

    /** 当前已启用的模型数量 */
    val enabledModelCount: Int
        get() = models.count { it.isEnabled }
}

/**
 * 能力过滤标签枚举。
 */
enum class CapabilityFilter(val label: String) {
    ALL("全部"),
    ENABLED_ONLY("仅启用"),
    REASONING("🧠 Reasoning"),
    IMAGE("🖼️ Image"),
    FREE("🆓 免费")
}

/**
 * 供应商与模型管理整个页面的 UI State。
 *
 * @param providers 所有已加载的供应商列表（来自 appState.providers，含内置预设与自定义）
 * @param selectedProviderId 当前在右侧工作区选中的供应商 ID，null 且 isCreatingCustom=false 时显示空态
 * @param isCreatingCustom 是否正在新建自定义供应商（右侧显示创建表单）
 * @param searchQuery 模型搜索框关键字
 * @param capabilityFilter 当前选中的能力过滤器
 * @param isSavingCredentials 凭据保存/连接中状态
 * @param successMessage 操作成功提示信息（临时）
 * @param errorMessage 错误提示信息（临时）
 */
data class ProviderSettingsUiState(
    val providers: List<ProviderItemUiState> = emptyList(),
    val selectedProviderId: String? = null,
    val isCreatingCustom: Boolean = false,
    val searchQuery: String = "",
    val capabilityFilter: CapabilityFilter = CapabilityFilter.ALL,
    val isSavingCredentials: Boolean = false,
    val successMessage: String? = null,
    val errorMessage: String? = null
) {
    /** 当前选中的供应商 */
    val selectedProvider: ProviderItemUiState?
        get() = providers.find { it.id == selectedProviderId }

    /**
     * 根据搜索词与能力过滤器过滤后的模型列表。
     */
    val filteredModels: List<ModelItemUiState>
        get() {
            val provider = selectedProvider ?: return emptyList()
            return provider.models.filter { model ->
                // 1. 搜索词匹配（匹配名称或 providerModelId）
                val matchesQuery = searchQuery.isBlank() ||
                        model.name.contains(searchQuery, ignoreCase = true) ||
                        model.providerModelId.contains(searchQuery, ignoreCase = true)

                // 2. 能力过滤
                val matchesFilter = when (capabilityFilter) {
                    CapabilityFilter.ALL -> true
                    CapabilityFilter.ENABLED_ONLY -> model.isEnabled
                    CapabilityFilter.REASONING -> model.supportsThinking
                    CapabilityFilter.IMAGE -> model.supportsImages
                    CapabilityFilter.FREE -> model.isFree
                }

                matchesQuery && matchesFilter
            }
        }
}

/**
 * 供应商与模型管理 ViewModel（设置页"供应商管理"标签的数据层）。
 *
 * ## 数据流（唯一真理源）
 *
 * ```
 * MederiAiCore (驱动) → appState.providers: StateFlow → 本 ViewModel 收集 → uiState → Compose 渲染
 * ```
 *
 * 所有写操作（增删改）转调 aiCore.* → core 层更新 → StateFlow 自动重推 → uiState 自动刷新。
 * **UI 只需观察 [uiState] 并调用本类公开方法，不要手动维护供应商列表副本。**
 * 唯一例外：[ModelsSyncStatus]（同步中/成功/失败提示）是纯 UI 态，由本 ViewModel 在
 * StateFlow 重推时保留，不会丢失。
 *
 * ## UI 对接速查
 *
 * | UI 交互 | 调用 |
 * |---|---|
 * | 左侧供应商列表 | `uiState.providers` |
 * | 选中某供应商 | `selectProvider(id)` |
 * | 新建自定义供应商表单 | `startCreateCustomProvider()` → 表单提交 `saveProviderCredentials(providerId=null, ...)` |
 * | 编辑供应商凭据（名称/BaseURL/Key） | `saveProviderCredentials(providerId, ...)` |
 * | 添加内置预设供应商 | `addBuiltinProvider(presetName, apiKey)`，预设名列表 `builtinPresets` |
 * | 断开连接（删光 Key） | `disconnectProvider(id)` |
 * | 删除供应商 | `deleteCustomProvider(id)` |
 * | 重新拉取远端模型 | `autoFetchModels(id)`，结果看 `provider.syncStatus` |
 * | 手动加模型 / 改模型 / 删模型 | `addManualModel` / `updateModelConfig` / `deleteModel` |
 * | 多 Key 管理 | `addApiKey` / `setDefaultApiKey` / `deleteApiKey`，数据源 `provider.apiKeys` |
 * | 模型搜索与过滤 | `updateSearchQuery` / `setCapabilityFilter`，结果 `uiState.filteredModels` |
 *
 * 操作成功/失败的提示统一走 `uiState.successMessage` / `uiState.errorMessage`（临时态，
 * 展示后调 `clearMessages()` 清除）。
 *
 * @param appState 全局 AppState，由外部（MainScreen 层）注入
 */
class ProviderSettingsViewModel(
    private val appState: AppState
) : ViewModel() {

    /** 页面唯一 UI 状态。Compose 中直接观察即可，写入只发生在本类内部。 */
    var uiState by mutableStateOf(ProviderSettingsUiState())
        private set

    /**
     * 内置供应商预设名列表（如 "Google Gemini"、"Agnes SG"），来自 core 的 BuiltinProviders。
     * UI 渲染"内置预设"区块；用户点选未添加的预设时弹出 Key 输入框，
     * 提交调 [addBuiltinProvider]。
     */
    val builtinPresets: List<String> get() = appState.aiCore.builtinPresets

    /**
     * 按内置预设添加供应商。用户只需填 API Key，BaseURL/协议/推理参数由预设定义。
     * 协程由 viewModelScope 管理，避免 UI 层泄露 SuspendLambda 匿名类与生命周期问题。
     *
     * @param name 预设完整名（必须来自 [builtinPresets]，如 "Agnes SG"）
     * @param apiKey 必填，明文 Key
     */
    fun addBuiltinProvider(
        name: String,
        apiKey: String,
        onComplete: ((Result<ProviderConfig>) -> Unit)? = null
    ) {
        viewModelScope.launch {
            uiState = uiState.copy(isSavingCredentials = true, errorMessage = null)
            val result = appState.aiCore.addBuiltinProvider(name, apiKey)
            result.fold(
                onSuccess = { config ->
                    uiState = uiState.copy(
                        isSavingCredentials = false,
                        selectedProviderId = config.id,
                        isCreatingCustom = false,
                        successMessage = "已成功添加供应商 ${config.name}"
                    )
                    if (config.isConnected) {
                        autoFetchModels(config.id)
                    }
                },
                onFailure = { e ->
                    uiState = uiState.copy(
                        isSavingCredentials = false,
                        errorMessage = "添加供应商失败: ${e.message}"
                    )
                }
            )
            onComplete?.invoke(result)
        }
    }

    init {
        viewModelScope.launch {
            appState.providers.collect { providerConfigs ->
                // 保留各供应商已有的同步状态（syncStatus 是纯 UI 态，StateFlow 重推时不丢失）
                val previousSyncStatuses = uiState.providers.associate { it.id to it.syncStatus }
                val providers = providerConfigs.map { config ->
                    val syncStatus = previousSyncStatuses[config.id] ?: ModelsSyncStatus.Idle
                    toProviderItemUiState(config, syncStatus)
                }
                // 已选中的供应商仍存在则保持选中，否则回落到列表第一个
                val currentSelected = uiState.selectedProviderId
                val newSelectedId = if (currentSelected != null && providers.any { it.id == currentSelected }) {
                    currentSelected
                } else {
                    providers.firstOrNull()?.id
                }
                uiState = uiState.copy(
                    providers = providers,
                    selectedProviderId = newSelectedId
                )
            }
        }
    }

    /** ProviderConfig (contract) → ProviderItemUiState，syncStatus 是 UI 局部状态由外部传入。 */
    private fun toProviderItemUiState(config: ProviderConfig, syncStatus: ModelsSyncStatus): ProviderItemUiState {
        return ProviderItemUiState(
            id = config.id,
            name = config.name,
            isBuiltin = config.type == ProviderType.Builtin,
            isConnected = config.isConnected,
            protocolType = config.protocolType,
            baseUrl = config.baseUrl ?: "",
            apiKey = config.apiKeys.firstOrNull()?.maskedValue ?: "",
            apiKeys = config.apiKeys,
            reasoningLevels = config.reasoningLevels,
            responseSanitization = config.responseSanitization,
            models = config.models.map { toModelItemUiState(it) },
            syncStatus = syncStatus
        )
    }

    /**
     * ModelOption (contract) → ModelItemUiState。
     *
     * 注意 core 未建模的字段映射：
     * - isFree / isRichMetadata 恒 false（core 无此概念）
     * - isEnabled 来自 core（任务 5 起持久化）
     */
    private fun toModelItemUiState(model: ModelOption): ModelItemUiState {
        return ModelItemUiState(
            id = model.id,
            providerModelId = model.providerModelId,
            name = model.name,
            isEnabled = model.isEnabled,
            supportsThinking = model.supportsThinking,
            supportsImages = model.supportsImages,
            supportsImagesOverride = model.supportsImagesOverride,
            origin = model.origin,
            isFree = false,
            contextWindow = model.contextWindow,
            maxTokens = model.maxTokens,
            reasoningLevels = model.reasoningLevels,
            isRichMetadata = false
        )
    }

    // ==========================================
    // 1. 供应商选择与新建自定义切换
    // ==========================================

    fun selectProvider(providerId: String) {
        uiState = uiState.copy(
            selectedProviderId = providerId,
            isCreatingCustom = false,
            searchQuery = "",
            capabilityFilter = CapabilityFilter.ALL,
            successMessage = null,
            errorMessage = null
        )
    }

    fun startCreateCustomProvider() {
        uiState = uiState.copy(
            selectedProviderId = null,
            isCreatingCustom = true,
            searchQuery = "",
            successMessage = null,
            errorMessage = null
        )
    }

    // ==========================================
    // 2. 搜索与过滤
    // ==========================================

    fun updateSearchQuery(query: String) {
        uiState = uiState.copy(searchQuery = query)
    }

    fun setCapabilityFilter(filter: CapabilityFilter) {
        uiState = uiState.copy(capabilityFilter = filter)
    }

    fun clearMessages() {
        uiState = uiState.copy(successMessage = null, errorMessage = null)
    }

    // ==========================================
    // 3. 供应商连接与凭据保存
    // ==========================================

    fun saveProviderCredentials(
        providerId: String?,
        name: String,
        protocolType: ProtocolType,
        baseUrl: String,
        apiKey: String,
        reasoningLevels: Map<String, String?>
    ) {
        viewModelScope.launch {
            uiState = uiState.copy(isSavingCredentials = true, errorMessage = null)
            // 用户配置了推理参数则用用户的（levels 为空 map 视为未配置，core 侧回退协议默认工厂）
            val reasoningConfig = ReasoningConfigInput(levels = reasoningLevels)
                .takeIf { reasoningLevels.isNotEmpty() }

            if (providerId == null) {
                // 新建自定义供应商：core 返回完整 ProviderConfig
                appState.aiCore.createCustomProvider(
                    CreateCustomProviderInput(
                        name = name.ifBlank { "Custom Provider" },
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim().ifBlank { null },
                        type = protocolType,
                        responseSanitization = false,
                        reasoningParameter = reasoningConfig
                    )
                ).fold(
                    onSuccess = { config ->
                        uiState = uiState.copy(
                            isSavingCredentials = false,
                            selectedProviderId = config.id,
                            isCreatingCustom = false,
                            successMessage = "已成功创建自定义供应商"
                        )
                        if (config.isConnected) {
                            autoFetchModels(config.id)
                        }
                    },
                    onFailure = { e ->
                        uiState = uiState.copy(
                            isSavingCredentials = false,
                            errorMessage = "保存失败: ${e.message}"
                        )
                    }
                )
            } else {
                // 更新已有供应商：core 只返回 Unit
                appState.aiCore.configureProvider(
                    providerId,
                    ProviderUpdateInput(
                        name = name.ifBlank { null },
                        baseUrl = baseUrl.trim().ifBlank { null },
                        apiKey = apiKey.trim().ifBlank { null },
                        reasoningParameter = reasoningConfig
                    )
                ).fold(
                    onSuccess = {
                        uiState = uiState.copy(
                            isSavingCredentials = false,
                            selectedProviderId = providerId,
                            successMessage = "供应商配置已保存"
                        )
                        if (apiKey.isNotBlank()) {
                            autoFetchModels(providerId)
                        }
                    },
                    onFailure = { e ->
                        uiState = uiState.copy(
                            isSavingCredentials = false,
                            errorMessage = "保存失败: ${e.message}"
                        )
                    }
                )
            }
        }
    }

    /**
     * 断开供应商连接（删除该供应商的全部 API Key）。
     *
     * 断开后 isConnected 变为 false，该供应商下的模型不再出现在聊天输入框的可选模型列表。
     * 注意这是"删光所有 Key"的快捷操作；如需精细管理单个 Key，用 [deleteApiKey]。
     */
    fun disconnectProvider(providerId: String) {
        viewModelScope.launch {
            val provider = uiState.providers.find { it.id == providerId }
            if (provider == null) return@launch

            val keys = appState.providers.value.find { it.id == providerId }?.apiKeys ?: emptyList()
            for (key in keys) {
                appState.aiCore.deleteProviderApiKey(providerId, key.id)
            }
            uiState = uiState.copy(successMessage = "已断开连接")
        }
    }

    // ==========================================
    // 3.5 多 API Key 管理（一个供应商可配置多个 Key，其中一个为默认）
    // ==========================================

    /**
     * 给供应商添加一个 API Key。
     *
     * @param providerId 供应商 ID（来自 [ProviderItemUiState.id]）
     * @param name Key 的显示名（如 "Primary" / "Backup"），便于用户区分
     * @param key 明文 API Key
     * @param isDefault 是否设为默认 Key。供应商的第一个 Key 建议传 true；
     *                  若已有默认 Key 又再传 true，core 会把默认标记迁移到新 Key 上
     *
     * 用法示例（UI 渲染"添加 Key"表单时）：
     * ```
     * viewModel.addApiKey(provider.id, nameInput, keyInput, isDefault = provider.apiKeys.isEmpty())
     * ```
     * 成功后 appState.providers StateFlow 自动推送，uiState.providers 里的 apiKeys 列表随之刷新，
     * UI 无需手动更新本地状态。
     */
    fun addApiKey(providerId: String, name: String, key: String, isDefault: Boolean = false) {
        viewModelScope.launch {
            appState.aiCore.addProviderApiKey(providerId, name, key, isDefault)
                .onSuccess { uiState = uiState.copy(successMessage = "已添加 API Key") }
                .onFailure { uiState = uiState.copy(errorMessage = "添加 Key 失败: ${it.message}") }
        }
    }

    /**
     * 把某个 Key 设为该供应商的默认 Key。
     *
     * 发消息时 core 使用默认 Key 鉴权；默认 Key 失效时可切换到备用 Key。
     *
     * @param keyId 要设为默认的 Key ID（来自 [ApiKeyOption.id]）
     *
     * 用法示例（每个非默认 Key 行渲染"设为默认"按钮）：
     * ```
     * if (!key.isDefault) viewModel.setDefaultApiKey(provider.id, key.id)
     * ```
     */
    fun setDefaultApiKey(providerId: String, keyId: String) {
        viewModelScope.launch {
            appState.aiCore.setDefaultProviderApiKey(providerId, keyId)
                .onSuccess { uiState = uiState.copy(successMessage = "已切换默认 Key") }
                .onFailure { uiState = uiState.copy(errorMessage = "切换失败: ${it.message}") }
        }
    }

    /**
     * 删除单个 API Key。
     *
     * 注意：删除默认 Key 后 core 会自动把默认标记落到剩余 Key 中的第一个；
     * 删光所有 Key 等价于 [disconnectProvider]（供应商变为未连接）。
     *
     * @param keyId 要删除的 Key ID（来自 [ApiKeyOption.id]）
     */
    fun deleteApiKey(providerId: String, keyId: String) {
        viewModelScope.launch {
            appState.aiCore.deleteProviderApiKey(providerId, keyId)
                .onSuccess { uiState = uiState.copy(successMessage = "已删除 API Key") }
                .onFailure { uiState = uiState.copy(errorMessage = "删除失败: ${it.message}") }
        }
    }

    fun deleteCustomProvider(providerId: String) {
        viewModelScope.launch {
            val result = appState.aiCore.deleteProvider(providerId)
            result.fold(
                onSuccess = {
                    val updated = uiState.providers.filter { it.id != providerId }
                    uiState = uiState.copy(
                        providers = updated,
                        selectedProviderId = updated.firstOrNull()?.id,
                        isCreatingCustom = false,
                        successMessage = "已删除供应商"
                    )
                },
                onFailure = { e ->
                    uiState = uiState.copy(errorMessage = "删除失败: ${e.message}")
                }
            )
        }
    }

    // ==========================================
    // 4. 模型拉取与同步
    // ==========================================

    fun autoFetchModels(providerId: String) {
        viewModelScope.launch {
            updateProviderSyncStatus(providerId, ModelsSyncStatus.Syncing)

            val result = appState.aiCore.refreshProviderModels(providerId)
            result.fold(
                onSuccess = { modelIds ->
                    if (modelIds.isEmpty()) {
                        // 远端没返回任何模型：明确提示，让用户手动填模型参数
                        updateProviderSyncStatus(providerId, ModelsSyncStatus.Empty)
                        uiState = uiState.copy(
                            errorMessage = "远端未返回任何模型，请手动添加模型参数"
                        )
                    } else {
                        updateProviderSyncStatus(providerId, ModelsSyncStatus.Success(modelIds.size, "/models"))
                        uiState = uiState.copy(
                            successMessage = "已通过 /models 自动同步 ${modelIds.size} 个模型"
                        )
                    }
                },
                onFailure = { e ->
                    val msg = e.message ?: ""
                    val status = if (msg.contains("404") || msg.contains("not found")) {
                        ModelsSyncStatus.UnsupportedEndpoint
                    } else {
                        ModelsSyncStatus.Error("拉取失败: $msg")
                    }
                    updateProviderSyncStatus(providerId, status)
                }
            )
        }
    }

    private fun updateProviderSyncStatus(providerId: String, status: ModelsSyncStatus) {
        uiState = uiState.copy(
            providers = uiState.providers.map {
                if (it.id == providerId) it.copy(syncStatus = status) else it
            }
        )
    }

    // ==========================================
    // 5. 模型即时生效操作
    // ==========================================

    fun toggleModelEnabled(providerId: String, modelId: String) {
        val current = uiState.providers
            .find { it.id == providerId }?.models?.find { it.id == modelId } ?: return
        val newState = !current.isEnabled

        // 乐观更新（即点即生效）
        applyModelEnabledLocal(providerId, modelId, newState)

        viewModelScope.launch {
            appState.aiCore.setModelEnabled(providerId, modelId, newState).fold(
                onSuccess = {},
                onFailure = { e ->
                    // 失败回滚
                    applyModelEnabledLocal(providerId, modelId, current.isEnabled)
                    uiState = uiState.copy(errorMessage = "更新失败: ${e.message}")
                }
            )
        }
    }

    /** 全选 / 全部取消（对当前过滤后可见的模型），逐个持久化，即点即生效 */
    fun setAllModelsEnabled(providerId: String, enabled: Boolean) {
        val targetIds = uiState.filteredModels.map { it.id }
        if (targetIds.isEmpty()) return

        val snapshot = uiState.providers
        targetIds.forEach { applyModelEnabledLocal(providerId, it, enabled) }

        viewModelScope.launch {
            var failure: String? = null
            for (modelId in targetIds) {
                appState.aiCore.setModelEnabled(providerId, modelId, enabled).onFailure { e ->
                    if (failure == null) failure = e.message
                }
            }
            if (failure != null) {
                // 有失败：整体回滚到操作前快照
                uiState = uiState.copy(providers = snapshot, errorMessage = "批量更新失败: $failure")
            }
        }
    }

    private fun applyModelEnabledLocal(providerId: String, modelId: String, enabled: Boolean) {
        uiState = uiState.copy(
            providers = uiState.providers.map { p ->
                if (p.id == providerId) {
                    p.copy(models = p.models.map { m ->
                        if (m.id == modelId) m.copy(isEnabled = enabled) else m
                    })
                } else p
            }
        )
    }

    fun addManualModel(
        providerId: String,
        modelId: String,
        name: String,
        supportsImages: Boolean,
        supportsThinking: Boolean,
        contextWindow: Int?,
        maxTokens: Int?,
        reasoningLevels: List<String>
    ) {
        val cleanModelId = modelId.trim()
        if (cleanModelId.isBlank()) return

        viewModelScope.launch {
            val result = appState.aiCore.addProviderModel(
                providerId = providerId,
                providerModelId = cleanModelId,
                name = name.ifBlank { cleanModelId }.trim(),
                supportsThinking = supportsThinking,
                supportsImages = supportsImages,
                contextWindow = contextWindow,
                maxTokens = maxTokens,
                reasoningLevels = if (supportsThinking) reasoningLevels else emptyList()
            )
            result.fold(
                onSuccess = {
                    uiState = uiState.copy(successMessage = "已添加模型: ${name.ifBlank { cleanModelId }}")
                },
                onFailure = { e ->
                    uiState = uiState.copy(errorMessage = "添加失败: ${e.message}")
                }
            )
        }
    }

    fun updateModelConfig(
        providerId: String,
        modelId: String,
        name: String,
        supportsImages: Boolean,
        supportsThinking: Boolean,
        contextWindow: Int?,
        maxTokens: Int?,
        reasoningLevels: List<String>
    ) {
        // FETCHED 模型元数据是端点/目录权威（ModelMerge 唯一写入），UI 不提供编辑；
        // 这里兜底拦一次，防止调用方绕过对话框状态直接发起
        val target = uiState.providers.find { it.id == providerId }?.models?.find { it.id == modelId }
        if (target?.origin == ModelOrigin.FETCHED) {
            uiState = uiState.copy(errorMessage = "该模型元数据来自模型目录，不可编辑（如需自定义请删除后手动添加）")
            return
        }
        viewModelScope.launch {
            val result = appState.aiCore.updateProviderModel(
                providerId = providerId,
                modelId = modelId,
                name = name.ifBlank { null }?.trim(),
                supportsThinking = supportsThinking,
                supportsImages = supportsImages,
                contextWindow = contextWindow,
                maxTokens = maxTokens,
                reasoningLevels = if (supportsThinking) reasoningLevels else emptyList()
            )
            result.fold(
                onSuccess = {
                    uiState = uiState.copy(successMessage = "模型配置已更新")
                },
                onFailure = { e ->
                    uiState = uiState.copy(errorMessage = "更新失败: ${e.message}")
                }
            )
        }
    }

    /**
     * 图片能力用户覆盖（FETCHED 模型的图片开关唯一入口）：
     * 用户显式设置写入 supportsImagesOverride（用户权威），目录/刷新/回填永不洗掉。
     */
    fun setImageOverride(providerId: String, modelId: String, supported: Boolean) {
        viewModelScope.launch {
            val result = appState.aiCore.updateProviderModel(
                providerId = providerId,
                modelId = modelId,
                supportsImages = supported
            )
            result.fold(
                onSuccess = {
                    uiState = uiState.copy(successMessage = if (supported) "已设置支持图片（用户覆盖，不会被目录同步覆盖）" else "已设置不支持图片（用户覆盖）")
                },
                onFailure = { e ->
                    uiState = uiState.copy(errorMessage = "设置失败: ${e.message}")
                }
            )
        }
    }

    /**
     * 「自动设置」：显式应用 models.dev 目录元数据到该供应商全部 FETCHED 模型
     * （系统永不自动纠正存量数据，这是目录数据进入存量模型的唯一通道；用户覆盖优先）。
     */
    fun autoSetupModels(providerId: String) {
        viewModelScope.launch {
            appState.aiCore.autoSetupProviderModels(providerId).fold(
                onSuccess = { n ->
                    uiState = uiState.copy(successMessage = "自动设置完成：更新 $n 个模型（用户覆盖不受影响）")
                },
                onFailure = { e ->
                    uiState = uiState.copy(errorMessage = "自动设置失败: ${e.message}")
                }
            )
        }
    }

    fun deleteModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            val result = appState.aiCore.deleteProviderModel(providerId, modelId)
            result.fold(
                onSuccess = {
                    uiState = uiState.copy(successMessage = "已删除模型")
                },
                onFailure = { e ->
                    uiState = uiState.copy(errorMessage = "删除失败: ${e.message}")
                }
            )
        }
    }
}
