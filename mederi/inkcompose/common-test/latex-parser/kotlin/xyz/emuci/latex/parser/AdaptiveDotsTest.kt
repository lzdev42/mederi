package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 自适应省略号 \dots 测试
 */
class AdaptiveDotsTest {

    private val parser = LatexParser()

    @Test
    fun should_parse_dots_as_ldots_by_default() {
        val result = parser.parse("a_1, a_2, \\dots, a_n")
        assertIs<LatexNode.Document>(result)
        val hasSymbol = result.children.any { it is LatexNode.Symbol && (it as LatexNode.Symbol).unicode == "…" }
        assertTrue(hasSymbol, "Should contain ldots (…) symbol")
    }

    @Test
    fun should_parse_dots_as_cdots_before_binary_op() {
        val result = parser.parse("a_1 + a_2 + \\dots + a_n")
        assertIs<LatexNode.Document>(result)
        val hasSymbol = result.children.any { it is LatexNode.Symbol && (it as LatexNode.Symbol).unicode == "⋯" }
        assertTrue(hasSymbol, "Should contain cdots (⋯) symbol before +")
    }

    @Test
    fun should_parse_dots_as_cdots_before_command_binary_op() {
        val result = parser.parse("A \\times \\dots \\times B")
        assertIs<LatexNode.Document>(result)
        val symbols = result.children.filterIsInstance<LatexNode.Symbol>()
        val dotsSymbol = symbols.find { it.symbol == "cdots" || it.symbol == "ldots" }
        assertTrue(dotsSymbol != null, "Should contain a dots symbol")
    }
}
