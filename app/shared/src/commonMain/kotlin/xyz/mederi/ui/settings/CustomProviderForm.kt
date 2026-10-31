package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ProtocolType
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.WorkspaceActionButton

/** VM包装保留原保存入口和原始字段语义，不在表现层纠正/裁剪供应商参数。 */
@Composable
internal fun CreateCustomProviderForm(
    viewModel: ProviderSettingsViewModel,
    colors: MederiColors,
    isCompact: Boolean = false,
    onCancel: () -> Unit = {},
) {
    CustomProviderFormContent(
        saving = viewModel.uiState.isSavingCredentials,
        colors = colors,
        onCancel = onCancel,
        onSave = { name, protocol, baseUrl, key, levels ->
            viewModel.saveProviderCredentials(providerId = null, name = name, protocolType = protocol,
                baseUrl = baseUrl, apiKey = key, reasoningLevels = levels)
        },
    )
}

/** 本地仅持有尚未提交的表单输入；提交值保持原值，不另设供应商事实状态。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomProviderFormContent(
    saving: Boolean,
    colors: MederiColors,
    onCancel: () -> Unit,
    onSave: (String, ProtocolType, String, String, Map<String, String?>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var protocolType by remember { mutableStateOf(ProtocolType.DEFAULT) }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var reasoningLevels by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.LG),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
            Text(stringResource(Res.string.settings_panel_create_custom_title), style = WorkspaceStyle.Title, color = colors.textPrimary)
            Text(stringResource(Res.string.settings_panel_create_custom_desc), style = WorkspaceStyle.Caption, color = colors.textMuted)
        }
        LabeledTextField(stringResource(Res.string.settings_panel_provider_name_label), name, { name = it },
            stringResource(Res.string.settings_panel_provider_name_placeholder), colors)
        ProtocolSelectorBar(protocolType, { protocolType = it }, colors)
        LabeledTextField(
            label = stringResource(Res.string.settings_panel_base_url_label), value = baseUrl, onValueChange = { baseUrl = it },
            placeholder = if (protocolType == ProtocolType.GOOGLE) ProtocolType.GOOGLE.placeholderUrl else {
                stringResource(Res.string.settings_panel_base_url_placeholder_local, ProtocolType.OPENAI_CHAT.placeholderUrl)
            },
            colors = colors,
        )
        LabeledTextField(stringResource(Res.string.settings_panel_api_key_secret_label), apiKey, { apiKey = it },
            stringResource(Res.string.provider_api_key_placeholder), colors, isPassword = true)
        Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
            Text(stringResource(Res.string.settings_panel_reasoning_optional_title), style = WorkspaceStyle.Title, color = colors.textPrimary)
            Text(stringResource(Res.string.settings_panel_reasoning_optional_hint), style = WorkspaceStyle.Caption, color = colors.textMuted)
            ReasoningLevelsEditor(reasoningLevels, { reasoningLevels = it }, colors)
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
        ) {
            WorkspaceActionButton(stringResource(Res.string.settings_panel_cancel), onCancel,
                enabled = !saving, colors = colors)
            WorkspaceActionButton(
                text = stringResource(if (saving) Res.string.settings_panel_connecting else Res.string.settings_panel_save_connect),
                enabled = name.isNotBlank() && baseUrl.isNotBlank() && !saving,
                primary = true, colors = colors,
                onClick = { onSave(name, protocolType, baseUrl, apiKey, reasoningLevels) },
            )
        }
    }
}
