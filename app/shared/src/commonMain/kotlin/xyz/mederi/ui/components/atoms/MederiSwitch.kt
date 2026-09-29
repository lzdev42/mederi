package xyz.mederi.ui.components.atoms

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.LocalMederiColors

/**
 * 极简 mini 开关（原型 .mini-switch / .mcp-toggle-switch）：
 * 28x16 track（r10）+ 12x12 thumb + 2dp inset；ON: accentPrimary track + onAccentPrimary thumb；
 * OFF: divider track + textPrimary thumb；thumb 位移 animateDpAsState(120ms)。
 */
@Composable
fun MederiMiniSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val thumbOffset by animateDpAsState(if (checked) 14.dp else 2.dp, tween(120), label = "miniSwitchThumb")
    Box(
        modifier = modifier.size(width = 28.dp, height = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) colors.accentPrimary else colors.divider)
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            Modifier.size(12.dp).offset(x = thumbOffset, y = 2.dp)
                .clip(CircleShape)
                .background(if (checked) colors.onAccentPrimary else colors.textPrimary)
        )
    }
}