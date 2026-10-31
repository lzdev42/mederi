package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Trash2
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.API_KEY_ROTATION_ID
import xyz.mederi.core.contract.models.ReasoningLevels
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.ConfirmDialog
import xyz.mederi.ui.components.atoms.MederiDialog
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceActionTone

@Composable
internal fun EditProviderCredentialsDialog(
    provider: ProviderItemUiState,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onSave: (name: String, baseUrl: String, apiKey: String, reasoningLevels: Map<String, String?>) -> Unit,
) {
    MederiDialog(onDismiss = onDismiss, width = 480.dp) {
        ProviderCredentialsContent(provider, colors, onDismiss, onSave)
    }
}

/** 名称/端点/JSON保持原值提交；密钥只提交用户新输入，不能将DTO掩码当作明文回写。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderCredentialsContent(
    provider: ProviderItemUiState,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Map<String, String?>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var apiKey by remember(provider.id) { mutableStateOf("") }
    var reasoningLevels by remember(provider.id) { mutableStateOf(provider.reasoningLevels) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MederiSpacing.MD)) {
        Text(stringResource(Res.string.settings_panel_edit_reasoning), style = WorkspaceStyle.Title, color = colors.textPrimary)
        LabeledTextField(stringResource(Res.string.settings_panel_provider_name_label), name, { name = it },
            stringResource(Res.string.settings_panel_name_placeholder), colors)
        LabeledTextField(stringResource(Res.string.provider_base_url), baseUrl, { baseUrl = it },
            stringResource(Res.string.provider_base_url_placeholder), colors)
        LabeledTextField(stringResource(Res.string.provider_api_key), apiKey, { apiKey = it },
            provider.maskedApiKey.ifBlank { stringResource(Res.string.provider_api_key_placeholder) }, colors, isPassword = true)
        Text(stringResource(Res.string.settings_panel_key_unchanged_hint), style = WorkspaceStyle.Caption, color = colors.textSecondary)
        Text(stringResource(Res.string.settings_panel_reasoning_edit_hint), style = WorkspaceStyle.Caption, color = colors.textMuted)
        ReasoningLevelsEditor(reasoningLevels, { reasoningLevels = it }, colors)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
            WorkspaceActionButton(stringResource(Res.string.settings_panel_cancel), onDismiss, colors = colors)
            WorkspaceActionButton(stringResource(Res.string.settings_panel_save_reconnect),
                onClick = { onSave(name, baseUrl, apiKey, reasoningLevels) },
                enabled = baseUrl.isNotBlank(), primary = true, colors = colors)
        }
    }
}

/**
 * v6 推理参数编辑器：NONE/LOW/MEDIUM/HIGH/MAX 每档一个 JSON 输入框。
 *
 * - LOW~MAX：该级别的请求体 JSON 片段（如 {"reasoning":{"effort":"high"}}）
 * - NONE：一般留空（不发即关）；奇葩端点填显式关闭参数；常开型端点（Agnes）填常开开关
 *
 * 档位顺序唯一真理源 = ReasoningLevels.ORDER（contract 层）
 */

@Composable
private fun reasoningLevelHint(level: String): String = when (level) {
    "NONE" -> stringResource(Res.string.settings_panel_level_none_hint)
    "MINIMAL" -> stringResource(Res.string.settings_panel_level_minimal_hint)
    "LOW" -> stringResource(Res.string.settings_panel_level_low_hint)
    "MEDIUM" -> stringResource(Res.string.settings_panel_level_medium_hint)
    "HIGH" -> stringResource(Res.string.settings_panel_level_high_hint)
    "XHIGH" -> stringResource(Res.string.settings_panel_level_xhigh_hint)
    "MAX" -> stringResource(Res.string.settings_panel_level_max_hint)
    else -> level
}

