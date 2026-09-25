package xyz.emuci.diagram.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 从 Material 3 [ColorScheme] 生成图表主题。
 * 只映射 app 侧颜色 Token（fallback 源码框等）与明暗判定；
 * 图形配色交给 Mermaid 内置主题（'default'/'dark'），不注入 themeVariables 调色板。
 */
fun DiagramTheme.Companion.material3(
    colorScheme: ColorScheme,
    base: DiagramTheme = DiagramTheme.Default,
): DiagramTheme {
    val isDark = colorScheme.surface.luminance() < 0.5f
    val colors = ThemeColors(
        canvas = colorScheme.surface,
        surface = if (isDark) colorScheme.surfaceContainerLow else colorScheme.surface,
        surfaceAlt = if (isDark) colorScheme.surfaceContainerLowest else colorScheme.surfaceContainerHighest,
        textPrimary = colorScheme.onSurface,
        textSecondary = colorScheme.onSurfaceVariant,
        border = colorScheme.outlineVariant,
        accent = colorScheme.primary,
        accentSecondary = colorScheme.secondary,
        accentTertiary = colorScheme.tertiary,
        danger = colorScheme.error,
        success = blend(colorScheme.primary, colorScheme.tertiary, 0.35f),
        warning = blend(colorScheme.tertiary, colorScheme.error, 0.2f),
        selection = colorScheme.secondaryContainer,
        diagnostic = colorScheme.errorContainer,
    )

    return base.copy(colors = colors, isDark = isDark)
}

@Composable
fun DiagramTheme.Companion.material3(
    base: DiagramTheme = DiagramTheme.Default,
): DiagramTheme = material3(colorScheme = MaterialTheme.colorScheme, base = base)

private fun blend(
    start: Color,
    end: Color,
    ratio: Float,
): Color {
    val t = ratio.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * t,
        green = start.green + (end.green - start.green) * t,
        blue = start.blue + (end.blue - start.blue) * t,
        alpha = start.alpha + (end.alpha - start.alpha) * t,
    )
}
