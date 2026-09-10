package xyz.emuci.latex.renderer.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderStyleFontSizeTest {
    @Test
    fun fontSizeDeclarationUsesBaseFontSizeInsteadOfCurrentFontSize() {
        val context = RenderContext(
            fontSize = 20.sp,
            baseFontSize = 20.sp,
            color = Color.Black
        )

        val small = context.applyFontSize(LatexNode.FontSize.SizeType.SMALL)
        val hugeInsideSmall = small.applyFontSize(LatexNode.FontSize.SizeType.HUGE_2)

        assertEquals(18f, small.fontSize.value, 0.001f)
        assertEquals(49.76f, hugeInsideSmall.fontSize.value, 0.001f)
    }
}
