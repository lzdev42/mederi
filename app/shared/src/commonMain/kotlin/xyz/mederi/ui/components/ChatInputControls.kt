package xyz.mederi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Square
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.auto_approve_title
import mederi.app.shared.generated.resources.auto_approve_tooltip_main
import mederi.app.shared.generated.resources.auto_approve_tooltip_note
import mederi.app.shared.generated.resources.input_send
import mederi.app.shared.generated.resources.input_stop
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.*
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.components.atoms.MederiIconButton
import xyz.mederi.ui.components.atoms.MederiSendRoundButton

/** 自动审批开关与说明气泡。选中态订阅 AppState 派生流（唯一真理源）。 */
@Composable
internal fun AgentModeSelector(viewModel: WorkspaceViewModel) {
    val selectedAgentMode by viewModel.selectedAgentMode.collectAsState()
    val isAutoApprove = selectedAgentMode == AgentMode.AUTONOMOUS
    val colors = LocalMederiColors.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                viewModel.selectAgentMode(if (isAutoApprove) AgentMode.APPROVAL else AgentMode.AUTONOMOUS)
            }
            .padding(horizontal = 4.dp)
    ) {
        Text(
            text = stringResource(Res.string.auto_approve_title),
            color = colors.textSecondary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
        )
        HelpCircleTooltip(
            mainText = stringResource(Res.string.auto_approve_tooltip_main),
            noteText = stringResource(Res.string.auto_approve_tooltip_note),
            touchTargetSize = 20.dp,
            iconSize = 13.dp
        )
        AutoApproveSwitch(
            viewModel = viewModel,
            scale = 0.65f,
            switchWidth = 30.dp,
            switchHeight = 20.dp
        )
    }
}

/**
 * 自动审批开关（桌面输入条与移动端设置抽屉共用）。
 * 唯一真理源 = viewModel.selectedAgentMode，写入走 selectAgentMode。
 */
@Composable
internal fun AutoApproveSwitch(
    viewModel: WorkspaceViewModel,
    scale: Float,
    switchWidth: Dp,
    switchHeight: Dp,
) {
    val selectedAgentMode by viewModel.selectedAgentMode.collectAsState()
    val colors = LocalMederiColors.current
    Switch(
        checked = selectedAgentMode == AgentMode.AUTONOMOUS,
        onCheckedChange = { checked ->
            viewModel.selectAgentMode(if (checked) AgentMode.AUTONOMOUS else AgentMode.APPROVAL)
        },
        modifier = Modifier
            .scale(scale)
            .size(width = switchWidth, height = switchHeight),
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.accentPrimary,
            uncheckedThumbColor = colors.textMuted,
            uncheckedTrackColor = colors.buttonSecondary
        )
    )
}

/** 发送 / 停止按钮。支持排队模式与中止模式。样式收敛为 MederiSendRoundButton（反色 send，KDoc 已注明）。 */
@Composable
internal fun SendButton(
    size: Dp = 28.dp,
    isStreaming: Boolean,
    hasContent: Boolean = false,
    canSend: Boolean,
    onSubmit: () -> Unit
) {
    val showSendIcon = !isStreaming || hasContent
    val isEnabled = if (isStreaming) true else canSend
    MederiSendRoundButton(
        icon = if (showSendIcon) FeatherIcons.ArrowUp else FeatherIcons.Square,
        onClick = onSubmit,
        contentDescription = stringResource(
            if (showSendIcon) Res.string.input_send else Res.string.input_stop
        ),
        enabled = isEnabled,
        modifier = Modifier.size(size),
    )
}

@Composable
internal fun ChipSelectorPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    height: Dp = 28.dp,
    onClick: () -> Unit = {}
) {
    val colors = LocalMederiColors.current
    Row(
        modifier = Modifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceInput)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(13.dp)
            )
        }
        Text(
            text = label,
            color = colors.textPrimary,
            fontSize = 11.5.sp,
            lineHeight = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = FeatherIcons.ChevronDown,
            contentDescription = null,
            tint = colors.textMuted,
            modifier = Modifier.size(10.dp)
        )
    }
}

@Composable
internal fun IconToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    size: Int = 28
) {
    MederiIconButton(
        icon = icon,
        onClick = onClick,
        size = size,
    )
}

@Composable
internal fun ContextToolChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    highlight: Boolean = false
) {
    val colors = LocalMederiColors.current
    val contentColor = if (highlight) colors.accentWarning else colors.textSecondary
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlight) colors.accentWarning.copy(alpha = 0.10f) else Color.Transparent)
            .border(
                1.dp,
                if (highlight) colors.accentWarning.copy(alpha = 0.5f) else colors.surfaceCardBorder,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = label,
            color = contentColor,
            fontSize = 11.5.sp,
            lineHeight = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = FeatherIcons.ChevronDown,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(10.dp)
        )
    }
}
