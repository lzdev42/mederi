package xyz.mederi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.launch
import xyz.mederi.core.contract.models.ModelOrigin
import xyz.mederi.core.contract.models.ProtocolType
import xyz.mederi.core.contract.models.ReasoningLevels
import xyz.mederi.core.ui.appstate.LocalAppState
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.close
import mederi.app.shared.generated.resources.input_default_tag
import mederi.app.shared.generated.resources.settings_filter_all_count
import mederi.app.shared.generated.resources.settings_filter_enabled_count
import mederi.app.shared.generated.resources.settings_filter_free
import mederi.app.shared.generated.resources.settings_filter_image
import mederi.app.shared.generated.resources.settings_filter_reasoning
import mederi.app.shared.generated.resources.settings_panel_add
import mederi.app.shared.generated.resources.settings_panel_add_key
import mederi.app.shared.generated.resources.settings_panel_add_key_title
import mederi.app.shared.generated.resources.settings_panel_add_model
import mederi.app.shared.generated.resources.settings_panel_add_model_title
import mederi.app.shared.generated.resources.settings_panel_add_provider
import mederi.app.shared.generated.resources.settings_panel_add_short
import mederi.app.shared.generated.resources.settings_panel_api_key_plain
import mederi.app.shared.generated.resources.settings_panel_api_key_secret_label
import mederi.app.shared.generated.resources.settings_panel_auto_setup
import mederi.app.shared.generated.resources.settings_panel_back_cd
import mederi.app.shared.generated.resources.settings_panel_back_list
import mederi.app.shared.generated.resources.settings_panel_badge_free
import mederi.app.shared.generated.resources.settings_panel_base_url_label
import mederi.app.shared.generated.resources.settings_panel_base_url_placeholder_local
import mederi.app.shared.generated.resources.settings_panel_builtin_base_url_hint
import mederi.app.shared.generated.resources.settings_panel_cancel
import mederi.app.shared.generated.resources.settings_panel_capability_image
import mederi.app.shared.generated.resources.settings_panel_capability_thinking
import mederi.app.shared.generated.resources.settings_panel_capability_thinking_levels
import mederi.app.shared.generated.resources.settings_panel_confirm_delete
import mederi.app.shared.generated.resources.settings_panel_configure_keys
import mederi.app.shared.generated.resources.settings_panel_connected
import mederi.app.shared.generated.resources.settings_panel_connecting
import mederi.app.shared.generated.resources.settings_panel_context_window
import mederi.app.shared.generated.resources.settings_panel_create_custom_desc
import mederi.app.shared.generated.resources.settings_panel_create_custom_title
import mederi.app.shared.generated.resources.settings_panel_delete
import mederi.app.shared.generated.resources.settings_panel_delete_provider_message
import mederi.app.shared.generated.resources.settings_panel_delete_provider_title
import mederi.app.shared.generated.resources.settings_panel_disconnect
import mederi.app.shared.generated.resources.settings_panel_disconnected
import mederi.app.shared.generated.resources.settings_panel_display_name
import mederi.app.shared.generated.resources.settings_panel_display_name_placeholder
import mederi.app.shared.generated.resources.settings_panel_done
import mederi.app.shared.generated.resources.settings_panel_edit
import mederi.app.shared.generated.resources.settings_panel_edit_model_title
import mederi.app.shared.generated.resources.settings_panel_edit_reasoning
import mederi.app.shared.generated.resources.settings_panel_empty_select_hint
import mederi.app.shared.generated.resources.settings_panel_fetch_failed
import mederi.app.shared.generated.resources.settings_panel_hide_all
import mederi.app.shared.generated.resources.settings_panel_image_input
import mederi.app.shared.generated.resources.settings_panel_key_alias
import mederi.app.shared.generated.resources.settings_panel_key_alias_placeholder
import mederi.app.shared.generated.resources.settings_panel_level_high_hint
import mederi.app.shared.generated.resources.settings_panel_level_low_hint
import mederi.app.shared.generated.resources.settings_panel_level_max_hint
import mederi.app.shared.generated.resources.settings_panel_level_medium_hint
import mederi.app.shared.generated.resources.settings_panel_level_none_hint
import mederi.app.shared.generated.resources.settings_panel_level_none_placeholder
import mederi.app.shared.generated.resources.settings_panel_manage_keys_count
import mederi.app.shared.generated.resources.settings_panel_manage_keys_title
import mederi.app.shared.generated.resources.settings_panel_max_output
import mederi.app.shared.generated.resources.settings_panel_metadata_hint
import mederi.app.shared.generated.resources.settings_panel_model_id_label
import mederi.app.shared.generated.resources.settings_panel_model_id_placeholder
import mederi.app.shared.generated.resources.settings_panel_model_info_title
import mederi.app.shared.generated.resources.settings_panel_name_placeholder
import mederi.app.shared.generated.resources.settings_panel_no_keys
import mederi.app.shared.generated.resources.settings_panel_no_matching_models
import mederi.app.shared.generated.resources.settings_panel_not_configured
import mederi.app.shared.generated.resources.settings_panel_not_set
import mederi.app.shared.generated.resources.settings_panel_override_badge
import mederi.app.shared.generated.resources.settings_panel_protocol_type
import mederi.app.shared.generated.resources.settings_panel_provider_name_label
import mederi.app.shared.generated.resources.settings_panel_provider_name_placeholder
import mederi.app.shared.generated.resources.settings_panel_providers_count
import mederi.app.shared.generated.resources.settings_panel_reasoning_edit_hint
import mederi.app.shared.generated.resources.settings_panel_reasoning_optional_hint
import mederi.app.shared.generated.resources.settings_panel_reasoning_optional_title
import mederi.app.shared.generated.resources.settings_panel_recommended_presets
import mederi.app.shared.generated.resources.settings_panel_refresh_models
import mederi.app.shared.generated.resources.settings_panel_refresh_short
import mederi.app.shared.generated.resources.settings_panel_save_config
import mederi.app.shared.generated.resources.settings_panel_save_connect
import mederi.app.shared.generated.resources.settings_panel_save_key
import mederi.app.shared.generated.resources.settings_panel_save_reconnect
import mederi.app.shared.generated.resources.settings_panel_search_models
import mederi.app.shared.generated.resources.settings_panel_set_default
import mederi.app.shared.generated.resources.settings_panel_set_default_key
import mederi.app.shared.generated.resources.settings_panel_show_all
import mederi.app.shared.generated.resources.settings_panel_spec_context
import mederi.app.shared.generated.resources.settings_panel_spec_output
import mederi.app.shared.generated.resources.settings_panel_support_images
import mederi.app.shared.generated.resources.settings_panel_support_thinking
import mederi.app.shared.generated.resources.settings_panel_supported
import mederi.app.shared.generated.resources.settings_panel_sync_empty
import mederi.app.shared.generated.resources.settings_panel_sync_idle_hint
import mederi.app.shared.generated.resources.settings_panel_sync_unsupported
import mederi.app.shared.generated.resources.settings_panel_syncing
import mederi.app.shared.generated.resources.settings_panel_thinking_label
import mederi.app.shared.generated.resources.settings_panel_thinking_levels_title
import mederi.app.shared.generated.resources.settings_panel_unknown
import mederi.app.shared.generated.resources.settings_panel_unsupported
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.util.formatContextWindow
import org.jetbrains.compose.resources.stringResource

// ============================================================================
// 1. 设计 Token 定义 (Design Tokens)
// ============================================================================
private object ProviderTokens {
    val SpacingXSmall = 4.dp
    val SpacingSmall = 8.dp
    val SpacingMedium = 12.dp
    val SpacingLarge = 16.dp
    val SpacingXLarge = 24.dp

    val RadiusBadge = RoundedCornerShape(4.dp)
    val RadiusControl = RoundedCornerShape(6.dp)
    val RadiusCard = RoundedCornerShape(8.dp)
    val RadiusWorkspace = RoundedCornerShape(10.dp)

    val FontTitle = 13.5.sp
    val FontValue = 12.sp
    val FontLabel = 11.sp
    val FontBadge = 10.5.sp
}

/**
 * 供应商与模型管理面板（支持桌面端 Master-Detail 双栏 与 移动端单栏下钻自适应）。
 *
 * 遵循 Linear / Raycast / Vercel 风格规范：
 * 1. 克制、精密、信息密度高但不拥挤
 * 2. 严格的 8px 间距阶梯与三级字阶
 * 3. 统一标签（Badge）容器体系与灰阶中性色彩策略
 * 4. 物理拆分“数据展示区”与“操作区”
 * 5. 常驻固定的「刷新模型」与「+ 添加模型」入口，杜绝突兀冗余的 status bar
 */
