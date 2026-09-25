package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.parser.visitor.MathMLVisitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AtopTest {
    private val parser = LatexParser()

    @Test
    fun should_parse_atop_as_ruleless_fraction() {
        val group = parser.parse("{g_1+g_2 \\atop i+j=n-2}").children.single()
        assertIs<LatexNode.Group>(group)
        val fraction = group.children.single()
        assertIs<LatexNode.Fraction>(fraction)
        assertEquals(LatexNode.Fraction.FractionStyle.RULELESS, fraction.style)
    }

    @Test
    fun should_parse_atop_in_document_math_list() {
        val fraction = parser.parse("a \\atop b").children.single()

        assertIs<LatexNode.Fraction>(fraction)
        assertEquals(LatexNode.Fraction.FractionStyle.RULELESS, fraction.style)
        assertEquals(0, fraction.sourceRange?.start)
        assertEquals(9, fraction.sourceRange?.end)
        assertEquals(0, fraction.numerator.sourceRange?.start)
        assertEquals(2, fraction.numerator.sourceRange?.end)
        assertEquals(7, fraction.denominator.sourceRange?.start)
        assertEquals(9, fraction.denominator.sourceRange?.end)
    }

    @Test
    fun should_parse_atop_in_inline_and_display_math_lists() {
        val inline = parser.parse("\$a \\atop b\$").children.single()
        val inlineMath = assertIs<LatexNode.InlineMath>(inline)
        assertIs<LatexNode.Fraction>(inlineMath.children.single())

        val display = parser.parse("\$\$a \\atop b\$\$").children.single()
        val displayMath = assertIs<LatexNode.DisplayMath>(display)
        assertIs<LatexNode.Fraction>(displayMath.children.single())
    }

    @Test
    fun should_emit_zero_line_thickness_mathml() {
        val document = parser.parse("{a \\atop b}")
        val mathMl = MathMLVisitor.convert(document)
        assertTrue(mathMl.contains("""linethickness="0""""))
    }

    @Test
    fun should_keep_regular_fraction_rule() {
        val fraction = parser.parse("\\frac{a}{b}").children.single()
        assertIs<LatexNode.Fraction>(fraction)
        assertEquals(LatexNode.Fraction.FractionStyle.NORMAL, fraction.style)
    }
}
