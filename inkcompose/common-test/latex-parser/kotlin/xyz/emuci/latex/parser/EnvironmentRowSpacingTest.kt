package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EnvironmentRowSpacingTest {
    @Test
    fun should_preserve_explicit_empty_rows() {
        val aligned = LatexParser().parse(
            "\\begin{aligned}\\\\a&=b\\\\\\\\c&=d\\end{aligned}"
        ).children.single()

        assertEquals(4, assertIs<LatexNode.Aligned>(aligned).rows.size)
    }

    @Test
    fun should_not_treat_optional_row_spacing_as_a_row() {
        val array = LatexParser().parse(
            "\\begin{array}{cc}a&b\\\\[.2cm]\\hline c&d\\end{array}"
        ).children.single()
        val parsed = assertIs<LatexNode.Array>(array)

        assertEquals(3, parsed.rows.size)
        assertEquals(LatexNode.RowGap(0.2, "cm"), parsed.rowGaps.single())
    }

    @Test
    fun should_not_consume_environment_after_unclosed_row_spacing() {
        val array = LatexParser().parse(
            "\\begin{array}{cc}a&b\\\\[.2cm c&d\\end{array}"
        ).children.single()

        assertEquals(2, assertIs<LatexNode.Array>(array).rows.size)
    }
}