@Composable
fun ProviderSettingsPanel() {
    val appState = LocalAppState.current
    // 经 ViewModelStore 管理：设置页关闭/切换 tab 不再丢失 VM 内状态，onCleared 协程清理可靠
    val viewModel: ProviderSettingsViewModel = viewModel { ProviderSettingsViewModel(appState) }
    val colors = LocalMederiColors.current
    val uiState = viewModel.uiState
    var addingBuiltin by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 640.dp
        var showMobileDetail by remember { mutableStateOf(false) }

        if (isCompact) {
            // ========================================================
            // 移动端单栏下钻模式 (Mobile Drill-down Layout)
            // ========================================================
            if (!showMobileDetail) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.surfaceWorkspace)
                        .padding(ProviderTokens.SpacingMedium)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
                ) {
                    AddProviderSidebarButton(
                        isSelected = false,
                        colors = colors,
                        onClick = {
                            viewModel.startCreateCustomProvider()
                            showMobileDetail = true
                        }
                    )

                    BuiltinPresetsSection(
                        presets = viewModel.builtinPresets,
                        existingProviderNames = uiState.providers.map { it.name }.toSet(),
                        colors = colors,
                        onAddPreset = { addingBuiltin = it }
                    )

                    Text(
                        text = stringResource(Res.string.settings_panel_providers_count, uiState.providers.size),
                        color = colors.textMuted,
                        fontSize = ProviderTokens.FontLabel,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )

                    uiState.providers.forEach { provider ->
                        ProviderSidebarRow(
                            provider = provider,
                            isSelected = uiState.selectedProviderId == provider.id,
                            colors = colors,
                            onClick = {
                                viewModel.selectProvider(provider.id)
                                showMobileDetail = true
                            }
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.surfaceWorkspace)
                        .padding(ProviderTokens.SpacingMedium),
                    verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showMobileDetail = false }
                            .padding(vertical = ProviderTokens.SpacingXSmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingXSmall)
                    ) {
                        Icon(FeatherIcons.ArrowLeft, stringResource(Res.string.settings_panel_back_cd), tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                        Text(stringResource(Res.string.settings_panel_back_list), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
                    }

                    HorizontalDivider(color = colors.divider)

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (uiState.isCreatingCustom) {
                            CreateCustomProviderForm(
                                viewModel = viewModel,
                                colors = colors,
                                isCompact = true,
                                onCancel = { showMobileDetail = false }
                            )
                        } else if (uiState.selectedProvider != null) {
                            ProviderDetailWorkspace(
                                provider = uiState.selectedProvider!!,
                                viewModel = viewModel,
                                colors = colors,
                                isCompact = true,
                                onDelete = { showMobileDetail = false }
                            )
                        }
                    }
                }
            }
        } else {
            // ========================================================
            // 桌面端 Master-Detail 双栏并排模式 (Desktop Layout)
            // ========================================================
            Row(modifier = Modifier.fillMaxSize()) {
                // 左侧导航栏 (Master Navigation)
                Column(
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                        .background(colors.surfaceSidebar)
                        .padding(ProviderTokens.SpacingSmall),
                    verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
                ) {
                    AddProviderSidebarButton(
                        isSelected = uiState.isCreatingCustom,
                        colors = colors,
                        onClick = { viewModel.startCreateCustomProvider() }
                    )

                    BuiltinPresetsSection(
                        presets = viewModel.builtinPresets,
                        existingProviderNames = uiState.providers.map { it.name }.toSet(),
                        colors = colors,
                        onAddPreset = { addingBuiltin = it }
                    )

                    HorizontalDivider(color = colors.divider)

                    Text(
                        text = stringResource(Res.string.settings_panel_providers_count, uiState.providers.size),
                        color = colors.textMuted,
                        fontSize = ProviderTokens.FontLabel,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingXSmall)
                    ) {
                        uiState.providers.forEach { provider ->
                            val isSelected = !uiState.isCreatingCustom && uiState.selectedProviderId == provider.id
                            ProviderSidebarRow(
                                provider = provider,
                                isSelected = isSelected,
                                colors = colors,
                                onClick = { viewModel.selectProvider(provider.id) }
                            )
                        }
                    }
                }

                // 竖向细分割线
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.divider))

                // 右侧工作台 (Detail Workspace)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(colors.surfaceWorkspace)
                        .padding(ProviderTokens.SpacingLarge)
                ) {
                    when {
                        uiState.isCreatingCustom -> {
                            CreateCustomProviderForm(
                                viewModel = viewModel,
                                colors = colors,
                                isCompact = false
                            )
                        }

                        uiState.selectedProvider != null -> {
                            ProviderDetailWorkspace(
                                provider = uiState.selectedProvider!!,
                                viewModel = viewModel,
                                colors = colors,
                                isCompact = false
                            )
                        }

                        else -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(Res.string.settings_panel_empty_select_hint), color = colors.textMuted, fontSize = ProviderTokens.FontValue)
                            }
                        }
                    }
                }
            }
        }
    }

    addingBuiltin?.let { preset ->
        AddBuiltinProviderDialog(
            providerName = preset,
            colors = colors,
            onDismiss = { addingBuiltin = null },
            onAdd = { apiKey ->
                viewModel.addBuiltinProvider(preset, apiKey)
                addingBuiltin = null
            }
        )
    }
}

// ============================================================================
// 2. 侧边栏与预设组件
// ============================================================================

@Composable
private fun BuiltinPresetsSection(
    presets: List<String>,
    existingProviderNames: Set<String>,
    colors: MederiColors,
    onAddPreset: (String) -> Unit
) {
    val unaddedPresets = presets.filter { preset ->
        existingProviderNames.none { it.equals(preset, ignoreCase = true) }
    }
    if (unaddedPresets.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ProviderTokens.SpacingXSmall),
        verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingXSmall)
    ) {
        Text(
            text = stringResource(Res.string.settings_panel_recommended_presets),
            color = colors.textMuted,
            fontSize = ProviderTokens.FontLabel,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        unaddedPresets.forEach { preset ->
            Surface(
                onClick = { onAddPreset(preset) },
                shape = ProviderTokens.RadiusControl,
                color = colors.surfaceCard,
                border = BorderStroke(1.dp, colors.divider),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = ProviderTokens.SpacingSmall, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = preset,
                        color = colors.textPrimary,
                        fontSize = ProviderTokens.FontValue,
                        fontWeight = FontWeight.Medium
                    )
                    Text(stringResource(Res.string.settings_panel_add), color = colors.accentPrimary, fontSize = ProviderTokens.FontBadge)
                }
            }
        }
    }
}

