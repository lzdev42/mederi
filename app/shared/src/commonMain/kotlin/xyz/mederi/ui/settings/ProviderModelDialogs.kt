package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ModelOrigin
import xyz.mederi.core.contract.models.ReasoningLevels
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.MederiDialog
import xyz.mederi.ui.components.atoms.WorkspaceActionButton

@Composable
internal fun AddManualModelDialog(
    colors: MederiColors,
    onDismiss: () -> Unit,
    onAdd: (id: String, name: String, images: Boolean, thinking: Boolean, cw: Int?, mt: Int?, levels: List<String>) -> Unit,
) {
    MederiDialog(onDismiss = onDismiss, width = 440.dp) {
        ModelConfigurationContent(
            model = null,
            title = stringResource(Res.string.settings_panel_add_model_title),
            submitLabel = stringResource(Res.string.settings_panel_add),
            colors = colors, onDismiss = onDismiss, onSave = onAdd,
        )
    }
}

@Composable
internal fun EditModelDialog(
    model: ModelItemUiState,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onSave: (name: String, images: Boolean, thinking: Boolean, cw: Int?, mt: Int?, levels: List<String>) -> Unit,
) {
    MederiDialog(onDismiss = onDismiss, width = 440.dp) {
        ModelConfigurationContent(
            model = model,
            title = stringResource(Res.string.settings_panel_edit_model_title, model.providerModelId),
            submitLabel = stringResource(Res.string.settings_panel_save_config),
            colors = colors, onDismiss = onDismiss,
            onSave = { _, name, images, thinking, cw, mt, levels -> onSave(name, images, thinking, cw, mt, levels) },
        )
    }
}

