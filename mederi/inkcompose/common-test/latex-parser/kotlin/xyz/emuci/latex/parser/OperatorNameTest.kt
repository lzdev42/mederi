package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.parser.visitor.AccessibilityVisitor
import xyz.emuci.latex.parser.visitor.MathMLVisitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * \operatorname 自定义运算符测试
 */
class OperatorNameTest {

    private val parser = LatexParser()

    @Test
    fun should_parse_operatorname_standalone() {
        val result = parser.parse("\\operatorname{Tr}")
        assertIs<LatexNode.Document>(result)
        val opName = result.children[0]
        assertIs<LatexNode.OperatorName>(opName)
        assertEquals("Tr", opName.name)
    }

    @Test
    fun should_parse_operatorname_with_subscript() {
        val result = parser.parse("\\operatorname{Tr}_A")
        assertIs<LatexNode.Document>(result)
        val bigOp = result.children[0]
        assertIs<LatexNode.BigOperator>(bigOp)
        assertEquals("Tr", bigOp.operator)
        assertEquals(false, bigOp.limitsInDisplay)
    }

    @Test
    fun should_parse_starred_operatorname_with_display_limits() {
        val result = parser.parse("\\operatorname*{argmax}_{x}")
        val bigOp = result.children[0]
        assertIs<LatexNode.BigOperator>(bigOp)
        assertEquals("argmax", bigOp.operator)
        assertEquals(true, bigOp.limitsInDisplay)
    }

    @Test
    fun should_distinguish_builtin_limit_and_nonlimit_operators() {
        val det = parser.parse("\\det_{A}").children[0]
        val arg = parser.parse("\\arg_{A}").children[0]
        assertIs<LatexNode.BigOperator>(det)
        assertIs<LatexNode.BigOperator>(arg)
        assertEquals(true, det.limitsInDisplay)
        assertEquals(false, arg.limitsInDisplay)
    }

    @Test
    fun should_parse_operatorname_with_limits() {
        val result = parser.parse("\\operatorname{argmax}\\limits_{x}")
        assertIs<LatexNode.Document>(result)
        val bigOp = result.children[0]
        assertIs<LatexNode.BigOperator>(bigOp)
        assertEquals("argmax", bigOp.operator)
        assertEquals(LatexNode.BigOperator.LimitsMode.LIMITS, bigOp.limitsMode)
    }

    @Test
    fun should_parse_operatorname_empty_gracefully() {
        val result = parser.parse("\\operatorname{}")
        assertIs<LatexNode.Document>(result)
        assertTrue(result.children.isNotEmpty())
    }

    @Test
    fun should_produce_accessibility_for_operatorname() {
        val result = parser.parse("\\operatorname{Tr}")
        val desc = AccessibilityVisitor.describe(result)
        assertTrue(desc.contains("Tr"))
    }

    @Test
    fun should_produce_mathml_for_operatorname() {
        val result = parser.parse("\\operatorname{Tr}")
        val mathml = MathMLVisitor.convert(result)
        assertTrue(mathml.contains("<mo>Tr</mo>"))
    }
}
