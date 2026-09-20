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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.delay
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.status_aborted
import mederi.app.shared.generated.resources.status_calling_tool
import mederi.app.shared.generated.resources.status_elapsed
import mederi.app.shared.generated.resources.status_generating
import mederi.app.shared.generated.resources.status_idle
import mederi.app.shared.generated.resources.status_preparing
import mederi.app.shared.generated.resources.status_retry_in
import mederi.app.shared.generated.resources.status_retrying
import mederi.app.shared.generated.resources.status_retrying_progress
import mederi.app.shared.generated.resources.status_sending
import mederi.app.shared.generated.resources.status_slow_response
import mederi.app.shared.generated.resources.status_thinking
import mederi.app.shared.generated.resources.status_waiting_answer
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import kotlin.time.Duration.Companion.milliseconds

/** TurnStatus 显示文案唯一映射点（枚举不持有表现层文案，i18n 约定）。 */
@Composable
private fun turnStatusLabel(status: TurnStatus): String = when (status) {
    TurnStatus.Idle -> stringResource(Res.string.status_idle)
    TurnStatus.Sending -> stringResource(Res.string.status_sending)
    TurnStatus.Preparing -> stringResource(Res.string.status_preparing)
    TurnStatus.Thinking -> stringResource(Res.string.status_thinking)
    TurnStatus.CallingTool -> stringResource(Res.string.status_calling_tool)
    TurnStatus.Generating -> stringResource(Res.string.status_generating)
    TurnStatus.WaitingAnswer -> stringResource(Res.string.status_waiting_answer)
    TurnStatus.Retrying -> stringResource(Res.string.status_retrying)
    TurnStatus.Aborted -> stringResource(Res.string.status_aborted)
}

/**
 * 对话轮次状态栏。在用户消息与 AI 回复之间动态显示当前 AI 正在做什么。
 *
 * 职能专一：只显示轮次运转过程状态（status），不展示报错信息（报错由专属错误组件承载）。
 * - status == Idle 时不渲染（完全消失）
 * - Preparing/Sending/Retrying 显示已耗时；Preparing 超过 20s 变警示色并改为"排队较长"文案
 * - Retrying 显示供应商真实错误信息（serverMsg）+ 轮次计数，信息来自 [statusHint]
 *
 * 计时锚定 [startedAtMillis]（发送请求时刻）：每秒用 `now - startedAtMillis` 重算，
 * 切换会话回来不重置（非"从 0 每秒 +1"的本地累加）。
 */
@Composable
fun StatusBar(
    status: TurnStatus,
    startedAtMillis: Long? = null,
    statusHint: String? = null,
    modifier: Modifier = Modifier,
) {
    if (!status.shouldDisplayInStatusBar) return

    val colors = LocalMederiColors.current
    val (icon, tint) = statusIconAndColor(status, colors)

    // 每秒重算当前时刻——elapsed = now - startedAtMillis（锚定发送请求时刻）
    var now by remember { mutableLongStateOf(xyz.mederi.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (true) {
            delay(1000.milliseconds)
            now = xyz.mederi.currentTimeMillis()
        }
    }


    // Retrying 状态：解析 statusHint 得到真实错误信息、重试轮次及重试目标时间
    val retryHint = if (status == TurnStatus.Retrying) parseRetryHint(statusHint) else null

    val retryCountdownSec = if (retryHint?.retryAtMillis != null) {
        val remMs = retryHint.retryAtMillis - now
        if (remMs > 0) (remMs + 999) / 1000 else 0L
    } else null

    val elapsedMs = if (startedAtMillis != null && startedAtMillis > 0) {
        (now - startedAtMillis).coerceAtLeast(0L)
    } else 0L

    val showElapsed = status == TurnStatus.Sending || status == TurnStatus.Preparing
    val slowResponse = status == TurnStatus.Preparing && elapsedMs >= 20_000

    // 主标签文案
    val labelText = when {
        status == TurnStatus.Preparing && slowResponse -> stringResource(Res.string.status_slow_response)
        status == TurnStatus.Retrying -> stringResource(Res.string.status_retrying_progress, retryHint?.attempt ?: "?", retryHint?.max ?: "?")
        else -> turnStatusLabel(status)
    }

    val timerText = when {
        status == TurnStatus.Retrying -> {
            if (retryCountdownSec != null && retryCountdownSec > 0) stringResource(Res.string.status_retry_in, retryCountdownSec)
            else stringResource(Res.string.status_elapsed, elapsedMs / 1000)   // 无 retryAt（旧格式/缺 delayMs）回退已耗时
        }
        showElapsed -> stringResource(Res.string.status_elapsed, elapsedMs / 1000)
        else -> ""
    }

    AnimatedVisibility(
        visible = status.shouldDisplayInStatusBar,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceCard.copy(alpha = 0.6f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 主行：图标 + 状态文案 + 耗时/倒计时
            Row(
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
                    text = labelText,
                    color = if (slowResponse) colors.accentWarning else tint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )

                // 倒计时或耗时
                if (timerText.isNotBlank()) {
                    Text(
                        text = timerText,
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

            // 重试：第二行显示供应商真实错误信息（serverMsg）
            val serverMsg = retryHint?.serverMsg
            if (status == TurnStatus.Retrying && !serverMsg.isNullOrBlank()) {
                Text(
                    text = serverMsg,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp),
                )
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
