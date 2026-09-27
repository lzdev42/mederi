package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.provider_api_key_placeholder
import mederi.app.shared.generated.resources.settings_panel_api_key_secret_label
import mederi.app.shared.generated.resources.settings_panel_base_url_label
import mederi.app.shared.generated.resources.settings_panel_base_url_placeholder_local
import mederi.app.shared.generated.resources.settings_panel_cancel
import mederi.app.shared.generated.resources.settings_panel_connecting
import mederi.app.shared.generated.resources.settings_panel_create_custom_desc
import mederi.app.shared.generated.resources.settings_panel_create_custom_title
import mederi.app.shared.generated.resources.settings_panel_provider_name_label
import mederi.app.shared.generated.resources.settings_panel_provider_name_placeholder
import mederi.app.shared.generated.resources.settings_panel_reasoning_optional_hint
import mederi.app.shared.generated.resources.settings_panel_reasoning_optional_title
import mederi.app.shared.generated.resources.settings_panel_save_connect
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ProtocolType
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.MederiGhostButton

// ============================================================================
// 3. 右侧工作台：新建自定义供应商表单
// ============================================================================

@Composable
internal fun CreateCustomProviderForm(
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
            placeholder = stringResource(Res.string.provider_api_key_placeholder),
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
            MederiGhostButton(
                text = stringResource(Res.string.settings_panel_cancel),
                onClick = {
                    onCancel()
                    val first = viewModel.uiState.providers.firstOrNull()?.id
                    if (first != null) viewModel.selectProvider(first)
                }
            )

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