@Composable
private fun AddBuiltinProviderDialog(
    providerName: String,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ProviderTokens.RadiusCard,
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.divider),
            modifier = Modifier.width(380.dp).padding(ProviderTokens.SpacingLarge)
        ) {
            Column(Modifier.padding(ProviderTokens.SpacingLarge), verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingMedium)) {
                Text(stringResource(Res.string.settings_panel_add_provider), color = colors.textPrimary, fontSize = ProviderTokens.FontTitle, fontWeight = FontWeight.SemiBold)
                HorizontalDivider(color = colors.divider)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)) {
                    Box(
                        Modifier.size(24.dp).clip(ProviderTokens.RadiusBadge).background(colors.surfaceInput),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(FeatherIcons.Cpu, null, tint = colors.textSecondary, modifier = Modifier.size(13.dp))
                    }
                    Column {
                        Text(providerName, color = colors.textPrimary, fontSize = ProviderTokens.FontValue, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(Res.string.settings_panel_builtin_base_url_hint), color = colors.textMuted, fontSize = ProviderTokens.FontLabel)
                    }
                }
                LabeledTextField(
                    label = "API Key",
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = "sk-...",
                    isPassword = true,
                    colors = colors
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss) { Text(stringResource(Res.string.settings_panel_cancel), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel) }
                    Spacer(Modifier.width(ProviderTokens.SpacingSmall))
                    PrimaryActionBtn(
                        text = stringResource(Res.string.settings_panel_add),
                        colors = colors,
                        enabled = apiKey.isNotBlank(),
                        onClick = { onAdd(apiKey.trim()) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddProviderSidebarButton(
    isSelected: Boolean,
    colors: MederiColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = ProviderTokens.RadiusControl,
        color = if (isSelected) colors.surfaceHover else colors.surfaceCard,
        border = BorderStroke(1.dp, if (isSelected) colors.divider else colors.divider),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ProviderTokens.SpacingSmall, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
        ) {
            Icon(
                imageVector = FeatherIcons.Plus,
                contentDescription = null,
                tint = if (isSelected) colors.accentPrimary else colors.textSecondary,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = stringResource(Res.string.settings_panel_add_provider),
                color = if (isSelected) colors.accentPrimary else colors.textPrimary,
                fontSize = ProviderTokens.FontValue,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ProviderSidebarRow(
    provider: ProviderItemUiState,
    isSelected: Boolean,
    colors: MederiColors,
    onClick: () -> Unit
) {
    val bg = if (isSelected) colors.surfaceHover else Color.Transparent
    val border = if (isSelected) colors.divider else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ProviderTokens.RadiusCard)
            .background(bg)
            .border(1.dp, border, ProviderTokens.RadiusCard)
            .clickable { onClick() }
            .padding(horizontal = ProviderTokens.SpacingSmall, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall),
            modifier = Modifier.weight(1f)
        ) {
            // 状态圆点：绿点(已连接) / 灰点(未连接)
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (provider.isConnected) colors.accentSuccess else colors.textMuted.copy(alpha = 0.4f))
            )

            Text(
                text = provider.name,
                color = if (isSelected) colors.textPrimary else colors.textSecondary,
                fontSize = ProviderTokens.FontValue,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 统一角标容器：统一展示 1/1 或 0
        CountBadge(
            countText = if (provider.isConnected) "${provider.enabledModelCount}/${provider.models.size}" else "0",
            colors = colors
        )
    }
}

// ============================================================================
// 3. 右侧工作台：新建自定义供应商表单
// ============================================================================

@Composable
private fun CreateCustomProviderForm(
    viewModel: ProviderSettingsViewModel,
    colors: MederiColors,
    isCompact: Boolean = false,
    onCancel: () -> Unit = {}
) {
    var name by remember { mutableStateOf("") }
    var protocolType by remember { mutableStateOf(ProtocolType.DEFAULT) }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var reasoningLevels by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    val isSaving = viewModel.uiState.isSavingCredentials

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingLarge)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.settings_panel_create_custom_title), color = colors.textPrimary, fontSize = ProviderTokens.FontTitle, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.settings_panel_create_custom_desc), color = colors.textMuted, fontSize = ProviderTokens.FontLabel)
        }

        HorizontalDivider(color = colors.divider)

        LabeledTextField(
            label = stringResource(Res.string.settings_panel_provider_name_label),
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(Res.string.settings_panel_provider_name_placeholder),
            colors = colors
        )

        ProtocolSelectorBar(
            selected = protocolType,
            onSelect = { protocolType = it },
            colors = colors
        )

        LabeledTextField(
            label = stringResource(Res.string.settings_panel_base_url_label),
            value = baseUrl,
            onValueChange = { baseUrl = it },
            placeholder = if (protocolType == ProtocolType.GOOGLE) {
                ProtocolType.GOOGLE.placeholderUrl
            } else {
                stringResource(Res.string.settings_panel_base_url_placeholder_local, ProtocolType.OPENAI_CHAT.placeholderUrl)
            },
            colors = colors
        )

        LabeledTextField(
            label = stringResource(Res.string.settings_panel_api_key_secret_label),
            value = apiKey,
            onValueChange = { apiKey = it },
            placeholder = "sk-...",
            isPassword = true,
            colors = colors
        )

        // 推理参数区块（v6：每级别一段请求体 JSON，根级合并；用户填什么发什么）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ProviderTokens.RadiusCard)
                .background(colors.surfaceCard)
                .border(1.dp, colors.divider, ProviderTokens.RadiusCard)
                .padding(ProviderTokens.SpacingMedium),
            verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
        ) {
            Text(stringResource(Res.string.settings_panel_reasoning_optional_title), color = colors.textPrimary, fontSize = ProviderTokens.FontValue, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(Res.string.settings_panel_reasoning_optional_hint),
                color = colors.textMuted, fontSize = ProviderTokens.FontLabel
            )
            ReasoningLevelsEditor(
                levels = reasoningLevels,
                onChange = { reasoningLevels = it },
                colors = colors
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    onCancel()
                    val first = viewModel.uiState.providers.firstOrNull()?.id
                    if (first != null) viewModel.selectProvider(first)
                }
            ) {
                Text(stringResource(Res.string.settings_panel_cancel), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
            }

            Spacer(modifier = Modifier.width(ProviderTokens.SpacingSmall))

            PrimaryActionBtn(
                text = if (isSaving) stringResource(Res.string.settings_panel_connecting) else stringResource(Res.string.settings_panel_save_connect),
                colors = colors,
                enabled = name.isNotBlank() && baseUrl.isNotBlank() && !isSaving,
                onClick = {
                    viewModel.saveProviderCredentials(
                        providerId = null,
                        name = name,
                        protocolType = protocolType,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        reasoningLevels = reasoningLevels
                    )
                }
            )
        }
    }
}

// ============================================================================
// 4. 右侧工作台：供应商详情与模型管理
// ============================================================================

