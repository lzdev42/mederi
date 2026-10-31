package xyz.mederi.ui.overview

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.ui.components.atoms.WorkingAnimationStyle
import xyz.mederi.ui.components.atoms.workingAnimation

/** 同一任务组只在整栏呼吸；文字和单个状态图标保持稳定，折叠时也能感知。 */
@Composable
internal fun Modifier.overviewTaskIndicator(running: Boolean): Modifier = workingAnimation(
    style = WorkingAnimationStyle.BreathingPulse,
    enabled = running,
    shape = RoundedCornerShape(MederiRadius.Control),
    primaryColor = LocalMederiColors.current.accentPrimary,
    strokeWidth = 1.5.dp,
)
