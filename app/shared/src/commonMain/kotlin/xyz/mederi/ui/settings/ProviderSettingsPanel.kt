package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.util.openUrl
import xyz.mederi.theme.*
import xyz.mederi.ui.components.ErrorBoard
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspacePanelEmptyState

/** Route只装配VM和原详情/表单，目录与管理共用纯Content。 */
@Composable
fun ProviderSettingsPanel() {
    val appState = LocalAppState.current
    val vm: ProviderSettingsViewModel = viewModel { ProviderSettingsViewModel(appState) }
    val colors = LocalMederiColors.current
    val state = vm.uiState
    val authVm: ChatGptAuthorizationViewModel = viewModel { ChatGptAuthorizationViewModel(appState.aiCore) }
    ProviderSettingsContent(state, vm.builtinPresets,
        vm::updateProviderQuery, vm::updatePresetQuery, vm::openProviderCatalog,
        vm::returnToProviderManagement, vm::startCreateCustomProvider,
        vm::selectProvider, vm::selectProviderPreset, vm::clearMessages,
        onChatGpt = if (appState.aiCore.supportsChatGptLogin) { { authVm.start(null, vm::selectProvider, ::openUrl) } } else null,
        startingChatGpt = authVm.busy,
        authorizationError = authVm.error,
        detail = { provider, compact ->
            key(provider.id) {
                ProviderDetailWorkspace(provider, vm, colors, compact, onDelete = vm::returnToProviderManagement,
                    authorization = if (provider.isChatGptOAuth && appState.aiCore.supportsChatGptLogin) {
                        { ChatGptAuthorizationPanel(authVm, provider, ::openUrl) }
                    } else null)
            }
        }, custom = { compact ->
            CreateCustomProviderForm(vm, colors, compact, onCancel = vm::openProviderCatalog)
        }, preset = { name ->
            ProviderPresetCredentials(name, state.isSavingCredentials, { vm.addBuiltinProvider(name, it) }, vm::openProviderCatalog)
        })
}

