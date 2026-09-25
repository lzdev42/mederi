package xyz.mederi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.provider_api_key
import mederi.app.shared.generated.resources.provider_api_key_placeholder
import mederi.app.shared.generated.resources.settings_panel_add
import mederi.app.shared.generated.resources.settings_panel_add_provider
import mederi.app.shared.generated.resources.settings_panel_builtin_base_url_hint
import mederi.app.shared.generated.resources.settings_panel_cancel
import mederi.app.shared.generated.resources.settings_panel_recommended_presets
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.ProviderIcon

// ============================================================================
// 2. 侧边栏与预设组件
// ============================================================================

@Composable
internal fun BuiltinPresetsSection(
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        ProviderIcon(name = preset, size = 15.dp)
                        Text(
                            text = preset,
                            color = colors.textPrimary,
                            fontSize = ProviderTokens.FontValue,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(stringResource(Res.string.settings_panel_add), color = colors.accentPrimary, fontSize = ProviderTokens.FontBadge)
                }
            }
        }
    }
}

@Composable
internal fun AddBuiltinProviderDialog(
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
                    ProviderIcon(name = providerName, size = 24.dp)
                    Column {
                        Text(providerName, color = colors.textPrimary, fontSize = ProviderTokens.FontValue, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(Res.string.settings_panel_builtin_base_url_hint), color = colors.textMuted, fontSize = ProviderTokens.FontLabel)
                    }
                }
                LabeledTextField(
                    label = stringResource(Res.string.provider_api_key),
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = stringResource(Res.string.provider_api_key_placeholder),
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
internal fun AddProviderSidebarButton(
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
internal fun ProviderSidebarRow(
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

            ProviderIcon(name = provider.name, baseUrl = provider.baseUrl, size = 15.dp)

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
