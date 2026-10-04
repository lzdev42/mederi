package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.settings_panel_badge_free
import mederi.app.shared.generated.resources.settings_panel_capability_image
import mederi.app.shared.generated.resources.settings_panel_capability_thinking
import mederi.app.shared.generated.resources.settings_panel_capability_thinking_levels
import mederi.app.shared.generated.resources.settings_panel_connected
import mederi.app.shared.generated.resources.settings_panel_delete
import mederi.app.shared.generated.resources.settings_panel_disconnected
import mederi.app.shared.generated.resources.settings_panel_edit
import mederi.app.shared.generated.resources.settings_panel_spec_context
import mederi.app.shared.generated.resources.settings_panel_spec_output
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ReasoningLevels
import xyz.mederi.theme.MederiColors
import xyz.mederi.util.formatContextWindow

// ============================================================================
// 5. 模型列表单项组件
// ============================================================================

@Composable
internal fun ModelItemRow(
    model: ModelItemUiState,
    colors: MederiColors,
    isCompact: Boolean = false,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isEnabled = model.isEnabled
    val textAlpha = if (isEnabled) 1f else 0.4f

    val contextStr = formatContextWindow(model.contextWindow)
    val maxOutStr = formatContextWindow(model.maxTokens)
    val specText = buildString {
        if (contextStr != null) append(stringResource(Res.string.settings_panel_spec_context, contextStr))
        if (contextStr != null && maxOutStr != null) append(" · ")
        if (maxOutStr != null) append(stringResource(Res.string.settings_panel_spec_output, maxOutStr))
        if (isEmpty()) append(model.providerModelId)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isEnabled) Color.Transparent else colors.surfaceSidebar.copy(alpha = 0.3f))
            .padding(horizontal = ProviderTokens.SpacingMedium, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingMedium)
    ) {
        // 左段：模型标识与上下文规格（自适应占满剩余空间）
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
            ) {
                Text(
                    text = model.name,
                    color = colors.textPrimary.copy(alpha = textAlpha),
                    fontSize = ProviderTokens.FontValue,
                    fontWeight = if (isEnabled) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (model.isFree) {
                    MetaBadge(text = stringResource(Res.string.settings_panel_badge_free), colors = colors)
                }
            }
            Text(
                text = specText,
                color = colors.textMuted.copy(alpha = textAlpha),
                fontSize = ProviderTokens.FontLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 右段：能力标签（单行紧凑） + 操作控件组靠右对齐
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingLarge)
        ) {
            if (model.supportsImages || model.supportsThinking) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingXSmall),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (model.supportsImages) {
                        UnifiedCapabilityTag(
                            text = stringResource(Res.string.settings_panel_capability_image),
                            icon = FeatherIcons.Image,
                            colors = colors,
                            alpha = textAlpha
                        )
                    }
                    if (model.supportsThinking) {
                        val activeLevelCount = model.reasoningLevels.count { ReasoningLevels.isActiveLevel(it) }
                        val label = if (activeLevelCount > 0) {
                            stringResource(Res.string.settings_panel_capability_thinking_levels, activeLevelCount.toString())
                        } else {
                            stringResource(Res.string.settings_panel_capability_thinking)
                        }
                        UnifiedCapabilityTag(
                            text = label,
                            icon = FeatherIcons.Cpu,
                            colors = colors,
                            alpha = textAlpha
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProviderTokens.SpacingSmall)
            ) {
                // 启用状态开关
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(ProviderTokens.RadiusBadge)
                        .background(if (isEnabled) colors.surfaceHover else colors.surfaceInput)
                        .clickable { onToggleEnabled() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isEnabled) FeatherIcons.Eye else FeatherIcons.EyeOff,
                        contentDescription = null,
                        tint = if (isEnabled) colors.textPrimary else colors.textMuted,
                        modifier = Modifier.size(12.dp)
                    )
                }

                Icon(
                    imageVector = FeatherIcons.Edit2,
                    contentDescription = stringResource(Res.string.settings_panel_edit),
                    tint = colors.textMuted,
                    modifier = Modifier.size(13.dp).clickable { onEdit() }
                )

                Icon(
                    imageVector = FeatherIcons.Trash2,
                    contentDescription = stringResource(Res.string.settings_panel_delete),
                    tint = colors.textMuted.copy(alpha = 0.6f),
                    modifier = Modifier.size(13.dp).clickable { onDelete() }
                )
            }
        }
    }
}


@Composable
internal fun ModelInfoRow(label: String, value: String, colors: MederiColors) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = colors.textSecondary, fontSize = ProviderTokens.FontLabel)
        Text(value, color = colors.textPrimary, fontSize = ProviderTokens.FontLabel)
    }
}

/**
 * 思考级别多选（LOW/MEDIUM/HIGH/MAX）。
 * 决定该模型在对话界面思考下拉中暴露哪些级别；供应商 ReasoningParameter 决定每档的实际参数。
 */


@Composable
internal fun StatusIndicator(isConnected: Boolean, colors: MederiColors) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isConnected) colors.accentSuccess else colors.textMuted)
        )
        Text(
            text = if (isConnected) stringResource(Res.string.settings_panel_connected) else stringResource(Res.string.settings_panel_disconnected),
            color = if (isConnected) colors.textPrimary else colors.textMuted,
            fontSize = ProviderTokens.FontLabel
        )
    }
}



@Composable
internal fun CountBadge(countText: String, colors: MederiColors) {
    Box(
        modifier = Modifier
            .clip(ProviderTokens.RadiusBadge)
            .background(colors.surfaceInput)
            .border(1.dp, colors.divider, ProviderTokens.RadiusBadge)
            .padding(horizontal = 5.dp, vertical = 1.5.dp)
    ) {
        Text(text = countText, color = colors.textMuted, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
    }
}



@Composable
internal fun CredentialFieldItem(
    label: String,
    value: String,
    colors: MederiColors,
    modifier: Modifier = Modifier,
    isWarn: Boolean = false
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, color = colors.textMuted, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        Text(
            text = value,
            color = if (isWarn) colors.accentWarning else colors.textPrimary,
            fontSize = ProviderTokens.FontValue,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