@Composable
private fun reasoningLevelPlaceholder(level: String): String = when (level) {
    "NONE" -> stringResource(Res.string.settings_panel_level_none_placeholder)
    "MINIMAL" -> """{"reasoning":{"effort":"minimal"}}"""
    "LOW" -> """{"reasoning":{"effort":"low"}}"""
    "MEDIUM" -> """{"reasoning":{"effort":"medium"}}"""
    "HIGH" -> """{"reasoning":{"effort":"high","summary":"auto"}}"""
    "XHIGH" -> """{"reasoning":{"effort":"xhigh"}}"""
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
        ReasoningLevels.ORDER.forEach { level ->
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
    onConfirm: () -> Unit,
) {
    ConfirmDialog(title = title, message = message,
        confirmLabel = stringResource(Res.string.settings_panel_confirm_delete),
        cancelLabel = stringResource(Res.string.settings_panel_cancel),
        onConfirm = onConfirm, onDismiss = onDismiss,
        danger = true, titleColor = colors.accentDanger, width = 360.dp)
}

@Composable
internal fun ApiKeyManagementDialog(
    provider: ProviderItemUiState,
    viewModel: ProviderSettingsViewModel,
    colors: MederiColors,
    onDismiss: () -> Unit,
) {
    MederiDialog(onDismiss = onDismiss, width = 460.dp) {
        ApiKeyManagementContent(
            provider = provider, colors = colors, onDismiss = onDismiss,
            onAdd = { name, key, isDefault -> viewModel.addApiKey(provider.id, name, key, isDefault) },
            onSetDefault = { viewModel.setDefaultApiKey(provider.id, it) },
            onDelete = { viewModel.deleteApiKey(provider.id, it) },
        )
    }
}

/** 密钥列表仅展示掩码；所有动作携带当前keyId，名称与值沿原添加规则trim。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApiKeyManagementContent(
    provider: ProviderItemUiState,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onAdd: (name: String, key: String, isDefault: Boolean) -> Unit,
    onSetDefault: (keyId: String) -> Unit,
    onDelete: (keyId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newKeyName by remember(provider.id) { mutableStateOf("") }
    var newKeyValue by remember(provider.id) { mutableStateOf("") }
    var isNewKeyDefault by remember(provider.id) { mutableStateOf(provider.apiKeys.isEmpty()) }
    var showAddForm by remember(provider.id) { mutableStateOf(provider.apiKeys.isEmpty()) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MederiSpacing.MD)) {
        Text(stringResource(Res.string.settings_panel_manage_keys_title), style = WorkspaceStyle.Title, color = colors.textPrimary)
        Text(provider.name, style = WorkspaceStyle.Caption, color = colors.textMuted)
        if (!showAddForm) WorkspaceActionButton(stringResource(Res.string.settings_panel_add_key),
            onClick = { showAddForm = true }, primary = true, colors = colors)
        if (provider.apiKeys.isEmpty()) {
            Text(stringResource(Res.string.settings_panel_no_keys), style = WorkspaceStyle.Caption, color = colors.textMuted)
        } else {
            provider.apiKeys.filter { it.id != API_KEY_ROTATION_ID }.forEach { keyOpt ->
                key(keyOpt.id) {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = MederiSpacing.XS),
                        verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
                            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
                            val fallbackName = stringResource(Res.string.provider_api_key)
                            Text(keyOpt.name.ifBlank { fallbackName },
                                style = WorkspaceStyle.Body, color = colors.textPrimary)
                            if (keyOpt.isDefault) MetaBadge(stringResource(Res.string.input_default_tag), colors)
                        }
                        Text(keyOpt.maskedValue, style = WorkspaceStyle.Caption.copy(fontFamily = FontFamily.Monospace), color = colors.textMuted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
                            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
                            if (!keyOpt.isDefault) WorkspaceActionButton(
                                stringResource(Res.string.settings_panel_set_default), { onSetDefault(keyOpt.id) }, colors = colors)
                            WorkspaceActionButton(stringResource(Res.string.settings_panel_delete),
                                onClick = { onDelete(keyOpt.id) }, icon = FeatherIcons.Trash2,
                                tone = WorkspaceActionTone.Danger, colors = colors)
                        }
                    }
                }
            }
        }
        if (showAddForm) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
                Text(stringResource(Res.string.settings_panel_add_key_title), style = WorkspaceStyle.Title, color = colors.textPrimary)
                LabeledTextField(stringResource(Res.string.settings_panel_key_alias), newKeyName, { newKeyName = it },
                    stringResource(Res.string.settings_panel_key_alias_placeholder), colors)
                LabeledTextField(stringResource(Res.string.settings_panel_api_key_plain), newKeyValue, { newKeyValue = it },
                    stringResource(Res.string.provider_api_key_placeholder), colors, isPassword = true)
                val defaultToggleLabel = stringResource(Res.string.settings_panel_set_default_key)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
                    Text(defaultToggleLabel, style = WorkspaceStyle.Body,
                        color = colors.textSecondary, modifier = Modifier.weight(1f))
                    Switch(checked = isNewKeyDefault, onCheckedChange = { isNewKeyDefault = it }, colors = switchColors(colors),
                        modifier = Modifier.semantics { contentDescription = defaultToggleLabel })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
                    verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
                    if (provider.apiKeys.isNotEmpty()) WorkspaceActionButton(stringResource(Res.string.settings_panel_cancel),
                        onClick = { showAddForm = false }, colors = colors)
                    val defaultKeyName = stringResource(Res.string.provider_api_key)
                    WorkspaceActionButton(stringResource(Res.string.settings_panel_save_key),
                        enabled = newKeyValue.isNotBlank(), primary = true, colors = colors,
                        onClick = {
                            onAdd(newKeyName.ifBlank { defaultKeyName }.trim(), newKeyValue.trim(), isNewKeyDefault)
                            newKeyName = ""; newKeyValue = ""; showAddForm = false
                        })
                }
            }
        }
        WorkspaceActionButton(stringResource(Res.string.settings_panel_done), onDismiss, colors = colors)
    }
}