@Composable
private fun ProviderDetailWorkspace(
    provider: ProviderItemUiState,
    viewModel: ProviderSettingsViewModel,
    colors: MederiColors,
    isCompact: Boolean = false,
    onDelete: () -> Unit = {}
) {
    var showEditCredentialsDialog by remember { mutableStateOf(false) }
    var showManageKeysDialog by remember { mutableStateOf(false) }
    var showAddManualModelDialog by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<ModelItemUiState?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val filteredModels = viewModel.uiState.filteredModels
    val isSyncing = provider.syncStatus is ModelsSyncStatus.Syncing

    // 操作反馈条（刷新/保存等成功与失败提示），4 秒后自动清除
    val feedbackMessage = viewModel.uiState.errorMessage ?: viewModel.uiState.successMessage
    val isFeedbackError = viewModel.uiState.errorMessage != null
    val feedbackText = feedbackMessage?.let { stringResource(it.key, *it.args.toTypedArray()) }
    LaunchedEffect(feedbackMessage) {
        if (feedbackMessage != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.clearMessages()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingMedium)
    ) {
        // 操作反馈（成功 / 失败）
        if (feedbackText != null) {
            Text(
                text = feedbackText,
                color = if (isFeedbackError) colors.accentDanger else colors.accentPrimary,
                fontSize = ProviderTokens.FontLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ProviderTokens.RadiusBadge)
                    .background(
                        if (isFeedbackError) colors.accentDanger.copy(alpha = 0.08f)
                        else colors.accentPrimary.copy(alpha = 0.08f)
                    )
                    .padding(horizontal = ProviderTokens.SpacingMedium, vertical = ProviderTokens.SpacingXSmall)
            )
        }

        // ----------------------------------------------------
        // A. 顶部 Header：供应商名称 + 协议 + 状态与删除
        // ----------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
            ) {
                Text(
                    text = provider.name,
                    color = colors.textPrimary,
                    fontSize = ProviderTokens.FontTitle,
                    fontWeight = FontWeight.SemiBold
                )
                MetaBadge(text = provider.protocolType.displayName, colors = colors)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingMedium)
            ) {
                StatusIndicator(isConnected = provider.isConnected, colors = colors)

                if (!provider.isBuiltin) {
                    Text(
                        text = stringResource(Res.string.settings_panel_delete),
                        color = colors.accentDanger,
                        fontSize = ProviderTokens.FontLabel,
                        modifier = Modifier
                            .clickable { showDeleteConfirm = true }
                            .padding(ProviderTokens.SpacingXSmall)
                    )
                }
            }
        }

        // ----------------------------------------------------
        // B. 认证凭据卡片：数据展示区 与 操作区 物理拆分
        // ----------------------------------------------------
        Surface(
            shape = ProviderTokens.RadiusCard,
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.divider),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // 数据展示区：Label + Value 两行式网格
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(ProviderTokens.SpacingLarge),
                    horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingXLarge)
                ) {
                    CredentialFieldItem(
                        label = "BASE URL",
                        value = provider.baseUrl.ifBlank { stringResource(Res.string.settings_panel_not_set) },
                        colors = colors,
                        modifier = Modifier.weight(1.4f)
                    )

                    CredentialFieldItem(
                        label = "API KEY",
                        value = if (provider.apiKey.isNotBlank()) provider.maskedApiKey else stringResource(Res.string.settings_panel_not_configured),
                        colors = colors,
                        isWarn = provider.apiKey.isBlank(),
                        modifier = Modifier.weight(1f)
                    )

                    if (!isCompact && provider.reasoningLevels.isNotEmpty()) {
                        val configuredLevels = provider.reasoningLevels.keys.joinToString("/")
                        CredentialFieldItem(
                            label = "REASONING",
                            value = configuredLevels,
                            colors = colors,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(color = colors.divider.copy(alpha = 0.6f))

                // 操作按钮组：独立区域，分级清晰
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceSidebar.copy(alpha = 0.5f))
                        .padding(horizontal = ProviderTokens.SpacingLarge, vertical = ProviderTokens.SpacingSmall),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)) {
                        GhostActionBtn(
                            text = if (provider.apiKeys.isNotEmpty()) {
                                stringResource(Res.string.settings_panel_manage_keys_count, provider.apiKeys.size)
                            } else {
                                stringResource(Res.string.settings_panel_configure_keys)
                            },
                            colors = colors
                        ) { showManageKeysDialog = true }

                        // 推理参数编辑仅自定义供应商：内置参数按官方文档内置（启动同步会覆盖用户改动），不开放
                        if (!provider.isBuiltin) {
                            GhostActionBtn(text = stringResource(Res.string.settings_panel_edit_reasoning), colors = colors) {
                                showEditCredentialsDialog = true
                            }
                        }
                    }

                    if (provider.isConnected) {
                        Text(
                            text = stringResource(Res.string.settings_panel_disconnect),
                            color = colors.textMuted,
                            fontSize = ProviderTokens.FontLabel,
                            modifier = Modifier
                                .clickable { viewModel.disconnectProvider(provider.id) }
                                .padding(horizontal = ProviderTokens.SpacingSmall, vertical = ProviderTokens.SpacingXSmall)
                        )
                    }
                }
            }
        }

        // ----------------------------------------------------
        // C. 模型管理工具栏（含常驻的刷新模型与添加模型按钮）
        // ----------------------------------------------------
        if (isCompact) {
            // 移动端折叠工具行
            Column(verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(ProviderTokens.RadiusControl)
                            .background(colors.surfaceInput)
                            .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
                            .padding(horizontal = ProviderTokens.SpacingSmall),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingXSmall)
                        ) {
                            Icon(FeatherIcons.Search, null, tint = colors.textMuted, modifier = Modifier.size(12.dp))
                            BasicTextField(
                                value = viewModel.uiState.searchQuery,
                                onValueChange = { viewModel.updateSearchQuery(it) },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = ProviderTokens.FontLabel, color = colors.textPrimary),
                                cursorBrush = SolidColor(colors.accentPrimary),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    if (viewModel.uiState.searchQuery.isEmpty()) {
                                        Text(stringResource(Res.string.settings_panel_search_models), fontSize = ProviderTokens.FontLabel, color = colors.textMuted)
                                    }
                                    inner()
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(ProviderTokens.SpacingSmall))

                    // 固定的刷新模型按钮
                    Box(
                        modifier = Modifier
                            .clip(ProviderTokens.RadiusControl)
                            .background(colors.surfaceCard)
                            .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
                            .clickable(enabled = !isSyncing) { viewModel.autoFetchModels(provider.id) }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    strokeWidth = 1.5.dp,
                                    modifier = Modifier.size(11.dp),
                                    color = colors.accentPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = FeatherIcons.RefreshCw,
                                    contentDescription = stringResource(Res.string.settings_panel_refresh_models),
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                            Text(stringResource(Res.string.settings_panel_refresh_short), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
                        }
                    }

                    // 「自动设置」：显式应用目录元数据（系统唯一自动写入通道，用户覆盖优先）
                    Box(
                        modifier = Modifier
                            .clip(ProviderTokens.RadiusControl)
                            .background(colors.surfaceCard)
                            .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
                            .clickable { viewModel.autoSetupModels(provider.id) }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(stringResource(Res.string.settings_panel_auto_setup), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
                    }

                    Spacer(modifier = Modifier.width(ProviderTokens.SpacingXSmall))

                    // 添加模型按钮
                    Box(
                        modifier = Modifier
                            .clip(ProviderTokens.RadiusControl)
                            .background(colors.surfaceCard)
                            .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
                            .clickable { showAddManualModelDialog = true }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(stringResource(Res.string.settings_panel_add_short), color = colors.textPrimary, fontSize = ProviderTokens.FontLabel, fontWeight = FontWeight.Medium)
                    }
                }

                // 移动端水平滚动的 Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingXSmall),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.settings_panel_show_all),
                        color = colors.textMuted,
                        fontSize = ProviderTokens.FontLabel,
                        modifier = Modifier
                            .clickable { viewModel.setAllModelsEnabled(provider.id, true) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    Text(
                        text = stringResource(Res.string.settings_panel_hide_all),
                        color = colors.textMuted,
                        fontSize = ProviderTokens.FontLabel,
                        modifier = Modifier
                            .clickable { viewModel.setAllModelsEnabled(provider.id, false) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    CapabilityFilter.entries.forEach { filter ->
                        val isSel = viewModel.uiState.capabilityFilter == filter
                        val label = when (filter) {
                            CapabilityFilter.ALL -> stringResource(Res.string.settings_filter_all_count, provider.models.size)
                            CapabilityFilter.ENABLED_ONLY -> stringResource(Res.string.settings_filter_enabled_count, provider.enabledModelCount)
                            CapabilityFilter.REASONING -> stringResource(Res.string.settings_filter_reasoning)
                            CapabilityFilter.IMAGE -> stringResource(Res.string.settings_filter_image)
                            CapabilityFilter.FREE -> stringResource(Res.string.settings_filter_free)
                        }
                        Box(
                            modifier = Modifier
                                .clip(ProviderTokens.RadiusBadge)
                                .background(if (isSel) colors.surfaceCard else colors.surfaceInput)
                                .border(1.dp, if (isSel) colors.accentPrimary else colors.divider, ProviderTokens.RadiusBadge)
                            .clickable { viewModel.setCapabilityFilter(filter) }
                            .padding(horizontal = ProviderTokens.SpacingSmall, vertical = 3.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (isSel) colors.textPrimary else colors.textMuted,
                                fontSize = ProviderTokens.FontBadge
                            )
                        }
                    }
                }
            }
        } else {
            // 桌面端横向工具栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 搜索框
                Box(
                    modifier = Modifier
                        .width(180.dp)
                        .height(28.dp)
                        .clip(ProviderTokens.RadiusControl)
                        .background(colors.surfaceInput)
                        .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
                        .padding(horizontal = ProviderTokens.SpacingSmall),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingXSmall)
                    ) {
                        Icon(FeatherIcons.Search, null, tint = colors.textMuted, modifier = Modifier.size(12.dp))
                        BasicTextField(
                            value = viewModel.uiState.searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = ProviderTokens.FontLabel, color = colors.textPrimary),
                            cursorBrush = SolidColor(colors.accentPrimary),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (viewModel.uiState.searchQuery.isEmpty()) {
                                    Text(stringResource(Res.string.settings_panel_search_models), fontSize = ProviderTokens.FontLabel, color = colors.textMuted)
                                }
                                inner()
                            }
                        )
                    }
                }

                // 统一风格的 Segmented Filter 控制器
                Row(
                    modifier = Modifier
                        .clip(ProviderTokens.RadiusControl)
                        .background(colors.surfaceInput)
                        .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CapabilityFilter.entries.forEach { filter ->
                        val isSel = viewModel.uiState.capabilityFilter == filter
                        val label = when (filter) {
                            CapabilityFilter.ALL -> stringResource(Res.string.settings_filter_all_count, provider.models.size)
                            CapabilityFilter.ENABLED_ONLY -> stringResource(Res.string.settings_filter_enabled_count, provider.enabledModelCount)
                            CapabilityFilter.REASONING -> stringResource(Res.string.settings_filter_reasoning)
                            CapabilityFilter.IMAGE -> stringResource(Res.string.settings_filter_image)
                            CapabilityFilter.FREE -> stringResource(Res.string.settings_filter_free)
                        }
                        Box(
                            modifier = Modifier
                                .clip(ProviderTokens.RadiusBadge)
                                .background(if (isSel) colors.surfaceCard else Color.Transparent)
                                .clickable { viewModel.setCapabilityFilter(filter) }
                                .padding(horizontal = ProviderTokens.SpacingSmall, vertical = 3.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (isSel) colors.textPrimary else colors.textMuted,
                                fontSize = ProviderTokens.FontBadge,
                                fontWeight = if (isSel) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                }

                // 批量操作、刷新模型与新增
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
                ) {
                    Text(
                        text = stringResource(Res.string.settings_panel_show_all),
                        color = colors.textMuted,
                        fontSize = ProviderTokens.FontLabel,
                        modifier = Modifier.clickable { viewModel.setAllModelsEnabled(provider.id, true) }
                    )
                    Text(
                        text = stringResource(Res.string.settings_panel_hide_all),
                        color = colors.textMuted,
                        fontSize = ProviderTokens.FontLabel,
                        modifier = Modifier.clickable { viewModel.setAllModelsEnabled(provider.id, false) }
                    )

                    // 固定的刷新模型按钮
                    Box(
                        modifier = Modifier
                            .clip(ProviderTokens.RadiusControl)
                            .background(colors.surfaceCard)
                            .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
                            .clickable(enabled = !isSyncing) { viewModel.autoFetchModels(provider.id) }
                            .padding(horizontal = ProviderTokens.SpacingSmall, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingXSmall)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    strokeWidth = 1.5.dp,
                                    modifier = Modifier.size(11.dp),
                                    color = colors.accentPrimary
                                )
                                Text(
                                    text = stringResource(Res.string.settings_panel_syncing),
                                    color = colors.textSecondary,
                                    fontSize = ProviderTokens.FontLabel
                                )
                            } else {
                                Icon(
                                    imageVector = FeatherIcons.RefreshCw,
                                    contentDescription = stringResource(Res.string.settings_panel_refresh_models),
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = stringResource(Res.string.settings_panel_refresh_models),
                                    color = colors.textSecondary,
                                    fontSize = ProviderTokens.FontLabel
                                )
                            }
                        }
                    }

                    // 「自动设置」：显式应用目录元数据（系统唯一自动写入通道，用户覆盖优先）
                    Box(
                        modifier = Modifier
                            .clip(ProviderTokens.RadiusControl)
                            .background(colors.surfaceCard)
                            .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
                            .clickable { viewModel.autoSetupModels(provider.id) }
                            .padding(horizontal = ProviderTokens.SpacingSmall, vertical = 4.dp)
                    ) {
                        Text(stringResource(Res.string.settings_panel_auto_setup), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
                    }

                    Box(
                        modifier = Modifier
                            .clip(ProviderTokens.RadiusControl)
                            .background(colors.surfaceCard)
                            .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
                            .clickable { showAddManualModelDialog = true }
                            .padding(horizontal = ProviderTokens.SpacingSmall, vertical = 4.dp)
                    ) {
                        Text(stringResource(Res.string.settings_panel_add_model), color = colors.textPrimary, fontSize = ProviderTokens.FontLabel, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ----------------------------------------------------
        // D. 模型列表区（内容自适应）
        // ----------------------------------------------------
        if (filteredModels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ProviderTokens.RadiusCard)
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.divider, ProviderTokens.RadiusCard)
                    .padding(ProviderTokens.SpacingXLarge),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
                ) {
                    when (val status = provider.syncStatus) {
                        is ModelsSyncStatus.Error -> {
                            Text(
                                text = stringResource(Res.string.settings_panel_fetch_failed, status.message),
                                color = colors.accentDanger,
                                fontSize = ProviderTokens.FontLabel
                            )
                        }
                        is ModelsSyncStatus.UnsupportedEndpoint -> {
                            Text(
                                text = stringResource(Res.string.settings_panel_sync_unsupported),
                                color = colors.textMuted,
                                fontSize = ProviderTokens.FontLabel
                            )
                        }
                        is ModelsSyncStatus.Empty -> {
                            Text(
                                text = stringResource(Res.string.settings_panel_sync_empty),
                                color = colors.textMuted,
                                fontSize = ProviderTokens.FontLabel
                            )
                        }
                        else -> {
                            Text(stringResource(Res.string.settings_panel_no_matching_models), color = colors.textMuted, fontSize = ProviderTokens.FontLabel)
                            if (provider.models.isEmpty()) {
                                Text(stringResource(Res.string.settings_panel_sync_idle_hint), color = colors.textMuted, fontSize = ProviderTokens.FontLabel)
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(ProviderTokens.RadiusCard)
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.divider, ProviderTokens.RadiusCard)
            ) {
                items(filteredModels, key = { it.id }) { model ->
                    ModelItemRow(
                        model = model,
                        colors = colors,
                        isCompact = isCompact,
                        onToggleEnabled = { viewModel.toggleModelEnabled(provider.id, model.id) },
                        onEdit = { editingModel = model },
                        onDelete = { viewModel.deleteModel(provider.id, model.id) }
                    )
                    HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
                }
            }
        }
    }

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
        val model = provider.models.find { it.id == captured.id } ?: captured
        EditModelDialog(
            model = model,
            colors = colors,
            onDismiss = { editingModel = null },
            onImageOverride = { supported ->
                viewModel.setImageOverride(provider.id, model.id, supported)
            },
            onSave = { newName, newImages, newThinking, newCw, newMt, newLevels ->
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
}

// ============================================================================
// 5. 模型列表单项组件
// ============================================================================

@Composable
private fun ModelItemRow(
    model: ModelItemUiState,
    colors: MederiColors,
    isCompact: Boolean = false,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isEnabled = model.isEnabled
    val textAlpha = if (isEnabled) 1f else 0.4f

    val contextStr = formatContextWindow(model.contextWindow)
    val maxOutStr = formatContextWindow(model.maxTokens)
    val specText = buildString {
        if (contextStr != null) append(stringResource(Res.string.settings_panel_spec_context, contextStr))
        if (contextStr != null && maxOutStr != null) append(" · ")
        if (maxOutStr != null) append(stringResource(Res.string.settings_panel_spec_output, maxOutStr))
        if (isEmpty()) append(model.providerModelId)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isEnabled) Color.Transparent else colors.surfaceSidebar.copy(alpha = 0.3f))
            .padding(horizontal = ProviderTokens.SpacingMedium, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 左段：模型标识与上下文规格
        Column(modifier = Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)) {
                Text(
                    text = model.name,
                    color = colors.textPrimary.copy(alpha = textAlpha),
                    fontSize = ProviderTokens.FontValue,
                    fontWeight = if (isEnabled) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (model.isFree) {
                    MetaBadge(text = stringResource(Res.string.settings_panel_badge_free), colors = colors)
                }
            }
            Text(text = specText, color = colors.textMuted.copy(alpha = textAlpha), fontSize = ProviderTokens.FontLabel)
        }

        // 中段：统一中性风格的能力标签 (去饱和度，统一容器)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingXSmall)) {
                if (model.supportsImages) UnifiedCapabilityTag(stringResource(Res.string.settings_panel_capability_image), FeatherIcons.Image, colors, textAlpha)
                if (model.supportsThinking) {
                    val label = if (model.reasoningLevels.isNotEmpty()) {
                        stringResource(Res.string.settings_panel_capability_thinking_levels, model.reasoningLevels.joinToString(","))
                    } else {
                        stringResource(Res.string.settings_panel_capability_thinking)
                    }
                    UnifiedCapabilityTag(label, FeatherIcons.Cpu, colors, textAlpha)
                }
            }
        }

        // 右段：操作控件组（与中段保持充足间距）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
        ) {
            // 启用状态开关
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(ProviderTokens.RadiusBadge)
                    .background(if (isEnabled) colors.surfaceHover else colors.surfaceInput)
                    .clickable { onToggleEnabled() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isEnabled) FeatherIcons.Eye else FeatherIcons.EyeOff,
                    contentDescription = null,
                    tint = if (isEnabled) colors.textPrimary else colors.textMuted,
                    modifier = Modifier.size(12.dp)
                )
            }

            Icon(
                imageVector = FeatherIcons.Edit2,
                contentDescription = stringResource(Res.string.settings_panel_edit),
                tint = colors.textMuted,
                modifier = Modifier.size(13.dp).clickable { onEdit() }
            )

            Icon(
                imageVector = FeatherIcons.Trash2,
                contentDescription = stringResource(Res.string.settings_panel_delete),
                tint = colors.textMuted.copy(alpha = 0.6f),
                modifier = Modifier.size(13.dp).clickable { onDelete() }
            )
        }
    }
}

