package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ReasoningLevels
import xyz.mederi.theme.*
import xyz.mederi.ui.components.atoms.WorkspaceIconButton
import xyz.mederi.util.formatContextWindow

/** 同一模型行适应宽窄；隐藏仅改变选择器可见性，不让名称和能力变得难以阅读。 */
@Composable
internal fun ModelItemRow(
    model: ModelItemUiState, colors: MederiColors, isCompact: Boolean = false,
    onToggleEnabled: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
) {
    val status = stringResource(if (model.isEnabled) Res.string.provider_model_enabled else Res.string.provider_model_hidden)
    val identity: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(model.name, style = WorkspaceStyle.Title,
                    color = if (model.isEnabled) colors.textPrimary else colors.textSecondary)
                if (model.isFree) MetaBadge(stringResource(Res.string.settings_panel_badge_free), colors)
            }
            if (model.providerModelId != model.name) Text(model.providerModelId, style = WorkspaceStyle.Caption,
                fontFamily = FontFamily.Monospace, color = colors.textSecondary)
            val context = formatContextWindow(model.contextWindow)
            val output = formatContextWindow(model.maxTokens)
            val specs = listOfNotNull(
                context?.let { stringResource(Res.string.settings_panel_spec_context, it) },
                output?.let { stringResource(Res.string.settings_panel_spec_output, it) })
            if (specs.isNotEmpty()) Text(specs.joinToString(" · "), style = WorkspaceStyle.Caption, color = colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(status, style = WorkspaceStyle.Caption, color = colors.textSecondary,
                    modifier = Modifier.align(Alignment.CenterVertically))
                if (model.supportsImages) UnifiedCapabilityTag(stringResource(Res.string.settings_panel_capability_image), FeatherIcons.Image, colors)
                if (model.supportsThinking) {
                    val levels = model.reasoningLevels.count { ReasoningLevels.isActiveLevel(it) }
                    UnifiedCapabilityTag(if (levels > 0) stringResource(Res.string.settings_panel_capability_thinking_levels, levels.toString())
                        else stringResource(Res.string.settings_panel_capability_thinking), FeatherIcons.Cpu, colors)
                }
            }
        }
    }
    val actions: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkspaceIconButton(if (model.isEnabled) FeatherIcons.Eye else FeatherIcons.EyeOff, onToggleEnabled,
                stringResource(if (model.isEnabled) Res.string.provider_model_disable else Res.string.provider_model_enable), colors = colors)
            WorkspaceIconButton(FeatherIcons.Edit2, onEdit, stringResource(Res.string.settings_panel_edit), colors = colors)
            WorkspaceIconButton(FeatherIcons.Trash2, onDelete, stringResource(Res.string.settings_panel_delete), colors = colors)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics { stateDescription = status }) {
        val narrow = isCompact || maxWidth < 540.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (narrow) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            identity()
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { actions() }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { identity() }
            actions()
        }
    }
}

@Composable
internal fun ModelInfoRow(label: String, value: String, colors: MederiColors) {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = WorkspaceStyle.Caption, color = colors.textSecondary)
        SelectionContainer { Text(value, style = WorkspaceStyle.Body, color = colors.textPrimary) }
    }
}

@Composable
internal fun StatusIndicator(isConnected: Boolean, colors: MederiColors) {
    Text(stringResource(if (isConnected) Res.string.settings_panel_connected else Res.string.settings_panel_disconnected),
        style = WorkspaceStyle.Caption, color = colors.textSecondary)
}

@Composable
internal fun CountBadge(countText: String, colors: MederiColors) = MetaBadge(countText, colors)

/** 完整端点可选择，长值换行，不靠单行省略隐藏关键身份。 */
@Composable
internal fun CredentialFieldItem(label: String, value: String, colors: MederiColors,
    modifier: Modifier = Modifier, isWarn: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = WorkspaceStyle.Caption, color = colors.textSecondary)
        SelectionContainer {
            Text(value, style = WorkspaceStyle.Body, fontFamily = FontFamily.Monospace,
                color = if (isWarn) colors.textSecondary else colors.textPrimary)
        }
    }
}