@Composable
internal fun ProviderSettingsContent(
    state: ProviderSettingsUiState, presets: List<String>,
    onProviderQuery: (String) -> Unit, onPresetQuery: (String) -> Unit,
    onOpenCatalog: () -> Unit, onManage: () -> Unit, onCustom: () -> Unit,
    onSelectProvider: (String) -> Unit, onSelectPreset: (String) -> Unit, onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    detail: @Composable (ProviderItemUiState, Boolean) -> Unit,
    custom: @Composable (Boolean) -> Unit,
    preset: @Composable (String) -> Unit,
    onChatGpt: (() -> Unit)? = null, startingChatGpt: Boolean = false,
    authorizationError: String? = null,
) {
    val colors = LocalMederiColors.current
    val configuredList = rememberLazyListState()
    val catalogList = rememberLazyListState()
    // 改查询时从首个匹配开始；步骤返回未改变查询，因此不会把目录阅读位置重置。
    LaunchedEffect(state.presetQuery) { catalogList.scrollToItem(0) }
    LaunchedEffect(state.providerQuery, state.providerIdsFilter) { configuredList.scrollToItem(0) }
    val enabled = !state.isSavingCredentials
    BoxWithConstraints(modifier.fillMaxSize().padding(12.dp)) {
        val compact = maxWidth < 720.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.errorMessage?.let { error ->
                ErrorBoard(stringResource(error.key, *error.args.toTypedArray()), onDismiss = onDismissError,
                    modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
            }
            if (state.page == ProviderPage.Catalog && authorizationError != null) {
                ErrorBoard(stringResource(Res.string.chatgpt_error, authorizationError))
            }
            when (val page = state.page) {
                ProviderPage.Catalog -> ProviderCatalogContent(providerPresetItems(presets, state.providers, state.presetQuery),
                    state.presetQuery, onPresetQuery, onSelectPreset, onCustom, onManage,
                    catalogList, Modifier.weight(1f), enabled, onChatGpt, startingChatGpt)
                is ProviderPage.Preset -> Box(Modifier.weight(1f)) { preset(page.name) }
                ProviderPage.Custom -> Column(Modifier.weight(1f)) {
                    WorkspaceActionButton(stringResource(Res.string.provider_preset_back), onOpenCatalog, enabled = enabled, icon = FeatherIcons.ArrowLeft)
                    Box(Modifier.weight(1f)) { custom(compact) }
                }
                ProviderPage.Manage, ProviderPage.Detail -> {
                    if (compact && page == ProviderPage.Detail) {
                        WorkspaceActionButton(stringResource(Res.string.settings_panel_back_list), onManage, enabled = enabled, icon = FeatherIcons.ArrowLeft)
                        Box(Modifier.weight(1f)) {
                            state.selectedProvider?.let { detail(it, true) }
                                ?: WorkspacePanelEmptyState(FeatherIcons.Cpu, stringResource(Res.string.settings_panel_empty_select_hint))
                        }
                    } else {
                        if (state.providers.isEmpty()) {
                            ProviderManagementHeader(0, onOpenCatalog, enabled)
                            WorkspacePanelEmptyState(FeatherIcons.Cpu,
                                stringResource(Res.string.provider_manage_empty), stringResource(Res.string.provider_manage_empty_hint),
                                Modifier.weight(1f))
                        }
                        else Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Column(if (compact) Modifier.fillMaxSize() else Modifier.width(224.dp).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProviderManagementHeader(state.providers.size, onOpenCatalog, enabled)
                                ProviderSearchField(state.providerQuery, stringResource(Res.string.provider_manage_search), onProviderQuery, enabled)
                                if (state.providerIdsFilter != null) {
                                    Text(stringResource(Res.string.provider_filtered_configs), style = WorkspaceStyle.Caption, color = colors.textSecondary)
                                    WorkspaceActionButton(stringResource(Res.string.provider_show_all), { onProviderQuery("") })
                                }
                                if (state.filteredProviders.isEmpty()) WorkspacePanelEmptyState(FeatherIcons.Search,
                                    stringResource(Res.string.provider_search_empty), modifier = Modifier.weight(1f), action = {
                                        WorkspaceActionButton(stringResource(Res.string.provider_search_clear), { onProviderQuery("") })
                                    })
                                else LazyColumn(Modifier.weight(1f), state = configuredList,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(state.filteredProviders, key = { it.id }) { provider ->
                                        ProviderChoiceRow(provider.name,
                                            provider.baseUrl.ifBlank { stringResource(if (provider.isConnected) Res.string.provider_configured else Res.string.provider_without_key) },
                                            null, state.selectedProviderId == provider.id, { onSelectProvider(provider.id) },
                                            enabled = enabled, baseUrl = provider.baseUrl)
                                    }
                                }
                            }
                            if (!compact) Box(Modifier.weight(1f).fillMaxHeight()) {
                                state.selectedProvider?.let { detail(it, false) }
                                    ?: WorkspacePanelEmptyState(FeatherIcons.Cpu, stringResource(Res.string.settings_panel_empty_select_hint))
                            }
                        }
                    }
                }
            }
        }
    }
}


/** 管理工具栏属于左列；按真实文字宽决定横/纵，不靠FlowRow默认顶对齐或猜字符数。 */
@Composable
internal fun ProviderManagementHeader(
    count: Int, onAdd: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val countLabel = stringResource(Res.string.settings_panel_providers_count, count)
    val addLabel = stringResource(Res.string.settings_panel_add_provider)
    val measurer = rememberTextMeasurer()
    val countWidth = measurer.measure(AnnotatedString(countLabel), style = WorkspaceStyle.Caption, softWrap = false).size.width
    val addWidth = measurer.measure(AnnotatedString(addLabel), style = WorkspaceStyle.Body, softWrap = false).size.width
    val required = with(LocalDensity.current) { (countWidth + addWidth).toDp() } +
        MederiSpacing.MD * 2 + WorkspaceStyle.IconSize + MederiSpacing.Tight + MederiSpacing.SM
    val label: @Composable () -> Unit = {
        Text(countLabel, style = WorkspaceStyle.Caption, color = colors.textSecondary)
    }
    val action: @Composable () -> Unit = {
        WorkspaceActionButton(addLabel, onAdd, primary = true, enabled = enabled, icon = FeatherIcons.Plus)
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= required) Row(Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            label(); action()
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            label(); action()
        }
    }
}
