package xyz.mederi.ui.settings

import xyz.mederi.ui.components.atoms.WorkspaceLoadingIndicator

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ModelOrigin
import xyz.mederi.core.contract.models.ReasoningLevels
import xyz.mederi.theme.*
import xyz.mederi.ui.components.ErrorBoard
import xyz.mederi.ui.components.atoms.ConfirmDialog
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceActionTone
import xyz.mederi.ui.components.atoms.WorkspaceNavigationTab

/** 详情Route只装配已有VM动作和领域弹窗；呈现交给不可变数据+回调。 */
@Composable
internal fun ProviderDetailWorkspace(
    provider: ProviderItemUiState, viewModel: ProviderSettingsViewModel, colors: MederiColors,
    isCompact: Boolean = false, onDelete: () -> Unit = {},
) {
    var showEditCredentialsDialog by remember(provider.id) { mutableStateOf(false) }
    var showManageKeysDialog by remember(provider.id) { mutableStateOf(false) }
    var showAddManualModelDialog by remember(provider.id) { mutableStateOf(false) }
    var editingModel by remember(provider.id) { mutableStateOf<ModelItemUiState?>(null) }
    var showDeleteConfirm by remember(provider.id) { mutableStateOf(false) }
    var showAutoSetupConfirm by remember(provider.id) { mutableStateOf(false) }
    LaunchedEffect(provider.id, editingModel?.id, provider.models.map { it.id }) {
        if (editingModel != null && provider.models.none { it.id == editingModel?.id }) editingModel = null
    }
    val feedbackMessage = viewModel.uiState.successMessage
    val feedbackText = feedbackMessage?.let { stringResource(it.key, *it.args.toTypedArray()) }
    LaunchedEffect(feedbackMessage) {
        if (feedbackMessage != null) { kotlinx.coroutines.delay(4000); viewModel.clearSuccessMessage() }
    }
    ProviderDetailContent(provider, viewModel.uiState.filteredModels, viewModel.uiState.searchQuery,
        viewModel.uiState.capabilityFilter,
        ProviderDetailActions(
            manageKeys = { showManageKeysDialog = true }, editCredentials = { showEditCredentialsDialog = true },
            disconnect = { viewModel.disconnectProvider(provider.id) },
            search = viewModel::updateSearchQuery, filter = viewModel::setCapabilityFilter,
            setAllEnabled = { viewModel.setAllModelsEnabled(provider.id, it) },
            refresh = { viewModel.autoFetchModels(provider.id) }, autoSetup = { showAutoSetupConfirm = true },
            addModel = { showAddManualModelDialog = true },
            toggleModel = { viewModel.toggleModelEnabled(provider.id, it.id) },
            editModel = { editingModel = it }, deleteModel = { viewModel.deleteModel(provider.id, it.id) },
            deleteProvider = { showDeleteConfirm = true },
        ), successMessage = feedbackText, isCompact = isCompact, colors = colors)

    // 弹窗部分
    if (showManageKeysDialog) {
        ApiKeyManagementDialog(provider, viewModel, colors, onDismiss = { showManageKeysDialog = false })
    }
    if (showEditCredentialsDialog) {
        EditProviderCredentialsDialog(
            provider = provider,
            colors = colors,
            onDismiss = { showEditCredentialsDialog = false },
            onSave = { newName, newBaseUrl, newApiKey, newReasoningLevels ->
                viewModel.saveProviderCredentials(
                    providerId = provider.id,
                    name = newName,
                    protocolType = provider.protocolType,
                    baseUrl = newBaseUrl,
                    apiKey = newApiKey,
                    reasoningLevels = newReasoningLevels
                )
                showEditCredentialsDialog = false
            }
        )
    }
    if (showAddManualModelDialog) {
        AddManualModelDialog(
            colors = colors,
            onDismiss = { showAddManualModelDialog = false },
            onAdd = { id, name, images, thinking, contextWindow, maxTokens, reasoningLevels ->
                viewModel.addManualModel(
                    providerId = provider.id,
                    modelId = id,
                    name = name,
                    supportsImages = images,
                    supportsThinking = thinking,
                    contextWindow = contextWindow,
                    maxTokens = maxTokens,
                    reasoningLevels = reasoningLevels
                )
                showAddManualModelDialog = false
            }
        )
    }
    editingModel?.let { captured ->
        // 解析最新状态：对话框打开期间用户覆盖/同步可能已更新模型，避免展示陈旧值
        val model = provider.models.find { it.id == captured.id }
        if (model != null) EditModelDialog(
            model = model,
            colors = colors,
            onDismiss = { editingModel = null },
            onSave = { newName, newImages, newThinking, newCw, newMt, newLevels ->
                if (model.origin == ModelOrigin.FETCHED) {
                    viewModel.setImageOverride(provider.id, model.id, newImages)
                } else {
                viewModel.updateModelConfig(
                    providerId = provider.id,
                    modelId = model.id,
                    name = newName,
                    supportsImages = newImages,
                    supportsThinking = newThinking,
                    contextWindow = newCw,
                    maxTokens = newMt,
                    reasoningLevels = newLevels
                )
                }
                editingModel = null
            }
        )
    }
    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            title = stringResource(Res.string.settings_panel_delete_provider_title),
            message = stringResource(Res.string.settings_panel_delete_provider_message, provider.name),
            colors = colors,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                viewModel.deleteCustomProvider(provider.id)
                showDeleteConfirm = false
                onDelete()
            }
        )
    }
    if (showAutoSetupConfirm) {
        ConfirmDialog(
            title = stringResource(Res.string.auto_setup_confirm_title),
            message = stringResource(Res.string.auto_setup_confirm_message),
            confirmLabel = stringResource(Res.string.auto_setup_confirm_ok),
            cancelLabel = stringResource(Res.string.settings_panel_cancel),
            onConfirm = {
                showAutoSetupConfirm = false
                viewModel.autoSetupModels(provider.id)
            },
            onDismiss = { showAutoSetupConfirm = false },
            danger = false,
            width = 380.dp
        )
    }
}

