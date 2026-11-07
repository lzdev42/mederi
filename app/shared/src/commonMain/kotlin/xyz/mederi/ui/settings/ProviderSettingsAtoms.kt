package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.settings_panel_protocol_type
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ProtocolType
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceNavigationTab

@Composable
internal fun MetaBadge(text: String, colors: MederiColors) {
    Text(text, style = WorkspaceStyle.Caption, color = colors.textSecondary,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(ProviderTokens.RadiusBadge).background(colors.surfaceHover)
            .padding(horizontal = MederiSpacing.Tight, vertical = MederiSpacing.Micro))
}

@Composable
internal fun UnifiedCapabilityTag(text: String, icon: ImageVector, colors: MederiColors, alpha: Float = 1f) {
    Row(
        modifier = Modifier.clip(ProviderTokens.RadiusBadge).background(colors.surfaceHover.copy(alpha = alpha))
            .padding(horizontal = MederiSpacing.Tight, vertical = MederiSpacing.Micro),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
    ) {
        Icon(icon, null, tint = colors.textSecondary.copy(alpha = alpha), modifier = Modifier.size(WorkspaceStyle.IconSize))
        Text(text, color = colors.textSecondary.copy(alpha = alpha), style = WorkspaceStyle.Caption,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 保留旧签名，实际外观只由共同动作组件实现。 */
@Composable
internal fun GhostActionBtn(text: String, colors: MederiColors, onClick: () -> Unit) {
    WorkspaceActionButton(text = text, onClick = onClick, colors = colors)
}

@Composable
internal fun PrimaryActionBtn(text: String, colors: MederiColors, enabled: Boolean = true, onClick: () -> Unit) {
    WorkspaceActionButton(text = text, onClick = onClick, enabled = enabled, primary = true, colors = colors)
}

/** 字段值始终归调用方；enabled供保存中等暂不可编辑场景复用。 */
@Composable
internal fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    colors: MederiColors,
    isPassword: Boolean = false,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
        Text(label, color = colors.textSecondary, style = WorkspaceStyle.Caption)
        androidx.compose.runtime.CompositionLocalProvider(xyz.mederi.theme.LocalMederiColors provides colors) {
            xyz.mederi.ui.components.atoms.WorkspaceTextField(value, onValueChange,
                Modifier.fillMaxWidth().semantics { contentDescription = label }, enabled = enabled,
                placeholder = placeholder,
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None)
        }
    }
}

/** 协议选择复用同一导航/单选语义，窄屏自动换行，不将协议名裁进固定等分格。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProtocolSelectorBar(selected: ProtocolType, onSelect: (ProtocolType) -> Unit, colors: MederiColors) {
    Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
        Text(stringResource(Res.string.settings_panel_protocol_type), color = colors.textSecondary, style = WorkspaceStyle.Caption)
        FlowRow(modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
            ProtocolType.ALL.forEach { protocol ->
                WorkspaceNavigationTab(text = protocol.displayName, selected = selected == protocol,
                    onClick = { onSelect(protocol) }, role = Role.RadioButton)
            }
        }
    }
}

@Composable
internal fun switchColors(colors: MederiColors) = SwitchDefaults.colors(
    checkedThumbColor = colors.onAccentPrimary,
    checkedTrackColor = colors.accentPrimary,
    uncheckedThumbColor = colors.textMuted,
    uncheckedTrackColor = colors.surfaceInput,
)

@Composable
internal fun checkboxColors(colors: MederiColors) = CheckboxDefaults.colors(
    checkedColor = colors.accentPrimary,
    checkmarkColor = colors.onAccentPrimary,
    uncheckedColor = colors.textMuted,
)