// ============================================================================
// 6. 弹窗组件 (Dialogs)
// ============================================================================

@Composable
private fun EditProviderCredentialsDialog(
    provider: ProviderItemUiState,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onSave: (name: String, baseUrl: String, apiKey: String, reasoningLevels: Map<String, String?>) -> Unit
) {
    var name by remember { mutableStateOf(provider.name) }
    var baseUrl by remember { mutableStateOf(provider.baseUrl) }
    var apiKey by remember { mutableStateOf(provider.apiKey) }
    var reasoningLevels by remember { mutableStateOf(provider.reasoningLevels) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ProviderTokens.RadiusCard,
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.divider),
            modifier = Modifier.width(480.dp).padding(ProviderTokens.SpacingLarge)
        ) {
            Column(Modifier.padding(ProviderTokens.SpacingLarge), verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingMedium)) {
                Text(stringResource(Res.string.settings_panel_edit_reasoning), color = colors.textPrimary, fontSize = ProviderTokens.FontTitle, fontWeight = FontWeight.SemiBold)
                HorizontalDivider(color = colors.divider)

                LabeledTextField(stringResource(Res.string.settings_panel_provider_name_label), name, { name = it }, stringResource(Res.string.settings_panel_name_placeholder), colors)
                LabeledTextField("Base URL", baseUrl, { baseUrl = it }, "https://...", colors)
                LabeledTextField("API Key", apiKey, { apiKey = it }, "sk-...", isPassword = true, colors = colors)

                HorizontalDivider(color = colors.divider.copy(alpha = 0.6f))
                Text(
                    stringResource(Res.string.settings_panel_reasoning_edit_hint),
                    color = colors.textMuted, fontSize = ProviderTokens.FontLabel
                )
                ReasoningLevelsEditor(
                    levels = reasoningLevels,
                    onChange = { reasoningLevels = it },
                    colors = colors
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss) { Text(stringResource(Res.string.settings_panel_cancel), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel) }
                    Spacer(Modifier.width(ProviderTokens.SpacingSmall))
                    PrimaryActionBtn(stringResource(Res.string.settings_panel_save_reconnect), colors, enabled = baseUrl.isNotBlank()) {
                        onSave(name, baseUrl, apiKey, reasoningLevels)
                    }
                }
            }
        }
    }
}

