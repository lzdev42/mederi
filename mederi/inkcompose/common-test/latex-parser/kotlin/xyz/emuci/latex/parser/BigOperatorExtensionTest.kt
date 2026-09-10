package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * BigOperator 补全测试：
 * \coprod, \bigoplus, \bigotimes, \bigsqcup, \bigodot, \biguplus
 */
class BigOperatorExtensionTest {

    private val parser = LatexParser()

    @Test
    fun should_parse_coprod() {
        val result = parser.parse("\\coprod_{i=1}^{n}")
        assertIs<LatexNode.Document>(result)
        val bigOp = result.children[0]
        assertIs<LatexNode.BigOperator>(bigOp)
        assertEquals("coprod", bigOp.operator)
    }

    @Test
    fun should_parse_bigoplus() {
        val result = parser.parse("\\bigoplus_{k}")
        assertIs<LatexNode.Document>(result)
        val bigOp = result.children[0]
        assertIs<LatexNode.BigOperator>(bigOp)
        assertEquals("bigoplus", bigOp.operator)
    }

    @Test
    fun should_parse_bigotimes() {
        val result = parser.parse("\\bigotimes_{i=1}^{n}")
        assertIs<LatexNode.Document>(result)
        val bigOp = result.children[0]
        assertIs<LatexNode.BigOperator>(bigOp)
        assertEquals("bigotimes", bigOp.operator)
    }

    @Test
    fun should_parse_bigsqcup() {
        val result = parser.parse("\\bigsqcup_{j}")
        assertIs<LatexNode.Document>(result)
        val bigOp = result.children[0]
        assertIs<LatexNode.BigOperator>(bigOp)
        assertEquals("bigsqcup", bigOp.operator)
    }

    @Test
    fun should_parse_bigodot() {
        val result = parser.parse("\\bigodot")
        assertIs<LatexNode.Document>(result)
        val bigOp = result.children[0]
        assertIs<LatexNode.BigOperator>(bigOp)
        assertEquals("bigodot", bigOp.operator)
    }

    @Test
    fun should_parse_biguplus() {
        val result = parser.parse("\\biguplus_{i}")
        assertIs<LatexNode.Document>(result)
        val bigOp = result.children[0]
        assertIs<LatexNode.BigOperator>(bigOp)
        assertEquals("biguplus", bigOp.operator)
    }
}
