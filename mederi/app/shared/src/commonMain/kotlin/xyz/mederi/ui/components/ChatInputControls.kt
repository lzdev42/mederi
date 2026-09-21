package xyz.mederi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import xyz.mederi.core.ui.WorkspaceViewModel
import xyz.mederi.theme.LocalMederiColors

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
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                viewModel.selectAgentMode(if (isAutoApprove) AgentMode.APPROVAL else AgentMode.AUTONOMOUS)
            }
            .padding(horizontal = 4.dp, vertical = 2.dp)
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

/** 发送 / 停止按钮。 */
@Composable
internal fun SendButton(
    size: Dp,
    isStreaming: Boolean,
    canSend: Boolean,
    onSubmit: () -> Unit
) {
    val colors = LocalMederiColors.current
    val bgColor = when {
        isStreaming -> colors.accentDanger
        canSend -> colors.accentPrimary
        else -> colors.buttonSecondary
    }
    val iconColor = when {
        isStreaming || canSend -> colors.onAccentPrimary
        else -> colors.textMuted
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, if (canSend) colors.accentPrimary else colors.surfaceCardBorder, CircleShape)
            .clickable(enabled = isStreaming || canSend, onClick = onSubmit),
        contentAlignment = Alignment.Center
    ) {
        if (isStreaming) {
            Icon(FeatherIcons.Square, stringResource(Res.string.input_stop), tint = iconColor, modifier = Modifier.size(size * 0.37f))
        } else {
            Icon(FeatherIcons.ArrowUp, stringResource(Res.string.input_send), tint = iconColor, modifier = Modifier.size(size * 0.48f))
        }
    }
}

@Composable
internal fun ChipSelectorPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    height: Dp = 28.dp,
    onClick: () -> Unit = {}
) {
    val colors = LocalMederiColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceInput,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.surfaceCardBorder)
    ) {
        Row(
            modifier = Modifier
                .height(height)
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
                fontWeight = FontWeight.Medium
            )
            Icon(
                imageVector = FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

@Composable
internal fun IconToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    size: Int = 28
) {
    val colors = LocalMederiColors.current
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(colors.buttonSecondary)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(if (size >= 36) 18.dp else (size * 0.5f).dp)
        )
    }
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
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (highlight) colors.accentWarning.copy(alpha = 0.10f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (highlight) colors.accentWarning.copy(alpha = 0.5f) else colors.surfaceCardBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
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
                fontSize = 11.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Icon(
                imageVector = FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(10.dp)
            )
        }
    }
}
