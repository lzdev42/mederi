package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.input_default_tag
import mederi.app.shared.generated.resources.provider_api_key
import mederi.app.shared.generated.resources.provider_api_key_placeholder
import mederi.app.shared.generated.resources.provider_base_url
import mederi.app.shared.generated.resources.provider_base_url_placeholder
import mederi.app.shared.generated.resources.settings_panel_add_key
import mederi.app.shared.generated.resources.settings_panel_add_key_title
import mederi.app.shared.generated.resources.settings_panel_api_key_plain
import mederi.app.shared.generated.resources.settings_panel_cancel
import mederi.app.shared.generated.resources.settings_panel_confirm_delete
import mederi.app.shared.generated.resources.settings_panel_delete
import mederi.app.shared.generated.resources.settings_panel_done
import mederi.app.shared.generated.resources.settings_panel_edit_reasoning
import mederi.app.shared.generated.resources.settings_panel_key_alias
import mederi.app.shared.generated.resources.settings_panel_key_alias_placeholder
import mederi.app.shared.generated.resources.settings_panel_level_high_hint
import mederi.app.shared.generated.resources.settings_panel_level_low_hint
import mederi.app.shared.generated.resources.settings_panel_level_max_hint
import mederi.app.shared.generated.resources.settings_panel_level_medium_hint
import mederi.app.shared.generated.resources.settings_panel_level_none_hint
import mederi.app.shared.generated.resources.settings_panel_level_none_placeholder
import mederi.app.shared.generated.resources.settings_panel_manage_keys_title
import mederi.app.shared.generated.resources.settings_panel_name_placeholder
import mederi.app.shared.generated.resources.settings_panel_no_keys
import mederi.app.shared.generated.resources.settings_panel_provider_name_label
import mederi.app.shared.generated.resources.settings_panel_reasoning_edit_hint
import mederi.app.shared.generated.resources.settings_panel_save_key
import mederi.app.shared.generated.resources.settings_panel_save_reconnect
import mederi.app.shared.generated.resources.settings_panel_set_default
import mederi.app.shared.generated.resources.settings_panel_set_default_key
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.ConfirmDialog
import xyz.mederi.ui.components.atoms.MederiDialog
import xyz.mederi.ui.components.atoms.MederiGhostButton

