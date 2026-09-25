package xyz.emuci.latex.renderer.model

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class LatexThemeTest {
    private val defaultLightColors = LatexThemeColors(
        color = Color.Black,
        backgroundColor = Color.Transparent
    )

    private val defaultDarkColors = LatexThemeColors(
        color = Color.White,
        backgroundColor = Color.Transparent
    )

    @Test
    fun autoThemeFollowsSystemTheme() {
        val theme = LatexTheme.auto()

        assertEquals(defaultLightColors, theme.resolve(systemInDarkTheme = false))
        assertEquals(defaultDarkColors, theme.resolve(systemInDarkTheme = true))
    }

    @Test
    fun fixedThemeIgnoresSystemTheme() {
        assertEquals(defaultLightColors, LatexTheme.light().resolve(systemInDarkTheme = true))
        assertEquals(defaultDarkColors, LatexTheme.dark().resolve(systemInDarkTheme = false))
    }

    @Test
    fun material3MapsOnSurfaceAndSurfaceForLightScheme() {
        val scheme = lightColorScheme(
            surface = Color(0xFFF7F7F7),
            onSurface = Color(0xFF111111)
        )

        val theme = LatexTheme.material3(scheme)

        assertEquals(
            LatexThemeColors(
                color = Color(0xFF111111),
                backgroundColor = Color(0xFFF7F7F7)
            ),
            theme.resolve(systemInDarkTheme = false)
        )
    }

    @Test
    fun material3MapsOnSurfaceAndSurfaceForDarkScheme() {
        val scheme = darkColorScheme(
            surface = Color(0xFF121212),
            onSurface = Color(0xFFF0F0F0)
        )

        val theme = LatexTheme.material3(scheme)

        assertEquals(
            LatexThemeColors(
                color = Color(0xFFF0F0F0),
                backgroundColor = Color(0xFF121212)
            ),
            theme.resolve(systemInDarkTheme = false)
        )
    }
}