/** 行为闭包由Route绑定providerId；模型动作继续携带原ModelItem而非按显示名推测。 */
internal data class ProviderDetailActions(
    val manageKeys: () -> Unit,
    val editCredentials: () -> Unit,
    val disconnect: () -> Unit,
    val search: (String) -> Unit,
    val filter: (CapabilityFilter) -> Unit,
    val setAllEnabled: (Boolean) -> Unit,
    val refresh: () -> Unit,
    val autoSetup: () -> Unit,
    val addModel: () -> Unit,
    val toggleModel: (ModelItemUiState) -> Unit,
    val editModel: (ModelItemUiState) -> Unit,
    val deleteModel: (ModelItemUiState) -> Unit,
    val deleteProvider: () -> Unit,
)

/** 单一阅读滚动包含头部/凭据/工具栏/模型，避免小窗口或大字体时固定区挤掉所有模型。 */
@Composable
internal fun ProviderDetailContent(
    provider: ProviderItemUiState, models: List<ModelItemUiState>, query: String,
    filter: CapabilityFilter, actions: ProviderDetailActions,
    modifier: Modifier = Modifier, successMessage: String? = null, isCompact: Boolean = false,
    colors: MederiColors = LocalMederiColors.current,
) {
    val listState = remember(provider.id) { LazyListState() }
    LazyColumn(modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "header:identity") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(provider.name, style = WorkspaceStyle.Title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    if (!provider.isBuiltin) WorkspaceActionButton(stringResource(Res.string.settings_panel_delete),
                        actions.deleteProvider, tone = WorkspaceActionTone.Danger)
                }
                MetaBadge(provider.protocolType.displayName, colors)
                if (successMessage != null) Text(successMessage, style = WorkspaceStyle.Caption,
                    color = colors.accentText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        item(key = "header:credentials") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CredentialFieldItem(stringResource(Res.string.provider_base_url_label),
                    provider.baseUrl.ifBlank { stringResource(Res.string.settings_panel_not_set) }, colors)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CredentialFieldItem(stringResource(Res.string.provider_api_key_label),
                        if (provider.apiKey.isNotBlank()) provider.maskedApiKey else stringResource(Res.string.settings_panel_not_configured),
                        colors, modifier = Modifier.widthIn(max = 320.dp))
                    if (provider.reasoningLevels.isNotEmpty()) {
                        val levels = provider.reasoningLevels.keys.filter { ReasoningLevels.isActiveLevel(it) }
                            .ifEmpty { provider.reasoningLevels.keys.toList() }
                        val summary = if (levels.size > 3) "${levels.first()} - ${levels.last()} (${levels.size})" else levels.joinToString(" / ")
                        CredentialFieldItem(stringResource(Res.string.provider_reasoning_label), summary, colors,
                            modifier = Modifier.widthIn(max = 320.dp))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WorkspaceActionButton(stringResource(if (provider.apiKeys.isNotEmpty()) Res.string.settings_panel_manage_keys_count else Res.string.settings_panel_configure_keys,
                        *if (provider.apiKeys.isNotEmpty()) arrayOf<Any>(provider.apiKeys.size) else emptyArray()), actions.manageKeys)
                    if (!provider.isBuiltin) WorkspaceActionButton(stringResource(Res.string.settings_panel_edit_reasoning), actions.editCredentials)
                    if (provider.isConnected) WorkspaceActionButton(stringResource(Res.string.settings_panel_disconnect), actions.disconnect)
                }
            }
        }
        item(key = "header:model-toolbar") { ProviderModelToolbar(provider, query, filter, actions) }
        if (provider.syncStatus is ModelsSyncStatus.Error) item(key = "header:sync-error") {
            ErrorBoard(stringResource(Res.string.settings_panel_fetch_failed, provider.syncStatus.message),
                showContinue = true, onContinue = actions.refresh,
                continueLabel = stringResource(Res.string.settings_panel_refresh_models))
        }
        if (models.isEmpty()) item(key = "header:empty") {
            when (provider.syncStatus) {
                ModelsSyncStatus.Syncing -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceLoadingIndicator(Modifier.size(WorkspaceStyle.IconSize), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.settings_panel_syncing), style = WorkspaceStyle.Caption, color = colors.textSecondary)
                }
                is ModelsSyncStatus.Error -> Unit // 错误已通过共同ErrorBoard完整展示。
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(when (provider.syncStatus) {
                        ModelsSyncStatus.UnsupportedEndpoint -> Res.string.settings_panel_sync_unsupported
                        ModelsSyncStatus.Empty -> Res.string.settings_panel_sync_empty
                        else -> Res.string.settings_panel_no_matching_models
                    }), style = WorkspaceStyle.Body, color = colors.textSecondary)
                    if (provider.models.isEmpty() && provider.syncStatus == ModelsSyncStatus.Idle)
                        Text(stringResource(Res.string.settings_panel_sync_idle_hint), style = WorkspaceStyle.Caption, color = colors.textSecondary)
                }
            }
        }
        items(models, key = { "model:${it.id}" }) { model ->
            Column {
                ModelItemRow(model, colors, isCompact,
                    { actions.toggleModel(model) }, { actions.editModel(model) }, { actions.deleteModel(model) })
                HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
internal fun ProviderModelToolbar(provider: ProviderItemUiState, query: String, filter: CapabilityFilter, actions: ProviderDetailActions) {
    val syncing = provider.syncStatus == ModelsSyncStatus.Syncing
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderSearchField(query, stringResource(Res.string.settings_panel_search_models), actions.search)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WorkspaceActionButton(stringResource(Res.string.settings_panel_show_all), { actions.setAllEnabled(true) })
            WorkspaceActionButton(stringResource(Res.string.settings_panel_hide_all), { actions.setAllEnabled(false) })
            WorkspaceActionButton(stringResource(if (syncing) Res.string.settings_panel_syncing else Res.string.settings_panel_refresh_models),
                actions.refresh, enabled = !syncing, icon = FeatherIcons.RefreshCw)
            WorkspaceActionButton(stringResource(Res.string.settings_panel_auto_setup), actions.autoSetup)
            WorkspaceActionButton(stringResource(Res.string.settings_panel_add_model), actions.addModel, icon = FeatherIcons.Plus)
        }
        FlowRow(Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CapabilityFilter.entries.forEach { option ->
                WorkspaceNavigationTab(stringResource(when (option) {
                    CapabilityFilter.ALL -> Res.string.settings_filter_all_count
                    CapabilityFilter.ENABLED_ONLY -> Res.string.settings_filter_enabled_count
                    CapabilityFilter.REASONING -> Res.string.settings_filter_reasoning
                    CapabilityFilter.IMAGE -> Res.string.settings_filter_image
                    CapabilityFilter.FREE -> Res.string.settings_filter_free
                }, *when (option) {
                    CapabilityFilter.ALL -> arrayOf<Any>(provider.models.size)
                    CapabilityFilter.ENABLED_ONLY -> arrayOf<Any>(provider.enabledModelCount)
                    else -> emptyArray()
                }), option == filter, { actions.filter(option) }, role = Role.RadioButton)
            }
        }
    }
}
