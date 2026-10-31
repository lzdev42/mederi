package xyz.mederi.ui.components

import xyz.mederi.ui.components.atoms.WorkspaceLoadingIndicator

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
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
import mederi.app.shared.generated.resources.status_compacting
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
import xyz.mederi.ui.TurnStatus
import xyz.mederi.ui.parseRetryHint
import xyz.mederi.theme.WorkspaceStyle
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
    TurnStatus.Compacting -> stringResource(Res.string.status_compacting)
    TurnStatus.Aborted -> stringResource(Res.string.status_aborted)
}

/**
 * 对话轮次状态栏。在用户消息与 AI 回复之间动态显示当前 AI 正在做什么。
 *
 * 职能专一：只显示轮次运转过程状态（status），不展示报错信息（报错由专属错误组件承载）。
 * - status == Idle 时不渲染（完全消失）
 * - Preparing/Sending/Retrying/Compacting 显示已耗时；Preparing 超过 20s 变警示色并改为"排队较长"文案
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

    val showElapsed = status == TurnStatus.Sending || status == TurnStatus.Preparing || status == TurnStatus.Compacting
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

    val indicatorColor = if (slowResponse) colors.accentWarning else tint
    // 主轮次反馈使用轻量行；真实计时与重试解析保持原样，后台任务不参与此处状态派生。
    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = indicatorColor,
                    modifier = Modifier.size(WorkspaceStyle.IconSize).align(Alignment.CenterVertically))
            } else {
                WorkspaceLoadingIndicator(
                    color = indicatorColor,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(WorkspaceStyle.IconSize).align(Alignment.CenterVertically),
                )
            }
            Text(
                text = labelText,
                color = if (slowResponse) colors.accentWarning else colors.textPrimary,
                style = WorkspaceStyle.Title,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            if (timerText.isNotBlank()) {
                Text(text = timerText, color = colors.textSecondary, style = WorkspaceStyle.Caption,
                    modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        val serverMsg = retryHint?.serverMsg
        if (status == TurnStatus.Retrying && !serverMsg.isNullOrBlank()) {
            Text(
                text = serverMsg,
                color = colors.textSecondary,
                style = WorkspaceStyle.Caption,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp),
            )
        }
    }
}

private fun statusIconAndColor(
    status: TurnStatus,
    colors: xyz.mederi.theme.MederiColors,
): Pair<ImageVector?, Color> = when (status) {
    TurnStatus.Sending -> null to colors.accentPrimary
    TurnStatus.Preparing -> null to colors.accentPrimary
    TurnStatus.Retrying -> FeatherIcons.RefreshCw to colors.accentWarning
    TurnStatus.Compacting -> FeatherIcons.Archive to colors.accentPrimary
    TurnStatus.Thinking -> FeatherIcons.Zap to colors.accentPrimary
    TurnStatus.CallingTool -> FeatherIcons.Terminal to colors.accentSecondary
    TurnStatus.Generating -> FeatherIcons.Edit2 to colors.accentPrimary
    TurnStatus.WaitingAnswer -> FeatherIcons.HelpCircle to colors.accentWarning
    TurnStatus.Aborted -> FeatherIcons.Square to colors.textMuted
    TurnStatus.Idle -> null to colors.textMuted
}
