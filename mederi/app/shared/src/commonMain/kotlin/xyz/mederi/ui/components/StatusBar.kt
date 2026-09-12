package xyz.mederi.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.delay
import xyz.mederi.theme.LocalMederiColors

/**
 * 对话轮次状态栏。在用户消息与 AI 回复之间动态显示当前 AI 正在做什么。
 *
 * 职能专一：只显示轮次运转过程状态（status），不展示报错信息（报错由专属错误组件承载）。
 * - status == Idle 时不渲染（完全消失）
 * - 发送中/请求模型中/重试中显示已耗时；超过 20s 变警示色提示响应较慢
 *
 * 计时锚定 [startedAtMillis]（发送请求时刻）：每秒用 `now - startedAtMillis` 重算，
 * 切换会话回来不重置（非"从 0 每秒 +1"的本地累加）。
 */
@Composable
fun StatusBar(
    status: TurnStatus,
    startedAtMillis: Long? = null,
    modifier: Modifier = Modifier,
) {
    if (status == TurnStatus.Idle) return

    val colors = LocalMederiColors.current
    val (icon, tint) = statusIconAndColor(status, colors)

    // 每秒重算当前时刻——elapsed = now - startedAtMillis（锚定发送请求时刻）
    var now by remember { mutableLongStateOf(xyz.mederi.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (true) {
            delay(1000)
            now = xyz.mederi.currentTimeMillis()
        }
    }

    val elapsedMs = if (startedAtMillis != null && startedAtMillis > 0) {
        (now - startedAtMillis).coerceAtLeast(0L)
    } else 0L

    val showElapsed = status == TurnStatus.Sending || status == TurnStatus.Preparing || status == TurnStatus.Retrying
    val slowResponse = showElapsed && elapsedMs >= 20_000

    AnimatedVisibility(
        visible = status != TurnStatus.Idle,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceCard.copy(alpha = 0.6f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 左侧图标 + 动画
            when (status) {
                TurnStatus.Preparing, TurnStatus.Sending -> {
                    // 闪烁圆点
                    val transition = rememberInfiniteTransition(label = "prep")
                    val alpha by transition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "prepAlpha",
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(tint.copy(alpha = alpha)),
                    )
                }
                TurnStatus.Retrying, TurnStatus.WaitingAnswer -> {
                    icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp)) }
                }
                else -> {
                    CircularProgressIndicator(
                        color = tint,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            Text(
                text = status.label,
                color = if (slowResponse) colors.accentWarning else tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )

            // 已耗时（响应变慢时警示）
            if (showElapsed) {
                Text(
                    text = buildString {
                        append("(${elapsedMs / 1000}s)")
                        if (slowResponse) append(" 响应较慢")
                    },
                    color = if (slowResponse) colors.accentWarning else colors.textMuted,
                    fontSize = 11.sp,
                )
            }

            // Preparing/Sending 额外显示动态省略号
            if (status == TurnStatus.Preparing || status == TurnStatus.Sending) {
                val transition = rememberInfiniteTransition(label = "dots")
                val dotAlphas = (0..2).map { index ->
                    transition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = index * 200, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot$index",
                    )
                }
                dotAlphas.forEach { alpha ->
                    Text(
                        text = "·",
                        color = tint.copy(alpha = alpha.value),
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

private fun statusIconAndColor(
    status: TurnStatus,
    colors: xyz.mederi.theme.MederiColors,
): Pair<ImageVector?, Color> = when (status) {
    TurnStatus.Sending -> null to colors.textMuted
    TurnStatus.Preparing -> null to colors.textMuted
    TurnStatus.Retrying -> FeatherIcons.RefreshCw to colors.accentWarning
    TurnStatus.Thinking -> FeatherIcons.Zap to colors.accentPrimary
    TurnStatus.CallingTool -> FeatherIcons.Terminal to colors.accentSecondary
    TurnStatus.Generating -> FeatherIcons.Edit2 to colors.accentPrimary
    TurnStatus.WaitingAnswer -> FeatherIcons.HelpCircle to colors.accentWarning
    TurnStatus.Aborted -> FeatherIcons.Square to colors.textMuted
    TurnStatus.Idle -> null to colors.textMuted
}
