package xyz.emuci.latex.renderer.export

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class SvgCanvasTest {
    @Test
    fun recordsCanvasCommandsAsVectorElements() {
        val bytes = renderToSvg(
            width = 120f,
            height = 48f,
            textAsPath = true,
            prettyPrint = true
        ) { canvas ->
            val paint = Paint().apply {
                color = Color(0xff336699)
                strokeWidth = 2f
            }
            canvas.drawLine(Offset(4f, 8f), Offset(116f, 40f), paint)
        }

        val svg = assertNotNull(bytes).decodeToString()
        assertContains(svg, "<svg")
        assertContains(svg, "viewBox=\"0 0 120 48\"")
        assertFalse("data:image" in svg, "SVG export must not embed a raster snapshot")
    }
}
