package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.settings_panel_add
import mederi.app.shared.generated.resources.settings_panel_add_model_title
import mederi.app.shared.generated.resources.settings_panel_cancel
import mederi.app.shared.generated.resources.settings_panel_context_window
import mederi.app.shared.generated.resources.settings_panel_display_name
import mederi.app.shared.generated.resources.settings_panel_display_name_placeholder
import mederi.app.shared.generated.resources.settings_panel_edit_model_title
import mederi.app.shared.generated.resources.settings_panel_max_output
import mederi.app.shared.generated.resources.settings_panel_model_id_label
import mederi.app.shared.generated.resources.settings_panel_model_id_placeholder
import mederi.app.shared.generated.resources.settings_panel_save_config
import mederi.app.shared.generated.resources.settings_panel_support_images
import mederi.app.shared.generated.resources.settings_panel_support_thinking
import mederi.app.shared.generated.resources.settings_panel_thinking_levels_title
import org.jetbrains.compose.resources.stringResource

import xyz.mederi.core.contract.models.ReasoningLevels
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.MederiDialog
import xyz.mederi.ui.components.atoms.MederiGhostButton

// ============================================================================
// 6. 弹窗组件 (Dialogs)
// ============================================================================

@Composable
internal fun AddManualModelDialog(
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

    // 对话框壳走 MederiDialog（统一 12dp 圆角 + surfaceSidebar chrome + 16dp 内边距 + spacedBy(12)）
    MederiDialog(
        onDismiss = onDismiss,
        width = 440.dp
    ) {
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
                    MederiGhostButton(
                        text = stringResource(Res.string.settings_panel_cancel),
                        onClick = onDismiss
                    )
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

@Composable
internal fun EditModelDialog(
    model: ModelItemUiState,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onSave: (name: String, images: Boolean, thinking: Boolean, cw: Int?, mt: Int?, levels: List<String>) -> Unit
) {
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

    // 对话框壳走 MederiDialog（统一 12dp 圆角 + surfaceSidebar chrome + 16dp 内边距 + spacedBy(12)）
    MederiDialog(
        onDismiss = onDismiss,
        width = 440.dp
    ) {
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
                    MederiGhostButton(
                        text = stringResource(Res.string.settings_panel_cancel),
                        onClick = onDismiss
                    )
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

