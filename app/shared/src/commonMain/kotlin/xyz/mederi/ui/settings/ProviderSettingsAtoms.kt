package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.settings_panel_protocol_type
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ProtocolType
import xyz.mederi.theme.MederiColors

// ============================================================================
// 7. 共享原子 UI 组件 (Design Token Atoms)
// ============================================================================

@Composable
internal fun MetaBadge(text: String, colors: MederiColors) {
    Box(
        modifier = Modifier
            .clip(ProviderTokens.RadiusBadge)
            .background(colors.surfaceInput)
            .border(1.dp, colors.divider, ProviderTokens.RadiusBadge)
            .padding(horizontal = 5.dp, vertical = 1.5.dp)
    ) {
        Text(
            text = text,
            color = colors.textSecondary,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun UnifiedCapabilityTag(
    text: String,
    icon: ImageVector,
    colors: MederiColors,
    alpha: Float = 1f
) {
    Row(
        modifier = Modifier
            .clip(ProviderTokens.RadiusBadge)
            .background(colors.surfaceInput.copy(alpha = alpha))
            .border(1.dp, colors.divider.copy(alpha = alpha), ProviderTokens.RadiusBadge)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = colors.textSecondary.copy(alpha = alpha), modifier = Modifier.size(10.dp))
        Text(
            text = text,
            color = colors.textSecondary.copy(alpha = alpha),
            fontSize = ProviderTokens.FontBadge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun GhostActionBtn(text: String, colors: MederiColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(ProviderTokens.RadiusControl)
            .background(colors.surfaceCard)
            .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
            .clickable { onClick() }
            .padding(horizontal = ProviderTokens.SpacingSmall, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
    }
}

@Composable
internal fun PrimaryActionBtn(
    text: String,
    colors: MederiColors,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(ProviderTokens.RadiusControl)
            .background(if (enabled) colors.accentPrimary else colors.accentPrimary.copy(alpha = 0.35f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = ProviderTokens.SpacingMedium, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = colors.onAccentPrimary, fontSize = ProviderTokens.FontLabel, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    colors: MederiColors,
    isPassword: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, fontSize = ProviderTokens.FontLabel, color = colors.textMuted) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(fontSize = ProviderTokens.FontValue, color = colors.textPrimary),
            shape = ProviderTokens.RadiusControl,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceInput,
                unfocusedContainerColor = colors.surfaceInput,
                focusedBorderColor = colors.accentPrimary,
                unfocusedBorderColor = colors.divider
            )
        )
    }
}

@Composable
internal fun ProtocolSelectorBar(
    selected: ProtocolType,
    onSelect: (ProtocolType) -> Unit,
    colors: MederiColors
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.settings_panel_protocol_type), color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ProviderTokens.RadiusControl)
                .background(colors.surfaceInput)
                .border(1.dp, colors.divider, ProviderTokens.RadiusControl)
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ProtocolType.ALL.forEach { pt ->
                val isSel = selected == pt
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ProviderTokens.RadiusBadge)
                        .background(if (isSel) colors.surfaceCard else Color.Transparent)
                        .clickable { onSelect(pt) }
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        pt.displayName,
                        color = if (isSel) colors.accentPrimary else colors.textSecondary,
                        fontSize = ProviderTokens.FontBadge,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
internal fun switchColors(colors: MederiColors) = SwitchDefaults.colors(
    checkedThumbColor = colors.onAccentPrimary,
    checkedTrackColor = colors.accentPrimary,
    uncheckedThumbColor = colors.textMuted,
    uncheckedTrackColor = colors.surfaceInput
)

@Composable
internal fun checkboxColors(colors: MederiColors) = CheckboxDefaults.colors(
    checkedColor = colors.accentPrimary,
    checkmarkColor = colors.onAccentPrimary,
    uncheckedColor = colors.textMuted
)
