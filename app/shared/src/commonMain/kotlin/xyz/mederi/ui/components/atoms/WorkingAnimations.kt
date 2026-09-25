package xyz.mederi.ui.components.atoms

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.LocalMederiColors
import kotlin.math.max

/**
 * 工作态动效风格枚举。
 * 支持全局任意 UI 容器一键调用：
 * - [BorderBeam]：流光边框（360° 顺畅环绕旋转的高亮光束，前沿 AI 工具科技质感，余光感知度最高）
 * - [ShimmerWave]：微光扫描波（斜向高光波段周期掠过，符合经典数据流淌与计算中隐喻）
 * - [BreathingPulse]：呼吸脉冲（整块边框与外轮廓柔光明暗起伏，内敛生命感）
 */
enum class WorkingAnimationStyle {
    /** 方案一：流光边框（首推） */
    BorderBeam,

    /** 方案二：微光扫描 */
    ShimmerWave,

    /** 方案三：呼吸脉冲 */
    BreathingPulse
}

/**
 * 统一工作态动效 Modifier。
 * 任意 Composable（如 Box, Row, Card, Column 等）只要添加：
 * ```kotlin
 * Modifier.workingAnimation(
 *     style = WorkingAnimationStyle.BorderBeam,
 *     enabled = subagent.status == "RUNNING"
 * )
 * ```
 * 即可立即拥有对应的动态工作指示效果。
 *
 * @param style 动效类型，默认 [WorkingAnimationStyle.BorderBeam]
 * @param enabled 是否开启（若为 false 则零开销直通渲染）
 * @param shape 轮廓形状，如 [RoundedCornerShape]
 * @param primaryColor 动效主高光颜色（默认读取主题的 [LocalMederiColors.current.accentPrimary]）
 * @param secondaryColor 动效次渐变颜色（仅对 [WorkingAnimationStyle.BorderBeam] 有效）
 * @param durationMillis 单次循环时长（毫秒）
 * @param strokeWidth 边框流光/脉冲线条粗细
 */
@Composable
fun Modifier.workingAnimation(
    style: WorkingAnimationStyle = WorkingAnimationStyle.BorderBeam,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    primaryColor: Color = LocalMederiColors.current.accentPrimary,
    secondaryColor: Color = LocalMederiColors.current.accentSecondary,
    durationMillis: Int? = null,
    strokeWidth: Dp = 1.5.dp
): Modifier {
    if (!enabled) return this
    return when (style) {
        WorkingAnimationStyle.BorderBeam -> borderBeam(
            enabled = true,
            shape = shape,
            strokeWidth = strokeWidth,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            durationMillis = durationMillis ?: 2400
        )
        WorkingAnimationStyle.ShimmerWave -> shimmerWave(
            enabled = true,
            shape = shape,
            highlightColor = primaryColor,
            durationMillis = durationMillis ?: 2200
        )
        WorkingAnimationStyle.BreathingPulse -> breathingPulse(
            enabled = true,
            shape = shape,
            pulseColor = primaryColor,
            strokeWidth = strokeWidth,
            durationMillis = durationMillis ?: 2000
        )
    }
}

/**
 * 方案一：流光边框修饰符 (Border Beam)
 * 沿着 shape 外边框流动一段带柔和拖尾的渐变高光光束。
 */
@Composable
fun Modifier.borderBeam(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    strokeWidth: Dp = 1.5.dp,
    primaryColor: Color = LocalMederiColors.current.accentPrimary,
    secondaryColor: Color = LocalMederiColors.current.accentSecondary,
    beamLengthFraction: Float = 0.35f,
    durationMillis: Int = 2400
): Modifier {
    if (!enabled) return this

    val infiniteTransition = rememberInfiniteTransition(label = "border_beam_transition")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "border_beam_angle"
    )

    // 缓存 Path，尺寸未变化时不重新分配 Path 对象
    var cachedSize by remember { mutableStateOf(Size.Zero) }
    var cachedBorderPath by remember { mutableStateOf<Path?>(null) }

    return this.drawWithContent {
        drawContent()

        val strokePx = strokeWidth.toPx()
        if (size != cachedSize || cachedBorderPath == null) {
            cachedSize = size
            val outline = shape.createOutline(size, layoutDirection, this)
            cachedBorderPath = when (outline) {
                is Outline.Rounded -> {
                    val rr = outline.roundRect
                    val outer = Path().apply { addRoundRect(rr) }
                    val inner = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = rr.left + strokePx,
                                top = rr.top + strokePx,
                                right = (rr.right - strokePx).coerceAtLeast(rr.left + strokePx),
                                bottom = (rr.bottom - strokePx).coerceAtLeast(rr.top + strokePx),
                                topLeftCornerRadius = CornerRadius(
                                    (rr.topLeftCornerRadius.x - strokePx).coerceAtLeast(0f),
                                    (rr.topLeftCornerRadius.y - strokePx).coerceAtLeast(0f)
                                ),
                                topRightCornerRadius = CornerRadius(
                                    (rr.topRightCornerRadius.x - strokePx).coerceAtLeast(0f),
                                    (rr.topRightCornerRadius.y - strokePx).coerceAtLeast(0f)
                                ),
                                bottomRightCornerRadius = CornerRadius(
                                    (rr.bottomRightCornerRadius.x - strokePx).coerceAtLeast(0f),
                                    (rr.bottomRightCornerRadius.y - strokePx).coerceAtLeast(0f)
                                ),
                                bottomLeftCornerRadius = CornerRadius(
                                    (rr.bottomLeftCornerRadius.x - strokePx).coerceAtLeast(0f),
                                    (rr.bottomLeftCornerRadius.y - strokePx).coerceAtLeast(0f)
                                )
                            )
                        )
                    }
                    Path().apply { op(outer, inner, PathOperation.Difference) }
                }
                is Outline.Rectangle -> {
                    val outer = Path().apply { addRect(outline.rect) }
                    val inner = Path().apply {
                        addRect(
                            Rect(
                                left = outline.rect.left + strokePx,
                                top = outline.rect.top + strokePx,
                                right = (outline.rect.right - strokePx).coerceAtLeast(outline.rect.left + strokePx),
                                bottom = (outline.rect.bottom - strokePx).coerceAtLeast(outline.rect.top + strokePx)
                            )
                        )
                    }
                    Path().apply { op(outer, inner, PathOperation.Difference) }
                }
                is Outline.Generic -> outline.path
            }
        }

        cachedBorderPath?.let { borderPath ->
            clipPath(borderPath) {
                rotate(degrees = angle, pivot = center) {
                    val maxDimension = max(size.width, size.height) * 1.5f
                    val sweepBrush = Brush.sweepGradient(
                        0.0f to Color.Transparent,
                        (1f - beamLengthFraction) to Color.Transparent,
                        (1f - beamLengthFraction * 0.45f) to primaryColor.copy(alpha = 0.45f),
                        (1f - beamLengthFraction * 0.12f) to secondaryColor,
                        1.0f to Color.White
                    )
                    drawCircle(
                        brush = sweepBrush,
                        radius = maxDimension,
                        center = center
                    )
                }
            }
        }
    }
}