/**
 * v6 推理参数编辑器：NONE/LOW/MEDIUM/HIGH/MAX 每档一个 JSON 输入框。
 *
 * - LOW~MAX：该级别的请求体 JSON 片段（如 {"reasoning":{"effort":"high"}}）
 * - NONE：一般留空（不发即关）；奇葩端点填显式关闭参数；常开型端点（Agnes）填常开开关
 */
private val REASONING_LEVEL_ORDER = listOf("NONE", "LOW", "MEDIUM", "HIGH", "MAX")

@Composable
private fun reasoningLevelHint(level: String): String = when (level) {
    "NONE" -> stringResource(Res.string.settings_panel_level_none_hint)
    "LOW" -> stringResource(Res.string.settings_panel_level_low_hint)
    "MEDIUM" -> stringResource(Res.string.settings_panel_level_medium_hint)
    "HIGH" -> stringResource(Res.string.settings_panel_level_high_hint)
    "MAX" -> stringResource(Res.string.settings_panel_level_max_hint)
    else -> level
}

@Composable
private fun reasoningLevelPlaceholder(level: String): String = when (level) {
    "NONE" -> stringResource(Res.string.settings_panel_level_none_placeholder)
    "LOW" -> """{"reasoning":{"effort":"low"}}"""
    "MEDIUM" -> """{"reasoning":{"effort":"medium"}}"""
    "HIGH" -> """{"reasoning":{"effort":"high","summary":"auto"}}"""
    "MAX" -> """{"reasoning":{"effort":"max"}}"""
    else -> "{}"
}

@Composable
private fun ReasoningLevelsEditor(
    levels: Map<String, String?>,
    onChange: (Map<String, String?>) -> Unit,
    colors: MederiColors
) {
    Column(verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)) {
        REASONING_LEVEL_ORDER.forEach { level ->
            LabeledTextField(
                label = "$level · ${reasoningLevelHint(level)}",
                value = levels[level].orEmpty(),
                onValueChange = { raw ->
                    val updated = levels.toMutableMap()
                    if (raw.isBlank()) updated.remove(level) else updated[level] = raw
                    onChange(updated)
                },
                placeholder = reasoningLevelPlaceholder(level),
                colors = colors
            )
        }
    }
}

@Composable
private fun AddManualModelDialog(
    colors: MederiColors,
    onDismiss: () -> Unit,
    onAdd: (id: String, name: String, images: Boolean, thinking: Boolean, cw: Int?, mt: Int?, levels: List<String>) -> Unit
) {
    var modelId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var supportsImages by remember { mutableStateOf(false) }
    var supportsThinking by remember { mutableStateOf(false) }
    var contextWindowText by remember { mutableStateOf("") }
    var maxTokensText by remember { mutableStateOf("") }
    var selectedLevels by remember { mutableStateOf(ReasoningLevels.SELECTABLE.toSet()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ProviderTokens.RadiusCard,
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.divider),
            modifier = Modifier.width(440.dp).padding(ProviderTokens.SpacingLarge)
        ) {
            Column(Modifier.padding(ProviderTokens.SpacingLarge), verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingMedium)) {
                Text(stringResource(Res.string.settings_panel_add_model_title), color = colors.textPrimary, fontSize = ProviderTokens.FontTitle, fontWeight = FontWeight.SemiBold)
                HorizontalDivider(color = colors.divider)

                LabeledTextField(stringResource(Res.string.settings_panel_model_id_label), modelId, { modelId = it; if (name.isBlank()) name = it }, stringResource(Res.string.settings_panel_model_id_placeholder, "deepseek-chat"), colors)
                LabeledTextField(stringResource(Res.string.settings_panel_display_name), name, { name = it }, stringResource(Res.string.settings_panel_display_name_placeholder, "DeepSeek V3"), colors)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)) {
                    Box(Modifier.weight(1f)) {
                        LabeledTextField(stringResource(Res.string.settings_panel_context_window), contextWindowText, { contextWindowText = it.filter { ch -> ch.isDigit() } }, "128000", colors)
                    }
                    Box(Modifier.weight(1f)) {
                        LabeledTextField(stringResource(Res.string.settings_panel_max_output), maxTokensText, { maxTokensText = it.filter { ch -> ch.isDigit() } }, "8192", colors)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.settings_panel_support_images), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
                    Switch(checked = supportsImages, onCheckedChange = { supportsImages = it }, colors = switchColors(colors))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.settings_panel_support_thinking), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
                    Switch(checked = supportsThinking, onCheckedChange = { supportsThinking = it }, colors = switchColors(colors))
                }

                if (supportsThinking) {
                    ReasoningLevelsSelector(
                        selected = selectedLevels,
                        onChange = { selectedLevels = it },
                        colors = colors
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss) { Text(stringResource(Res.string.settings_panel_cancel), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel) }
                    Spacer(Modifier.width(ProviderTokens.SpacingSmall))
                    PrimaryActionBtn(stringResource(Res.string.settings_panel_add), colors, enabled = modelId.isNotBlank()) {
                        val cw = contextWindowText.toIntOrNull()
                        val mt = maxTokensText.toIntOrNull()
                        onAdd(
                            modelId.trim(), name.trim(), supportsImages, supportsThinking, cw, mt,
                            if (supportsThinking) selectedLevels.sortedBy { ReasoningLevels.SELECTABLE.indexOf(it) } else emptyList()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditModelDialog(
    model: ModelItemUiState,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onImageOverride: (Boolean) -> Unit,
    onSave: (name: String, images: Boolean, thinking: Boolean, cw: Int?, mt: Int?, levels: List<String>) -> Unit
) {
    // 元数据所有权：FETCHED = 端点/目录权威（ModelMerge 唯一写入）→ 元数据只读；
    // 唯一例外是图片能力开关（用户覆盖层，用户显式设置压过目录且同步永不洗掉）。
    // MANUAL = 用户权威 → 可编辑表单
    if (model.origin == ModelOrigin.FETCHED) {
        ModelMetadataViewDialog(
            model = model,
            colors = colors,
            onImageOverride = onImageOverride,
            onDismiss = onDismiss
        )
        return
    }

    var name by remember { mutableStateOf(model.name) }
    var supportsImages by remember { mutableStateOf(model.supportsImages) }
    var supportsThinking by remember { mutableStateOf(model.supportsThinking) }
    var contextWindowText by remember { mutableStateOf(model.contextWindow?.toString() ?: "") }
    var maxTokensText by remember { mutableStateOf(model.maxTokens?.toString() ?: "") }
    var selectedLevels by remember {
        mutableStateOf(
            (model.reasoningLevels.toSet().ifEmpty { ReasoningLevels.SELECTABLE.toSet() })
                .filter { it != "NONE" }.toSet()
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ProviderTokens.RadiusCard,
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.divider),
            modifier = Modifier.width(440.dp).padding(ProviderTokens.SpacingLarge)
        ) {
            Column(Modifier.padding(ProviderTokens.SpacingLarge), verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingMedium)) {
                Text(stringResource(Res.string.settings_panel_edit_model_title, model.providerModelId), color = colors.textPrimary, fontSize = ProviderTokens.FontTitle, fontWeight = FontWeight.SemiBold)
                HorizontalDivider(color = colors.divider)

                LabeledTextField(stringResource(Res.string.settings_panel_display_name), name, { name = it }, model.providerModelId, colors)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)) {
                    Box(Modifier.weight(1f)) {
                        LabeledTextField(stringResource(Res.string.settings_panel_context_window), contextWindowText, { contextWindowText = it.filter { ch -> ch.isDigit() } }, "128000", colors)
                    }
                    Box(Modifier.weight(1f)) {
                        LabeledTextField(stringResource(Res.string.settings_panel_max_output), maxTokensText, { maxTokensText = it.filter { ch -> ch.isDigit() } }, "8192", colors)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.settings_panel_support_images), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
                    Switch(checked = supportsImages, onCheckedChange = { supportsImages = it }, colors = switchColors(colors))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.settings_panel_support_thinking), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
                    Switch(checked = supportsThinking, onCheckedChange = { supportsThinking = it }, colors = switchColors(colors))
                }

                if (supportsThinking) {
                    ReasoningLevelsSelector(
                        selected = selectedLevels,
                        onChange = { selectedLevels = it },
                        colors = colors
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss) { Text(stringResource(Res.string.settings_panel_cancel), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel) }
                    Spacer(Modifier.width(ProviderTokens.SpacingSmall))
                    PrimaryActionBtn(stringResource(Res.string.settings_panel_save_config), colors) {
                        val cw = contextWindowText.toIntOrNull()
                        val mt = maxTokensText.toIntOrNull()
                        onSave(
                            name.trim(), supportsImages, supportsThinking, cw, mt,
                            if (supportsThinking) selectedLevels.sortedBy { ReasoningLevels.SELECTABLE.indexOf(it) } else emptyList()
                        )
                    }
                }
            }
        }
    }
}

