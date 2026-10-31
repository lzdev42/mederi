package xyz.mederi.ui.components.atoms

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.*

/** 真实无进度请求的共同指示器。静态模式保留不定进度语义，停止/隐藏不创建动画。 */
@Composable
internal fun WorkspaceLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = LocalMederiColors.current.accentPrimary,
    strokeWidth: Dp = 1.5.dp,
    running: Boolean = true,
    visible: Boolean = true,
) {
    if (!running || !visible) return
    val policy = LocalWorkspaceMotion.current
    var inViewport by remember { mutableStateOf(false) }
    val rotation = if (policy.loops(running, visible && inViewport)) {
        val transition = rememberInfiniteTransition(label = "workspaceRequestWaiting")
        val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(
            tween(WorkspaceMotionPolicy.LoadingCycleMillis, easing = LinearEasing), RepeatMode.Restart), label = "requestRotation")
        angle
    } else 0f
    Canvas(modifier.size(WorkspaceStyle.IconSize).onGloballyPositioned {
        val bounds = it.boundsInWindow()
        inViewport = bounds.width > 0f && bounds.height > 0f
    }.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val stroke = strokeWidth.toPx().coerceAtMost(size.minDimension / 2)
        val inset = stroke / 2
        val arcSize = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f))
        drawArc(color.copy(alpha = color.alpha * 0.16f), 0f, 360f, false,
            Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(color, rotation - 90f, 250f, false, Offset(inset, inset), arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
