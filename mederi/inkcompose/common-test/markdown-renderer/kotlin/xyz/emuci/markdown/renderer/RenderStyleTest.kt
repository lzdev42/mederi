package xyz.emuci.markdown.renderer

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderStyleTest {

    @Test
    fun chatStyleHasCompactLayoutAndNoDividers() {
        val style = RenderStyle.Chat
        assertEquals(13.5.sp, style.bodyStyle.fontSize)
        assertEquals(20.sp, style.bodyStyle.lineHeight)
        assertEquals(6.dp, style.blockSpacing)
        assertFalse(style.showHeadingDividers)
        assertEquals(16.dp, style.listIndent)
    }

    @Test
    fun githubStyleHasGenerousLayoutAndDividers() {
        val style = RenderStyle.Github
        assertEquals(14.sp, style.bodyStyle.fontSize)
        assertEquals(22.sp, style.bodyStyle.lineHeight)
        assertEquals(16.dp, style.blockSpacing)
        assertTrue(style.showHeadingDividers)
        assertEquals(24.dp, style.listIndent)
        assertEquals(24.sp, style.headingStyles[0].fontSize) // H1
        assertEquals(20.sp, style.headingStyles[1].fontSize) // H2
    }

    @Test
    fun customStyleCopyAllowsGranularOverrides() {
        val custom = RenderStyle.Github.copy(
            blockSpacing = 10.dp,
            showHeadingDividers = false,
        )
        assertEquals(10.dp, custom.blockSpacing)
        assertFalse(custom.showHeadingDividers)
        assertEquals(14.sp, custom.bodyStyle.fontSize)
    }

    @Test
    fun markdownColorsFromMaterial3() {
        val colorScheme = lightColorScheme(
            primary = Color(0xFF123456),
            onSurface = Color(0xFF222222),
            onSurfaceVariant = Color(0xFF666666),
            surface = Color(0xFFFFFFFF),
            outlineVariant = Color(0xFFCCCCCC),
        )
        val colors = MarkdownColors.fromMaterial3(colorScheme)
        assertEquals(Color(0xFF222222), colors.textPrimary)
        assertEquals(Color(0xFF666666), colors.textSecondary)
        assertEquals(Color(0xFF123456), colors.link)
        assertEquals(Color(0xFFCCCCCC), colors.divider)
        assertFalse(colors.isDark)
    }

    @Test
    fun markdownColorsFromDarkMaterial3DetectsDark() {
        val colorScheme = darkColorScheme(
            surface = Color(0xFF101010),
            onSurface = Color(0xFFEEEEEE),
        )
        val colors = MarkdownColors.fromMaterial3(colorScheme)
        assertTrue(colors.isDark)
    }

    @Test
    fun themeFromStyleAndColorsComposesCorrectly() {
        val style = RenderStyle.Github.copy(blockSpacing = 12.dp)
        val colors = MarkdownColors.light().copy(
            textPrimary = Color(0xFF111111),
            link = Color(0xFF0066CC),
        )

        val theme = MarkdownTheme.from(style = style, colors = colors)

        // 验证几何尺寸继承自 style
        assertEquals(12.dp, theme.blockSpacing)
        assertEquals(style.bodyStyle.fontSize, theme.bodyStyle.fontSize)
        assertEquals(style.bodyStyle.lineHeight, theme.bodyStyle.lineHeight)
        assertTrue(theme.showHeadingDividers)

        // 验证颜色继承自 colors
        assertEquals(Color(0xFF111111), theme.bodyStyle.color)
        assertEquals(Color(0xFF0066CC), theme.linkColor)
        assertEquals(colors.inlineCodeBackground, theme.inlineCodeBackground)
    }
}