@Composable
internal fun EditProviderCredentialsDialog(
    provider: ProviderItemUiState,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onSave: (name: String, baseUrl: String, apiKey: String, reasoningLevels: Map<String, String?>) -> Unit
) {
    var name by remember { mutableStateOf(provider.name) }
    var baseUrl by remember { mutableStateOf(provider.baseUrl) }
    var apiKey by remember { mutableStateOf(provider.apiKey) }
    var reasoningLevels by remember { mutableStateOf(provider.reasoningLevels) }

    // 对话框壳走 MederiDialog（统一 12dp 圆角 + surfaceSidebar chrome + 16dp 内边距 + spacedBy(12)）
    MederiDialog(
        onDismiss = onDismiss,
        width = 480.dp
    ) {
        Text(stringResource(Res.string.settings_panel_edit_reasoning), color = colors.textPrimary, fontSize = ProviderTokens.FontTitle, fontWeight = FontWeight.SemiBold)
        HorizontalDivider(color = colors.divider)

        LabeledTextField(stringResource(Res.string.settings_panel_provider_name_label), name, { name = it }, stringResource(Res.string.settings_panel_name_placeholder), colors)
        LabeledTextField(stringResource(Res.string.provider_base_url), baseUrl, { baseUrl = it }, stringResource(Res.string.provider_base_url_placeholder), colors)
        LabeledTextField(stringResource(Res.string.provider_api_key), apiKey, { apiKey = it }, stringResource(Res.string.provider_api_key_placeholder), isPassword = true, colors = colors)

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
            MederiGhostButton(
                text = stringResource(Res.string.settings_panel_cancel),
                onClick = onDismiss
            )
            Spacer(Modifier.width(ProviderTokens.SpacingSmall))
            PrimaryActionBtn(stringResource(Res.string.settings_panel_save_reconnect), colors, enabled = baseUrl.isNotBlank()) {
                onSave(name, baseUrl, apiKey, reasoningLevels)
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
internal fun ReasoningLevelsEditor(
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
internal fun ConfirmDeleteDialog(
    title: String,
    message: String,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    // 走共享 atoms ConfirmDialog（统一 12dp 圆角 + surfaceSidebar chrome + danger 标题色）
    ConfirmDialog(
        title = title,
        message = message,
        confirmLabel = stringResource(Res.string.settings_panel_confirm_delete),
        cancelLabel = stringResource(Res.string.settings_panel_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        danger = true,
        titleColor = colors.accentDanger,
        width = 360.dp,
    )
}



@Composable
internal fun ApiKeyManagementDialog(
    provider: ProviderItemUiState,
    viewModel: ProviderSettingsViewModel,
    colors: MederiColors,
    onDismiss: () -> Unit
) {
    var newKeyName by remember { mutableStateOf("") }
    var newKeyValue by remember { mutableStateOf("") }
    var isNewKeyDefault by remember { mutableStateOf(provider.apiKeys.isEmpty()) }
    var showAddForm by remember { mutableStateOf(provider.apiKeys.isEmpty()) }

    // 对话框壳走 MederiDialog（统一 12dp 圆角 + surfaceSidebar chrome + 16dp 内边距 + spacedBy(12)）
    MederiDialog(
        onDismiss = onDismiss,
        width = 460.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
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
                            // 非卡片壳（surfaceInput 输入井容器 + 动态默认描边），MederiCard 底色固定 surfaceCard 不适用，等价 Box 链
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(ProviderTokens.RadiusControl)
                                    .background(colors.surfaceInput)
                                    .border(1.dp, if (keyOpt.isDefault) colors.accentPrimary.copy(alpha = 0.5f) else colors.divider, ProviderTokens.RadiusControl)
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
                                            val apiKeyFallbackName = stringResource(Res.string.provider_api_key)
                                            val keyNameText = keyOpt.name.ifBlank { apiKeyFallbackName }
                                            Text(
                                                text = keyNameText,
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
                    // 非卡片壳（surfaceInput 输入井容器），MederiCard 底色固定 surfaceCard 不适用，等价 Box 链
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ProviderTokens.RadiusControl)
                            .background(colors.surfaceInput)
                            .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
                    ) {
                        Column(
                            modifier = Modifier.padding(ProviderTokens.SpacingMedium),
                            verticalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
                        ) {
                            Text(stringResource(Res.string.settings_panel_add_key_title), color = colors.textPrimary, fontSize = ProviderTokens.FontValue, fontWeight = FontWeight.SemiBold)
                            LabeledTextField(stringResource(Res.string.settings_panel_key_alias), newKeyName, { newKeyName = it }, stringResource(Res.string.settings_panel_key_alias_placeholder), colors)
                            LabeledTextField(stringResource(Res.string.settings_panel_api_key_plain), newKeyValue, { newKeyValue = it }, stringResource(Res.string.provider_api_key_placeholder), isPassword = true, colors = colors)
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
                                    MederiGhostButton(
                                        text = stringResource(Res.string.settings_panel_cancel),
                                        onClick = { showAddForm = false }
                                    )
                                    Spacer(Modifier.width(ProviderTokens.SpacingSmall))
                                }
                                val defaultKeyName = stringResource(Res.string.provider_api_key)
                                PrimaryActionBtn(stringResource(Res.string.settings_panel_save_key), colors, enabled = newKeyValue.isNotBlank()) {
                                    viewModel.addApiKey(
                                        providerId = provider.id,
                                        name = newKeyName.ifBlank { defaultKeyName }.trim(),
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
                    MederiGhostButton(
                        text = stringResource(Res.string.settings_panel_done),
                        onClick = onDismiss
                    )
                }
            }
        }
}

