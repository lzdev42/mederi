package xyz.emuci.latex.renderer.layout

import xyz.emuci.latex.renderer.layout.measurer.calculateKaTeXAccentVerticalPlacement
import kotlin.test.Test
import kotlin.test.assertEquals

class AccentPlacementTest {
    @Test
    fun hatUsesKaTeXNegativeClearanceInsteadOfPositiveGap() {
        val placement = calculateKaTeXAccentVerticalPlacement(
            baseHeight = 0.43056f,
            baseDepth = 0f,
            accentHeight = 0.69444f,
            accentDepth = 0f,
            xHeight = 0.431f
        )

        assertEquals(0.69444f, placement.totalHeight, 0.00001f)
        assertEquals(0.69444f, placement.baseline, 0.00001f)
        assertEquals(0.26388f, placement.contentY, 0.00001f)
        assertEquals(0f, placement.accentY, 0.00001f)
    }
}
