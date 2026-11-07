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
            colors = colors, onDismiss = onDismiss,
            onSave = { id, name, images, thinking, cw, mt, levels, _ -> onAdd(id, name, images, thinking, cw, mt, levels) },
        )
    }
}

@Composable
internal fun EditModelDialog(
    model: ModelItemUiState,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onSave: (name: String, images: Boolean, thinking: Boolean, cw: Int?, mt: Int?, levels: List<String>, optimizeForRequestCount: Boolean) -> Unit,
) {
    MederiDialog(onDismiss = onDismiss, width = 440.dp) {
        ModelConfigurationContent(
            model = model,
            title = stringResource(Res.string.settings_panel_edit_model_title, model.providerModelId),
            submitLabel = stringResource(Res.string.settings_panel_save_config),
            colors = colors, onDismiss = onDismiss,
            onSave = { _, name, images, thinking, cw, mt, levels, requestCountOptimization ->
                onSave(name, images, thinking, cw, mt, levels, requestCountOptimization)
            },
        )
    }
}

/**
 * 拉取与手动模型共用可编辑表单，允许补录或纠正目录缺失/错误的元数据。
 * 保留数字过滤、trim及档位排序规则；来源只用于说明自动设置语义，不限制编辑。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelConfigurationContent(
    model: ModelItemUiState?,
    title: String,
    submitLabel: String,
    colors: MederiColors,
    onDismiss: () -> Unit,
    onSave: (id: String, name: String, images: Boolean, thinking: Boolean, cw: Int?, mt: Int?, levels: List<String>, optimizeForRequestCount: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var modelId by remember(model?.id) { mutableStateOf(model?.providerModelId.orEmpty()) }
    var name by remember(model?.id) { mutableStateOf(model?.name.orEmpty()) }
    var supportsImages by remember(model?.id) { mutableStateOf(model?.supportsImages ?: false) }
    var supportsThinking by remember(model?.id) { mutableStateOf(model?.supportsThinking ?: false) }
    var contextWindowText by remember(model?.id) { mutableStateOf(model?.contextWindow?.toString().orEmpty()) }
    var maxTokensText by remember(model?.id) { mutableStateOf(model?.maxTokens?.toString().orEmpty()) }
    var optimizeForRequestCount by remember(model?.id) { mutableStateOf(model?.optimizeForRequestCount == true) }
    var selectedLevels by remember(model?.id) {
        mutableStateOf(when {
            model == null -> ReasoningLevels.SELECTABLE.toSet()
            else -> model.reasoningLevels.filter { it != "NONE" }.toSet()
        })
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MederiSpacing.MD)) {
        Text(title, style = WorkspaceStyle.Title, color = colors.textPrimary)
        if (model?.origin == ModelOrigin.FETCHED) {
            Text(stringResource(Res.string.settings_panel_fetched_edit_hint), style = WorkspaceStyle.Caption, color = colors.textMuted)
        }
        if (model == null) {
            LabeledTextField(stringResource(Res.string.settings_panel_model_id_label), modelId,
                onValueChange = { modelId = it; if (name.isBlank()) name = it },
                placeholder = stringResource(Res.string.settings_panel_model_id_placeholder, "deepseek-chat"), colors = colors)
        }
        LabeledTextField(
            label = stringResource(Res.string.settings_panel_display_name), value = name,
            onValueChange = { name = it },
            placeholder = if (model == null) stringResource(Res.string.settings_panel_display_name_placeholder, "DeepSeek V3") else model.providerModelId,
            colors = colors,
        )
        // 独立字段在窄对话框/大字体下保持全宽，避免把标签和输入值压进等分小格。
        LabeledTextField(stringResource(Res.string.settings_panel_context_window), contextWindowText,
            onValueChange = { contextWindowText = it.filter { ch -> ch.isDigit() } },
            placeholder = "128000", colors = colors)
        LabeledTextField(stringResource(Res.string.settings_panel_max_output), maxTokensText,
            onValueChange = { maxTokensText = it.filter { ch -> ch.isDigit() } },
            placeholder = "8192", colors = colors)
        ModelCapabilityControl(
            label = stringResource(Res.string.settings_panel_support_images), checked = supportsImages,
            onChange = { supportsImages = it }, colors = colors,
        )
        ModelCapabilityControl(
            label = stringResource(Res.string.settings_panel_support_thinking), checked = supportsThinking,
            onChange = { supportsThinking = it }, colors = colors,
        )
        if (supportsThinking) {
            ReasoningLevelsSelector(selectedLevels, { selectedLevels = it }, colors)
        }
        if (model != null) {
            ModelCapabilityControl(
                label = stringResource(Res.string.settings_panel_billed_per_request),
                checked = optimizeForRequestCount,
                onChange = { optimizeForRequestCount = it },
                colors = colors,
            )
            Text(
                stringResource(Res.string.settings_panel_billed_per_request_hint),
                style = WorkspaceStyle.Caption, color = colors.textMuted,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
            WorkspaceActionButton(stringResource(Res.string.settings_panel_cancel), onDismiss, colors = colors)
            WorkspaceActionButton(
                text = submitLabel, enabled = model != null || modelId.isNotBlank(), primary = true, colors = colors,
                onClick = {
                    onSave(modelId.trim(), name.trim(), supportsImages, supportsThinking,
                        contextWindowText.toIntOrNull(), maxTokensText.toIntOrNull(),
                        if (supportsThinking) selectedLevels.sortedBy { ReasoningLevels.SELECTABLE.indexOf(it) } else emptyList(),
                        optimizeForRequestCount)
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
