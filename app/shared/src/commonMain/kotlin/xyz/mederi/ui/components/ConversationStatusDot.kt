package xyz.mederi.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.theme.MederiColors

/**
 * 侧边栏会话状态指示灯组件。
 * - [ConversationStatus.Working]：琥珀橙呼吸光晕动效
 * - [ConversationStatus.WaitingUser]：翡翠绿微脉冲波纹 (Beacon Ping)
 * - [ConversationStatus.Idle]：晴空蓝低噪声静态微圆点
 * - [ConversationStatus.Error]：玫瑰红静态警示点
 */
@Composable
internal fun ConversationStatusDot(
    status: ConversationStatus,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            ConversationStatus.Working -> {
                val transition = rememberInfiniteTransition(label = "working_pulse")
                val alpha by transition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "working_alpha"
                )
                val scale by transition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "working_scale"
                )
                val amberColor = colors.statusWorking
                // 外层柔光晕
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha * 0.35f)
                        .clip(CircleShape)
                        .background(amberColor)
                )
                // 内核实心点
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .graphicsLayer(alpha = alpha)
                        .clip(CircleShape)
                        .background(amberColor)
                )
            }
            ConversationStatus.WaitingUser -> {
                val transition = rememberInfiniteTransition(label = "waiting_ping")
                val pingScale by transition.animateFloat(
                    initialValue = 0.9f,
                    targetValue = 2.1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1400, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "waiting_pingScale"
                )
                val pingAlpha by transition.animateFloat(
                    initialValue = 0.7f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1400, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "waiting_pingAlpha"
                )
                val emeraldColor = colors.statusWaiting
                // 外层扩散波纹
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .graphicsLayer(scaleX = pingScale, scaleY = pingScale, alpha = pingAlpha)
                        .clip(CircleShape)
                        .background(emeraldColor)
                )
                // 内核实心点
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(emeraldColor)
                )
            }
            ConversationStatus.Idle -> {
                // 正常结束：晴空蓝静态低噪微圆点（5dp）
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(colors.statusIdle.copy(alpha = 0.85f))
                )
            }
            ConversationStatus.Error -> {
                // 报错：玫瑰红静态警示点（6dp）
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(colors.statusError)
                )
            }
        }
    }
}
