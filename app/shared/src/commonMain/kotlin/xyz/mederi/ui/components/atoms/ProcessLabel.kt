package xyz.mederi.ui.components.atoms

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.LocalWorkspaceMotion
import xyz.mederi.theme.WorkspaceMotionPolicy
import xyz.mederi.theme.WorkspaceStyle

/** 运行中的过程标题使用克制的文字扫光；停止、离屏或减弱动效时不创建循环。 */
@Composable
internal fun ProcessLabel(
    text: String,
    color: Color,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val visibility = rememberWorkspaceTaskVisibility()
    var width by remember { mutableIntStateOf(0) }
    val band = with(LocalDensity.current) { 56.dp.toPx() }
    val style = if (LocalWorkspaceMotion.current.loops(running, visibility.visible) && width > 0) {
        val transition = rememberInfiniteTransition(label = "processLabelSweep")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(WorkspaceMotionPolicy.TaskCycleMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "processLabelSweepPosition",
        )
        val center = -band + progress * (width + band * 2)
        WorkspaceStyle.Title.copy(brush = Brush.linearGradient(
            colors = listOf(color, LocalMederiColors.current.textPrimary, color),
            start = Offset(center - band, 0f),
            end = Offset(center + band, 0f),
        ))
    } else WorkspaceStyle.Title.copy(color = color)

    Text(
        text = text,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(visibility.modifier).onSizeChanged { width = it.width },
    )
}
