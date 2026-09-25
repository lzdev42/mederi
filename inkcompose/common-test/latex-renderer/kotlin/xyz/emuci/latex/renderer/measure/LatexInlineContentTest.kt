package xyz.emuci.latex.renderer.measure

import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import xyz.emuci.latex.renderer.model.LatexConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class LatexInlineContentTest {
    @Test
    fun placeholderUsesFullCanvasDimensionsAndTextCenterAlignment() {
        val dimensions = LatexDimensions(
            widthPx = 24f,
            heightPx = 16f,
            baselinePx = 11f,
            contentWidthPx = 20f,
            contentHeightPx = 12f,
            contentBaselinePx = 9f
        )

        val inlineContent = dimensions.toInlineTextContent(
            density = Density(density = 2f, fontScale = 1f),
            latex = "\\frac{a}{b}",
            config = LatexConfig()
        )

        assertEquals(12.sp, inlineContent.placeholder.width)
        assertEquals(8.sp, inlineContent.placeholder.height)
        assertEquals(
            PlaceholderVerticalAlign.TextCenter,
            inlineContent.placeholder.placeholderVerticalAlign
        )
    }
}
