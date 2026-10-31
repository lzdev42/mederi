package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.RadarPulseDot

/** 会话工作态仅单点呼吸；等待用户、结束和异常保持静态，无波纹/缩放。 */
@Composable
internal fun ConversationStatusDot(
    status: ConversationStatus,
    colors: MederiColors,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (status) {
            ConversationStatus.Working -> RadarPulseDot(color = colors.statusWorking, size = 6.dp)
            ConversationStatus.WaitingUser -> RadarPulseDot(color = colors.statusWaiting, size = 6.dp, enabled = false)
            ConversationStatus.Idle -> Box(Modifier.size(5.dp).clip(CircleShape)
                .background(colors.statusIdle.copy(alpha = 0.85f)))
            ConversationStatus.Error -> Box(Modifier.size(6.dp).clip(CircleShape).background(colors.statusError))
        }
    }
}
