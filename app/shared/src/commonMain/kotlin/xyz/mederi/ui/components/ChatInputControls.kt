package xyz.mederi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import xyz.mederi.theme.MederiRadius
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
            .height(26.dp)
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
 * 真实 mini-switch：28x16 track（r10）+ 12x12 thumb + 2dp inset；
 * ON: accentPrimary track + onAccentPrimary thumb；OFF: divider track + textPrimary thumb；
 * thumb 位移 animateDpAsState(120ms) 平滑过渡（对齐原型 .mini-switch）。
 * 唯一真理源 = viewModel.selectedAgentMode，写入走 selectAgentMode。
 * scale/switchWidth/switchHeight 为历史 Material3 Switch 缩放包装遗留参数（桌面 0.65 / 移动抽屉 0.8），
 * 当前实现固定真实尺寸，参数不再生效（保留签名兼容移动端调用点）。
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
    val checked = selectedAgentMode == AgentMode.AUTONOMOUS
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 14.dp else 2.dp,
        animationSpec = tween(120),
        label = "autoApproveThumb",
    )
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) colors.accentPrimary else colors.divider)
            .clickable {
                viewModel.selectAgentMode(if (checked) AgentMode.APPROVAL else AgentMode.AUTONOMOUS)
            }
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .offset(x = thumbOffset, y = 2.dp)
                .clip(CircleShape)
                .background(if (checked) colors.onAccentPrimary else colors.textPrimary)
        )
    }
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
    height: Dp = 26.dp,
    maxWidth: Dp? = null,
    onClick: () -> Unit = {}
) {
    val colors = LocalMederiColors.current
    Row(
        modifier = Modifier
            .height(height)
            .then(if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier)
            .clip(RoundedCornerShape(MederiRadius.Control))
            .background(colors.surfaceHover)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Control))
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
            .height(26.dp)
            .clip(RoundedCornerShape(MederiRadius.Control))
            .background(if (highlight) colors.accentWarning.copy(alpha = 0.10f) else colors.surfaceHover)
            .border(
                1.dp,
                if (highlight) colors.accentWarning.copy(alpha = 0.5f) else colors.surfaceCardBorder,
                RoundedCornerShape(MederiRadius.Control)
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