/**
 * FETCHED 模型元数据只读视图。
 *
 * 元数据权威 = 端点/模型目录（ModelMerge 唯一写入），只展示不编辑；
 * 唯一例外：图片能力开关走**用户覆盖层**（supportsImagesOverride，用户显式设置压过目录且同步永不洗掉）——
 * 目录对长尾/私有模型经常缺数据或标错（如 agnes-image-* 在 models.dev 无收录/标成纯文本），
 * 用户对自己供应商的能力有最终发言权。
 */
@Composable
private fun ModelMetadataViewDialog(
    model: ModelItemUiState,
    colors: MederiColors,
    onImageOverride: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ProviderTokens.RadiusCard,
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.divider),
            modifier = Modifier.width(440.dp).padding(ProviderTokens.SpacingLarge)
        ) {
            Column(Modifier.padding(ProviderTokens.SpacingLarge), verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingMedium)) {
                Text(stringResource(Res.string.settings_panel_model_info_title, model.providerModelId), color = colors.textPrimary, fontSize = ProviderTokens.FontTitle, fontWeight = FontWeight.SemiBold)
                HorizontalDivider(color = colors.divider)

                ModelInfoRow(stringResource(Res.string.settings_panel_display_name), model.name, colors)
                ModelInfoRow(stringResource(Res.string.settings_panel_context_window), model.contextWindow?.let { "${it / 1000}K tokens" } ?: stringResource(Res.string.settings_panel_unknown), colors)
                ModelInfoRow(stringResource(Res.string.settings_panel_max_output), model.maxTokens?.let { "${it / 1000}K tokens" } ?: stringResource(Res.string.settings_panel_not_set), colors)

                // 图片能力：用户覆盖开关（唯一可编辑项）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)) {
                        Text(stringResource(Res.string.settings_panel_image_input), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
                        if (model.supportsImagesOverride != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.accentWarning.copy(alpha = 0.15f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(stringResource(Res.string.settings_panel_override_badge), color = colors.accentWarning, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    Switch(
                        checked = model.supportsImages,
                        onCheckedChange = { onImageOverride(it) },
                        colors = switchColors(colors)
                    )
                }
                ModelInfoRow(
                    stringResource(Res.string.settings_panel_thinking_label),
                    if (model.supportsThinking) {
                        model.reasoningLevels.filter { it != "NONE" }.joinToString(" / ").ifEmpty { stringResource(Res.string.settings_panel_supported) }
                    } else {
                        stringResource(Res.string.settings_panel_unsupported)
                    },
                    colors
                )

                Text(
                    stringResource(Res.string.settings_panel_metadata_hint),
                    color = colors.textMuted,
                    fontSize = ProviderTokens.FontLabel
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss) { Text(stringResource(Res.string.close), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel) }
                }
            }
        }
    }
}

@Composable
private fun ModelInfoRow(label: String, value: String, colors: MederiColors) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
        Text(value, color = colors.textPrimary, fontSize = ProviderTokens.FontLabel)
    }
}

/**
 * 思考级别多选（LOW/MEDIUM/HIGH/MAX）。
 * 决定该模型在对话界面思考下拉中暴露哪些级别；供应商 ReasoningParameter 决定每档的实际参数。
 */
