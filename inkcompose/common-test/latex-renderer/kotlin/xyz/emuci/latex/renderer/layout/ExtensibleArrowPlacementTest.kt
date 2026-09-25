package xyz.emuci.latex.renderer.layout

import xyz.emuci.latex.renderer.layout.measurer.calculateKaTeXExtensibleArrowVerticalPlacement
import kotlin.test.Test
import kotlin.test.assertEquals

class ExtensibleArrowPlacementTest {
    @Test
    fun arrowCenterIsOnMathAxisAndLabelsKeepKaTeXGap() {
        val placement = calculateKaTeXExtensibleArrowVerticalPlacement(
            aboveHeight = 10f,
            belowHeight = 8f,
            arrowTopExtent = 2.5f,
            arrowBottomExtent = 2.5f,
            labelGap = 1.11f,
            axisHeight = 5f,
            outerPadding = 0.75f
        )

        assertEquals(14.36f, placement.arrowCenterY, 0.0001f)
        assertEquals(19.36f, placement.baseline, 0.0001f)
        assertEquals(17.97f, placement.belowY, 0.0001f)
        assertEquals(26.72f, placement.totalHeight, 0.0001f)
        assertEquals(5f, placement.baseline - placement.arrowCenterY, 0.0001f)
    }
}
