package xyz.mederi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.close
import mederi.app.shared.generated.resources.settings_panel_add
import mederi.app.shared.generated.resources.settings_panel_add_model_title
import mederi.app.shared.generated.resources.settings_panel_cancel
import mederi.app.shared.generated.resources.settings_panel_context_window
import mederi.app.shared.generated.resources.settings_panel_display_name
import mederi.app.shared.generated.resources.settings_panel_display_name_placeholder
import mederi.app.shared.generated.resources.settings_panel_edit_model_title
import mederi.app.shared.generated.resources.settings_panel_image_input
import mederi.app.shared.generated.resources.settings_panel_max_output
import mederi.app.shared.generated.resources.settings_panel_metadata_hint
import mederi.app.shared.generated.resources.settings_panel_model_id_label
import mederi.app.shared.generated.resources.settings_panel_model_id_placeholder
import mederi.app.shared.generated.resources.settings_panel_model_info_title
import mederi.app.shared.generated.resources.settings_panel_not_set
import mederi.app.shared.generated.resources.settings_panel_override_badge
import mederi.app.shared.generated.resources.settings_panel_save_config
import mederi.app.shared.generated.resources.settings_panel_supported
import mederi.app.shared.generated.resources.settings_panel_support_images
import mederi.app.shared.generated.resources.settings_panel_support_thinking
import mederi.app.shared.generated.resources.settings_panel_thinking_label
import mederi.app.shared.generated.resources.settings_panel_thinking_levels_title
import mederi.app.shared.generated.resources.settings_panel_unknown
import mederi.app.shared.generated.resources.settings_panel_unsupported
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ModelOrigin
import xyz.mederi.core.contract.models.ReasoningLevels
import xyz.mederi.theme.MederiColors

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
internal fun EditModelDialog(
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