@Composable
private fun ReasoningLevelsSelector(
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
    colors: MederiColors
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ProviderTokens.RadiusCard)
            .background(colors.surfaceSidebar.copy(alpha = 0.5f))
            .padding(ProviderTokens.SpacingSmall),
        verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingXSmall)
    ) {
        Text(stringResource(Res.string.settings_panel_thinking_levels_title), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingMedium)) {
            ReasoningLevels.SELECTABLE.forEach { level ->
                val checked = level in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        onChange(if (checked) selected - level else selected + level)
                    }
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { nowChecked ->
                            onChange(if (nowChecked) selected + level else selected - level)
                        },
                        colors = checkboxColors(colors)
                    )
                    Text(level, color = colors.textPrimary, fontSize = ProviderTokens.FontLabel)
                }
            }
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    message: String,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ProviderTokens.RadiusCard,
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.divider),
            modifier = Modifier.width(360.dp).padding(ProviderTokens.SpacingLarge)
        ) {
            Column(Modifier.padding(ProviderTokens.SpacingLarge), verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingMedium)) {
                Text(title, color = colors.accentDanger, fontSize = ProviderTokens.FontTitle, fontWeight = FontWeight.SemiBold)
                Text(message, color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss) { Text(stringResource(Res.string.settings_panel_cancel), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel) }
                    Spacer(Modifier.width(ProviderTokens.SpacingSmall))
                    Box(
                        modifier = Modifier
                            .clip(ProviderTokens.RadiusControl)
                            .background(colors.accentDanger)
                            .clickable { onConfirm() }
                            .padding(horizontal = ProviderTokens.SpacingMedium, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(Res.string.settings_panel_confirm_delete), color = colors.onAccentPrimary, fontSize = ProviderTokens.FontLabel, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiKeyManagementDialog(
    provider: ProviderItemUiState,
    viewModel: ProviderSettingsViewModel,
    colors: MederiColors,
    onDismiss: () -> Unit
) {
    var newKeyName by remember { mutableStateOf("") }
    var newKeyValue by remember { mutableStateOf("") }
    var isNewKeyDefault by remember { mutableStateOf(provider.apiKeys.isEmpty()) }
    var showAddForm by remember { mutableStateOf(provider.apiKeys.isEmpty()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ProviderTokens.RadiusCard,
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.divider),
            modifier = Modifier.width(460.dp).padding(ProviderTokens.SpacingLarge)
        ) {
            Column(
                modifier = Modifier.padding(ProviderTokens.SpacingLarge),
                verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingMedium)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(Res.string.settings_panel_manage_keys_title), color = colors.textPrimary, fontSize = ProviderTokens.FontTitle, fontWeight = FontWeight.SemiBold)
                        Text(provider.name, color = colors.textMuted, fontSize = ProviderTokens.FontLabel)
                    }
                    if (!showAddForm) {
                        PrimaryActionBtn(stringResource(Res.string.settings_panel_add_key), colors) { showAddForm = true }
                    }
                }

                HorizontalDivider(color = colors.divider)

                if (provider.apiKeys.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.settings_panel_no_keys),
                        color = colors.textMuted,
                        fontSize = ProviderTokens.FontLabel,
                        modifier = Modifier.padding(vertical = ProviderTokens.SpacingSmall)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)) {
                        provider.apiKeys.forEach { keyOpt ->
                            Surface(
                                shape = ProviderTokens.RadiusControl,
                                color = colors.surfaceInput,
                                border = BorderStroke(1.dp, if (keyOpt.isDefault) colors.accentPrimary.copy(alpha = 0.5f) else colors.divider),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = ProviderTokens.SpacingMedium, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = keyOpt.name.ifBlank { "API Key" },
                                                color = colors.textPrimary,
                                                fontSize = ProviderTokens.FontValue,
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (keyOpt.isDefault) {
                                                MetaBadge(stringResource(Res.string.input_default_tag), colors)
                                            }
                                        }
                                        Text(
                                            text = keyOpt.maskedValue,
                                            color = colors.textMuted,
                                            fontSize = ProviderTokens.FontLabel,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (!keyOpt.isDefault) {
                                            Text(
                                                text = stringResource(Res.string.settings_panel_set_default),
                                                color = colors.accentPrimary,
                                                fontSize = ProviderTokens.FontLabel,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier
                                                    .clickable { viewModel.setDefaultApiKey(provider.id, keyOpt.id) }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                        Icon(
                                            imageVector = FeatherIcons.Trash2,
                                            contentDescription = stringResource(Res.string.settings_panel_delete),
                                            tint = colors.accentDanger.copy(alpha = 0.8f),
                                            modifier = Modifier
                                                .size(13.dp)
                                                .clickable { viewModel.deleteApiKey(provider.id, keyOpt.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (showAddForm) {
                    Surface(
                        shape = ProviderTokens.RadiusControl,
                        color = colors.surfaceInput,
                        border = BorderStroke(1.dp, colors.divider),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(ProviderTokens.SpacingMedium),
                            verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
                        ) {
                            Text(stringResource(Res.string.settings_panel_add_key_title), color = colors.textPrimary, fontSize = ProviderTokens.FontValue, fontWeight = FontWeight.SemiBold)
                            LabeledTextField(stringResource(Res.string.settings_panel_key_alias), newKeyName, { newKeyName = it }, stringResource(Res.string.settings_panel_key_alias_placeholder), colors)
                            LabeledTextField(stringResource(Res.string.settings_panel_api_key_plain), newKeyValue, { newKeyValue = it }, "sk-...", isPassword = true, colors = colors)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(Res.string.settings_panel_set_default_key), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
                                Switch(checked = isNewKeyDefault, onCheckedChange = { isNewKeyDefault = it }, colors = switchColors(colors))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (provider.apiKeys.isNotEmpty()) {
                                    TextButton(onClick = { showAddForm = false }) {
                                        Text(stringResource(Res.string.settings_panel_cancel), color = colors.textMuted, fontSize = ProviderTokens.FontLabel)
                                    }
                                    Spacer(Modifier.width(ProviderTokens.SpacingSmall))
                                }
                                PrimaryActionBtn(stringResource(Res.string.settings_panel_save_key), colors, enabled = newKeyValue.isNotBlank()) {
                                    viewModel.addApiKey(
                                        providerId = provider.id,
                                        name = newKeyName.ifBlank { "API Key" }.trim(),
                                        key = newKeyValue.trim(),
                                        isDefault = isNewKeyDefault
                                    )
                                    newKeyName = ""
                                    newKeyValue = ""
                                    showAddForm = false
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.settings_panel_done), color = colors.accentPrimary, fontSize = ProviderTokens.FontLabel, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ============================================================================
// 7. 共享原子 UI 组件 (Design Token Atoms)
// ============================================================================

@Composable
private fun CredentialFieldItem(
    label: String,
    value: String,
    colors: MederiColors,
    modifier: Modifier = Modifier,
    isWarn: Boolean = false
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, color = colors.textMuted, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        Text(
            text = value,
            color = if (isWarn) colors.accentWarning else colors.textPrimary,
            fontSize = ProviderTokens.FontValue,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusIndicator(isConnected: Boolean, colors: MederiColors) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isConnected) colors.accentSuccess else colors.textMuted)
        )
        Text(
            text = if (isConnected) stringResource(Res.string.settings_panel_connected) else stringResource(Res.string.settings_panel_disconnected),
            color = if (isConnected) colors.textPrimary else colors.textMuted,
            fontSize = ProviderTokens.FontLabel
        )
    }
}

@Composable
private fun CountBadge(countText: String, colors: MederiColors) {
    Box(
        modifier = Modifier
            .clip(ProviderTokens.RadiusBadge)
            .background(colors.surfaceInput)
            .border(1.dp, colors.divider, ProviderTokens.RadiusBadge)
            .padding(horizontal = 5.dp, vertical = 1.5.dp)
    ) {
        Text(text = countText, color = colors.textMuted, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MetaBadge(text: String, colors: MederiColors) {
    Box(
        modifier = Modifier
            .clip(ProviderTokens.RadiusBadge)
            .background(colors.surfaceInput)
            .border(1.dp, colors.divider, ProviderTokens.RadiusBadge)
            .padding(horizontal = 5.dp, vertical = 1.5.dp)
    ) {
        Text(text = text, color = colors.textSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun UnifiedCapabilityTag(
    text: String,
    icon: ImageVector,
    colors: MederiColors,
    alpha: Float = 1f
) {
    Row(
        modifier = Modifier
            .clip(ProviderTokens.RadiusBadge)
            .background(colors.surfaceInput.copy(alpha = alpha))
            .border(1.dp, colors.divider.copy(alpha = alpha), ProviderTokens.RadiusBadge)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, null, tint = colors.textSecondary.copy(alpha = alpha), modifier = Modifier.size(10.dp))
        Text(text = text, color = colors.textSecondary.copy(alpha = alpha), fontSize = ProviderTokens.FontBadge)
    }
}

@Composable
private fun GhostActionBtn(text: String, colors: MederiColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(ProviderTokens.RadiusControl)
            .background(colors.surfaceCard)
            .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
            .clickable { onClick() }
            .padding(horizontal = ProviderTokens.SpacingSmall, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
    }
}

@Composable
private fun PrimaryActionBtn(
    text: String,
    colors: MederiColors,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(ProviderTokens.RadiusControl)
            .background(if (enabled) colors.accentPrimary else colors.accentPrimary.copy(alpha = 0.35f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = ProviderTokens.SpacingMedium, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = colors.onAccentPrimary, fontSize = ProviderTokens.FontLabel, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    colors: MederiColors,
    isPassword: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, fontSize = ProviderTokens.FontLabel, color = colors.textMuted) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(fontSize = ProviderTokens.FontValue, color = colors.textPrimary),
            shape = ProviderTokens.RadiusControl,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceInput,
                unfocusedContainerColor = colors.surfaceInput,
                focusedBorderColor = colors.accentPrimary,
                unfocusedBorderColor = colors.divider
            )
        )
    }
}

@Composable
private fun ProtocolSelectorBar(
    selected: ProtocolType,
    onSelect: (ProtocolType) -> Unit,
    colors: MederiColors
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.settings_panel_protocol_type), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ProviderTokens.RadiusControl)
                .background(colors.surfaceInput)
                .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ProtocolType.ALL.forEach { pt ->
                val isSel = selected == pt
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ProviderTokens.RadiusBadge)
                        .background(if (isSel) colors.surfaceCard else Color.Transparent)
                        .clickable { onSelect(pt) }
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        pt.displayName,
                        color = if (isSel) colors.accentPrimary else colors.textSecondary,
                        fontSize = ProviderTokens.FontBadge,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun switchColors(colors: MederiColors) = SwitchDefaults.colors(
    checkedThumbColor = colors.onAccentPrimary,
    checkedTrackColor = colors.accentPrimary,
    uncheckedThumbColor = colors.textMuted,
    uncheckedTrackColor = colors.surfaceInput
)

@Composable
private fun checkboxColors(colors: MederiColors) = CheckboxDefaults.colors(
    checkedColor = colors.accentPrimary,
    checkmarkColor = colors.onAccentPrimary,
    uncheckedColor = colors.textMuted
)