/**
 * 方案二：微光扫描修饰符 (Shimmer Wave)
 * 周期性从左至右掠过一道半透明高光波带。
 */
@Composable
fun Modifier.shimmerWave(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    highlightColor: Color = LocalMederiColors.current.accentPrimary,
    durationMillis: Int = 2200,
    shimmerWidth: Dp = 120.dp
): Modifier {
    if (!enabled) return this

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer_transition")
    val progress by infiniteTransition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_progress"
    )

    var cachedSize by remember { mutableStateOf(Size.Zero) }
    var cachedOutlinePath by remember { mutableStateOf<Path?>(null) }

    return this.drawWithContent {
        drawContent()

        if (size != cachedSize || cachedOutlinePath == null) {
            cachedSize = size
            val outline = shape.createOutline(size, layoutDirection, this)
            cachedOutlinePath = when (outline) {
                is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                is Outline.Generic -> outline.path
            }
        }

        cachedOutlinePath?.let { clipPath ->
            clipPath(clipPath) {
                val shimmerPx = shimmerWidth.toPx()
                val currentX = size.width * progress
                val sweepBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        highlightColor.copy(alpha = 0.05f),
                        highlightColor.copy(alpha = 0.28f),
                        highlightColor.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    start = Offset(currentX - shimmerPx / 2f, 0f),
                    end = Offset(currentX + shimmerPx / 2f, size.height)
                )
                drawRect(brush = sweepBrush)
            }
        }
    }
}

/**
 * 方案三：呼吸脉冲修饰符 (Breathing Pulse)
 * 边框与外轮廓柔光随着呼吸节奏明暗脉动。
 */
@Composable
fun Modifier.breathingPulse(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    pulseColor: Color = LocalMederiColors.current.accentPrimary,
    minAlpha: Float = 0.25f,
    maxAlpha: Float = 0.85f,
    strokeWidth: Dp = 1.5.dp,
    durationMillis: Int = 2000
): Modifier {
    if (!enabled) return this

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
    val alpha by infiniteTransition.animateFloat(
        initialValue = minAlpha,
        targetValue = maxAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis / 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    return this.drawWithContent {
        drawContent()

        val strokePx = strokeWidth.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)
        when (outline) {
            is Outline.Rounded -> {
                drawRoundRect(
                    color = pulseColor.copy(alpha = alpha),
                    topLeft = Offset(strokePx / 2f, strokePx / 2f),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = outline.roundRect.topLeftCornerRadius,
                    style = Stroke(width = strokePx)
                )
            }
            is Outline.Rectangle -> {
                drawRect(
                    color = pulseColor.copy(alpha = alpha),
                    topLeft = Offset(strokePx / 2f, strokePx / 2f),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    style = Stroke(width = strokePx)
                )
            }
            is Outline.Generic -> {
                drawPath(
                    path = outline.path,
                    color = pulseColor.copy(alpha = alpha),
                    style = Stroke(width = strokePx)
                )
            }
        }
    }
}

/**
 * 运行中雷达扩散脉冲小微标圆点 (常用于 Badge、图标侧灯)
 */
@Composable
fun RadarPulseDot(
    modifier: Modifier = Modifier,
    color: Color = LocalMederiColors.current.accentPrimary,
    size: Dp = 8.dp,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (enabled) {
            val infiniteTransition = rememberInfiniteTransition(label = "radar_dot_pulse")
            val ringScale by infiniteTransition.animateFloat(
                initialValue = 0.8f,
                targetValue = 2.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "radar_scale"
            )
            val ringAlpha by infiniteTransition.animateFloat(
                initialValue = 0.75f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "radar_alpha"
            )
            Box(
                modifier = Modifier
                    .size(size)
                    .graphicsLayer(scaleX = ringScale, scaleY = ringScale, alpha = ringAlpha)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Box(
            modifier = Modifier
                .size(size * 0.65f)
                .clip(CircleShape)
                .background(color)
        )
    }
}
