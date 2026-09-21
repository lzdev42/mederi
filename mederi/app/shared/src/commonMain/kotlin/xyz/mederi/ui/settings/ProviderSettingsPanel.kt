package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.settings_panel_back_cd
import mederi.app.shared.generated.resources.settings_panel_back_list
import mederi.app.shared.generated.resources.settings_panel_empty_select_hint
import mederi.app.shared.generated.resources.settings_panel_providers_count
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.ui.appstate.LocalAppState
import xyz.mederi.theme.LocalMederiColors

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
