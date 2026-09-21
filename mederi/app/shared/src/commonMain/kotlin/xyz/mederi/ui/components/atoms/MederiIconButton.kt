package xyz.mederi.ui.components.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.LocalMederiColors

/**
 * 统一图标工具按钮：圆/方形底色 + 图标。
 * 对齐既有 IconToolButton 样式（CircleShape + buttonSecondary 背景 + textSecondary 图标，
 * 图标尺寸 = size>=36 时 18dp 否则 0.5*size）。[active] = true 时高亮底纹与 [activeTint] 图标，
 * 用于复制反馈、选中态等。
 */
@Composable
fun MederiIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: Int = 28,
    active: Boolean = false,
    activeTint: Color = LocalMederiColors.current.accentPrimary,
    shape: Shape = CircleShape,
) {
    val colors = LocalMederiColors.current
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(shape)
            .background(
                if (active) colors.accentPrimary.copy(alpha = 0.15f)
                else colors.buttonSecondary
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) activeTint else colors.textSecondary,
            modifier = Modifier.size(if (size >= 36) 18.dp else (size * 0.5f).dp),
        )
    }
}