/**
 * 模型表单可独立验证；元数据权限从origin派生，不另存一份权限状态。
 * FETCHED除图片开关外只读，保存回传原始元数据，Route仅调用图片覆盖API。
 * MANUAL保留数字过滤、trim及档位排序规则。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelConfigurationContent(
    model: ModelItemUiState?,
    title: String,
    submitLabel: String,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onSave: (id: String, name: String, images: Boolean, thinking: Boolean, cw: Int?, mt: Int?, levels: List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metadataEditable = model == null || model.origin == ModelOrigin.MANUAL
    var modelId by remember(model?.id) { mutableStateOf(model?.providerModelId.orEmpty()) }
    var name by remember(model?.id) { mutableStateOf(model?.name.orEmpty()) }
    var supportsImages by remember(model?.id) { mutableStateOf(model?.supportsImages ?: false) }
    var supportsThinking by remember(model?.id) { mutableStateOf(model?.supportsThinking ?: false) }
    var contextWindowText by remember(model?.id) { mutableStateOf(model?.contextWindow?.toString().orEmpty()) }
    var maxTokensText by remember(model?.id) { mutableStateOf(model?.maxTokens?.toString().orEmpty()) }
    var selectedLevels by remember(model?.id) {
        mutableStateOf(when {
            model == null -> ReasoningLevels.SELECTABLE.toSet()
            metadataEditable -> model.reasoningLevels.toSet().ifEmpty { ReasoningLevels.SELECTABLE.toSet() }.filter { it != "NONE" }.toSet()
            else -> model.reasoningLevels.filter { it != "NONE" }.toSet()
        })
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MederiSpacing.MD)) {
        Text(title, style = WorkspaceStyle.Title, color = colors.textPrimary)
        if (!metadataEditable) {
            Text(stringResource(Res.string.settings_panel_fetched_edit_hint), style = WorkspaceStyle.Caption, color = colors.textMuted)
        }
        if (model == null) {
            LabeledTextField(stringResource(Res.string.settings_panel_model_id_label), modelId,
                onValueChange = { modelId = it; if (name.isBlank()) name = it },
                placeholder = stringResource(Res.string.settings_panel_model_id_placeholder, "deepseek-chat"), colors = colors)
        }
        if (!metadataEditable) {
            // 目录事实用可选择的只读数据呈现，不堆叠无法操作的输入框。
            ModelInfoRow(stringResource(Res.string.settings_panel_display_name), model.name, colors)
            ModelInfoRow(stringResource(Res.string.settings_panel_context_window),
                model.contextWindow?.toString() ?: stringResource(Res.string.settings_panel_not_set), colors)
            ModelInfoRow(stringResource(Res.string.settings_panel_max_output),
                model.maxTokens?.toString() ?: stringResource(Res.string.settings_panel_not_set), colors)
        } else {
        LabeledTextField(
            label = stringResource(Res.string.settings_panel_display_name), value = name,
            onValueChange = { if (metadataEditable) name = it },
            placeholder = if (model == null) stringResource(Res.string.settings_panel_display_name_placeholder, "DeepSeek V3") else model.providerModelId,
            colors = colors, enabled = metadataEditable,
        )
        // 独立字段在窄对话框/大字体下保持全宽，避免把标签和输入值压进等分小格。
        LabeledTextField(stringResource(Res.string.settings_panel_context_window), contextWindowText,
            onValueChange = { if (metadataEditable) contextWindowText = it.filter { ch -> ch.isDigit() } },
            placeholder = "128000", colors = colors, enabled = metadataEditable)
        LabeledTextField(stringResource(Res.string.settings_panel_max_output), maxTokensText,
            onValueChange = { if (metadataEditable) maxTokensText = it.filter { ch -> ch.isDigit() } },
            placeholder = "8192", colors = colors, enabled = metadataEditable)
        }
        ModelCapabilityControl(
            label = stringResource(Res.string.settings_panel_support_images), checked = supportsImages,
            onChange = { supportsImages = it }, colors = colors,
        )
        ModelCapabilityControl(
            label = stringResource(Res.string.settings_panel_support_thinking), checked = supportsThinking,
            onChange = { if (metadataEditable) supportsThinking = it }, colors = colors, enabled = metadataEditable,
        )
        if (supportsThinking) {
            ReasoningLevelsSelector(selectedLevels, { if (metadataEditable) selectedLevels = it }, colors, enabled = metadataEditable)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
            WorkspaceActionButton(stringResource(Res.string.settings_panel_cancel), onDismiss, colors = colors)
            WorkspaceActionButton(
                text = submitLabel, enabled = model != null || modelId.isNotBlank(), primary = true, colors = colors,
                onClick = {
                    if (!metadataEditable) {
                        onSave(model.providerModelId, model.name, supportsImages, model.supportsThinking,
                            model.contextWindow, model.maxTokens, model.reasoningLevels)
                    } else {
                        onSave(modelId.trim(), name.trim(), supportsImages, supportsThinking,
                            contextWindowText.toIntOrNull(), maxTokensText.toIntOrNull(),
                            if (supportsThinking) selectedLevels.sortedBy { ReasoningLevels.SELECTABLE.indexOf(it) } else emptyList())
                    }
                },
            )
        }
    }
}

@Composable
private fun ModelCapabilityControl(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    colors: MederiColors,
    enabled: Boolean = true,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
        Text(label, style = WorkspaceStyle.Body, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = switchColors(colors), enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReasoningLevelsSelector(
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
    colors: MederiColors,
    enabled: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
        Text(stringResource(Res.string.settings_panel_thinking_levels_title), style = WorkspaceStyle.Caption, color = colors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
            ReasoningLevels.SELECTABLE.forEach { level ->
                val checked = level in selected
                Row(
                    modifier = Modifier.heightIn(min = WorkspaceStyle.ControlHeight)
                        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox,
                            onValueChange = { now -> onChange(if (now) selected + level else selected - level) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, colors = checkboxColors(colors))
                    Text(level, style = WorkspaceStyle.Body, color = if (enabled) colors.textPrimary else colors.textMuted)
                }
            }
        }
    }
}
