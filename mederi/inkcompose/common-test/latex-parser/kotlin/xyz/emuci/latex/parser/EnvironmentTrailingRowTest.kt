package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EnvironmentTrailingRowTest {
    @Test
    fun should_ignore_whitespace_after_trailing_matrix_separator() {
        val matrix = LatexParser().parse(
            "\\begin{matrix}a & b \\\\ c & d \\\\ \\end{matrix}"
        ).children.single()

        assertEquals(2, assertIs<LatexNode.Matrix>(matrix).rows.size)
    }

    @Test
    fun should_ignore_whitespace_after_trailing_cases_separator() {
        val cases = LatexParser().parse(
            "\\begin{cases}a & x > 0 \\\\ b & x < 0 \\\\ \\end{cases}"
        ).children.single()

        assertEquals(2, assertIs<LatexNode.Cases>(cases).cases.size)
    }
}
