package xyz.emuci.latex.renderer.export

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import xyz.emuci.latex.renderer.model.LatexConfig
import xyz.emuci.latex.renderer.model.LatexFontFamilies
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class LatexSvgExporterTest {
    @Test
    fun exportsMeasuredFormulaAsPortablePaths() {
        val exporter = createExporter()
        val result = assertNotNull(
            exporter.exportSvg(
                latex = "\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}",
                config = LatexConfig(fontSize = 24.sp)
            )
        )

        assertContains(result.svg, "<svg")
        assertContains(result.svg, "<path")
        assertContains(result.svg, "viewBox=")
        assertFalse("<image" in result.svg)
        assertFalse("<text" in result.svg, "PATH mode must not depend on installed fonts")
    }

    @Test
    fun sequentialExportsResetParserInput() {
        val exporter = createExporter()
        val first = assertNotNull(exporter.exportSvg("x^2"))
        val second = assertNotNull(exporter.exportSvg("\\sqrt{y}"))

        assertNotEquals(first.svg, second.svg)
    }

    private fun createExporter(): LatexExporterState {
        val density = Density(1f)
        val family = FontFamily.Serif
        val families = LatexFontFamilies(
            main = family,
            math = family,
            ams = family,
            sansSerif = FontFamily.SansSerif,
            monospace = FontFamily.Monospace,
            caligraphic = family,
            fraktur = family,
            script = family,
            size1 = family,
            size2 = family,
            size3 = family,
            size4 = family
        )
        return LatexExporterState(
            density = density,
            textMeasurer = TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(),
                defaultDensity = density,
                defaultLayoutDirection = LayoutDirection.Ltr
            ),
            fontFamilies = families
        )
    }
}
