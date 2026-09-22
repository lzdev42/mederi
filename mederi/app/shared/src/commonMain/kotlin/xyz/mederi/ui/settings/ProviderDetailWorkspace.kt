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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.provider_api_key_label
import mederi.app.shared.generated.resources.provider_base_url_label
import mederi.app.shared.generated.resources.provider_reasoning_label
import mederi.app.shared.generated.resources.auto_setup_confirm_message
import mederi.app.shared.generated.resources.auto_setup_confirm_ok
import mederi.app.shared.generated.resources.auto_setup_confirm_title
import mederi.app.shared.generated.resources.settings_filter_all_count
import mederi.app.shared.generated.resources.settings_filter_enabled_count
import mederi.app.shared.generated.resources.settings_filter_free
import mederi.app.shared.generated.resources.settings_filter_image
import mederi.app.shared.generated.resources.settings_filter_reasoning
import xyz.mederi.ui.components.ProviderIcon
import mederi.app.shared.generated.resources.settings_panel_add_model
import mederi.app.shared.generated.resources.settings_panel_add_short
import mederi.app.shared.generated.resources.settings_panel_auto_setup
import mederi.app.shared.generated.resources.settings_panel_cancel
import mederi.app.shared.generated.resources.settings_panel_configure_keys
import mederi.app.shared.generated.resources.settings_panel_delete
import mederi.app.shared.generated.resources.settings_panel_delete_provider_message
import mederi.app.shared.generated.resources.settings_panel_delete_provider_title
import mederi.app.shared.generated.resources.settings_panel_disconnect
import mederi.app.shared.generated.resources.settings_panel_edit_reasoning
import mederi.app.shared.generated.resources.settings_panel_fetch_failed
import mederi.app.shared.generated.resources.settings_panel_hide_all
import mederi.app.shared.generated.resources.settings_panel_manage_keys_count
import mederi.app.shared.generated.resources.settings_panel_no_matching_models
import mederi.app.shared.generated.resources.settings_panel_not_configured
import mederi.app.shared.generated.resources.settings_panel_not_set
import mederi.app.shared.generated.resources.settings_panel_refresh_models
import mederi.app.shared.generated.resources.settings_panel_refresh_short
import mederi.app.shared.generated.resources.settings_panel_search_models
import mederi.app.shared.generated.resources.settings_panel_show_all
import mederi.app.shared.generated.resources.settings_panel_sync_empty
import mederi.app.shared.generated.resources.settings_panel_sync_idle_hint
import mederi.app.shared.generated.resources.settings_panel_sync_unsupported
import mederi.app.shared.generated.resources.settings_panel_syncing
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.ConfirmDialog

// ============================================================================
// 4. 右侧工作台：供应商详情与模型管理
// ============================================================================

@Composable
internal fun ProviderDetailWorkspace(
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
    var showAutoSetupConfirm by remember { mutableStateOf(false) }

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
                ProviderIcon(name = provider.name, baseUrl = provider.baseUrl, size = 20.dp)
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
                // 用户指定暂时不显示右侧“已连接”状态指示
                // StatusIndicator(isConnected = provider.isConnected, colors = colors)

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
                        label = stringResource(Res.string.provider_base_url_label),
                        value = provider.baseUrl.ifBlank { stringResource(Res.string.settings_panel_not_set) },
                        colors = colors,
                        modifier = Modifier.weight(1.4f)
                    )

                    CredentialFieldItem(
                        label = stringResource(Res.string.provider_api_key_label),
                        value = if (provider.apiKey.isNotBlank()) provider.maskedApiKey else stringResource(Res.string.settings_panel_not_configured),
                        colors = colors,
                        isWarn = provider.apiKey.isBlank(),
                        modifier = Modifier.weight(1f)
                    )

                    if (!isCompact && provider.reasoningLevels.isNotEmpty()) {
                        val configuredLevels = provider.reasoningLevels.keys.joinToString("/")
                        CredentialFieldItem(
                            label = stringResource(Res.string.provider_reasoning_label),
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
                            .clickable { showAutoSetupConfirm = true }
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
            ) {
                // 上行：搜索框（弹性宽度）与右侧批量/维护操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 搜索框
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .widthIn(min = 120.dp, max = 220.dp)
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
                                .clickable { showAutoSetupConfirm = true }
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

                // 下行：分类筛选 Chip 控制条（支持水平滑动防溢出）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingXSmall),
                    verticalAlignment = Alignment.CenterVertically
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
                                .clip(ProviderTokens.RadiusControl)
                                .background(if (isSel) colors.surfaceCard else colors.surfaceInput)
                                .border(1.dp, if (isSel) colors.accentPrimary.copy(alpha = 0.5f) else colors.divider, ProviderTokens.RadiusControl)
                                .clickable { viewModel.setCapabilityFilter(filter) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
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
