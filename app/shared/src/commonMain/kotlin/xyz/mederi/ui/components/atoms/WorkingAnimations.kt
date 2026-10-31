package xyz.mederi.ui.components.atoms

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.LocalWorkspaceMotion
import xyz.mederi.theme.WorkspaceMotionPolicy

/** 历史调用兼容；所有样式统一为单色呼吸，避免不同任务各用一套运动语言。 */
enum class WorkingAnimationStyle { BorderBeam, ShimmerWave, BreathingPulse }

/** 裁剪后窗口面积决定循环是否可见；不是窗口焦点或系统偏好检测。 */
internal class WorkspaceTaskVisibility {
    var visible by mutableStateOf(false)
        private set
    val modifier: Modifier = Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = bounds.width > 0f && bounds.height > 0f
    }
}

@Composable
internal fun rememberWorkspaceTaskVisibility(): WorkspaceTaskVisibility = remember { WorkspaceTaskVisibility() }

/** 唯一任务循环；静态分支在创建transition之前退出，状态仍清晰可见。 */
@Composable
internal fun workspaceTaskPulseAlpha(
    running: Boolean,
    visible: Boolean = true,
    minAlpha: Float = 0.45f,
    maxAlpha: Float = 0.85f,
): Float {
    if (!LocalWorkspaceMotion.current.loops(running, visible)) return maxAlpha
    val transition = rememberInfiniteTransition(label = "workspaceTaskPulse")
    val alpha by transition.animateFloat(
        initialValue = minAlpha,
        targetValue = maxAlpha,
        animationSpec = infiniteRepeatable(
            tween(WorkspaceMotionPolicy.TaskCycleMillis / 2, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "workspaceTaskAlpha",
    )
    return alpha
}

/** style/secondaryColor/duration仅兼容已有调用，实际运动遵循设备统一策略。 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Modifier.workingAnimation(
    style: WorkingAnimationStyle = WorkingAnimationStyle.BorderBeam,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    primaryColor: Color = LocalMederiColors.current.accentPrimary,
    secondaryColor: Color = LocalMederiColors.current.accentSecondary,
    durationMillis: Int? = null,
    strokeWidth: Dp = 1.5.dp,
): Modifier = breathingPulse(enabled = enabled, shape = shape, pulseColor = primaryColor, strokeWidth = strokeWidth)

/** 旧流光入口兼容为共同呼吸；不再使用旋转渐变。 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Modifier.borderBeam(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    strokeWidth: Dp = 1.5.dp,
    primaryColor: Color = LocalMederiColors.current.accentPrimary,
    secondaryColor: Color = LocalMederiColors.current.accentSecondary,
    beamLengthFraction: Float = 0.35f,
    durationMillis: Int = 2400,
): Modifier = breathingPulse(enabled = enabled, shape = shape, pulseColor = primaryColor, strokeWidth = strokeWidth)

/** 旧扫描入口兼容为共同呼吸；不再扫过正文。 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Modifier.shimmerWave(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    highlightColor: Color = LocalMederiColors.current.accentPrimary,
    durationMillis: Int = 2200,
    shimmerWidth: Dp = 120.dp,
): Modifier = breathingPulse(enabled = enabled, shape = shape, pulseColor = highlightColor)

/** 单色边界明暗变化；禁用时不附加边界，减弱动效时保留静态边界。 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Modifier.breathingPulse(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    pulseColor: Color = LocalMederiColors.current.accentPrimary,
    minAlpha: Float = 0.25f,
    maxAlpha: Float = 0.85f,
    strokeWidth: Dp = 1.5.dp,
    durationMillis: Int = 2000,
): Modifier {
    if (!enabled) return this
    val visibility = rememberWorkspaceTaskVisibility()
    val alpha = workspaceTaskPulseAlpha(running = enabled, visible = visibility.visible, minAlpha = minAlpha, maxAlpha = maxAlpha)
    return then(visibility.modifier).drawWithContent {
        drawContent()
        val strokePx = strokeWidth.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)
        val color = pulseColor.copy(alpha = pulseColor.alpha * alpha)
        when (outline) {
            is Outline.Rounded -> drawRoundRect(
                color = color,
                topLeft = Offset(strokePx / 2f, strokePx / 2f),
                size = androidx.compose.ui.geometry.Size((size.width - strokePx).coerceAtLeast(0f), (size.height - strokePx).coerceAtLeast(0f)),
                cornerRadius = outline.roundRect.topLeftCornerRadius,
                style = Stroke(strokePx),
            )
            is Outline.Rectangle -> drawRect(
                color = color,
                topLeft = Offset(strokePx / 2f, strokePx / 2f),
                size = androidx.compose.ui.geometry.Size((size.width - strokePx).coerceAtLeast(0f), (size.height - strokePx).coerceAtLeast(0f)),
                style = Stroke(strokePx),
            )
            is Outline.Generic -> drawPath(outline.path, color, style = Stroke(strokePx))
        }
    }
}

/** 保留历史命名与尺寸参数，运行时仅圆点呼吸，不再扩散或缩放。 */
@Composable
fun RadarPulseDot(
    modifier: Modifier = Modifier,
    color: Color = LocalMederiColors.current.accentPrimary,
    size: Dp = 8.dp,
    enabled: Boolean = true,
) {
    val visibility = rememberWorkspaceTaskVisibility()
    val alpha = workspaceTaskPulseAlpha(running = enabled, visible = visibility.visible)
    Box(modifier.then(visibility.modifier).size(size).clip(CircleShape).background(color.copy(alpha = color.alpha * alpha)))
}
