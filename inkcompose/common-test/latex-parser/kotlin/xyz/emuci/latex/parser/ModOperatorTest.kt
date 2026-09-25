package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.parser.visitor.AccessibilityVisitor
import xyz.emuci.latex.parser.visitor.MathMLVisitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 取模运算符测试：\bmod, \pmod, \mod
 */
class ModOperatorTest {

    private val parser = LatexParser()

    @Test
    fun should_parse_bmod() {
        val result = parser.parse("a \\bmod b")
        assertIs<LatexNode.Document>(result)
        val mod = result.children.find { it is LatexNode.ModOperator }
        assertIs<LatexNode.ModOperator>(mod)
        assertEquals(LatexNode.ModOperator.ModStyle.BMOD, mod.modStyle)
    }

    @Test
    fun should_parse_pmod() {
        val result = parser.parse("a \\pmod{n}")
        assertIs<LatexNode.Document>(result)
        val mod = result.children.find { it is LatexNode.ModOperator }
        assertIs<LatexNode.ModOperator>(mod)
        assertEquals(LatexNode.ModOperator.ModStyle.PMOD, mod.modStyle)
        assertTrue(mod.content != null, "pmod should have content argument")
    }

    @Test
    fun should_parse_mod() {
        val result = parser.parse("a \\mod b")
        assertIs<LatexNode.Document>(result)
        val mod = result.children.find { it is LatexNode.ModOperator }
        assertIs<LatexNode.ModOperator>(mod)
        assertEquals(LatexNode.ModOperator.ModStyle.MOD, mod.modStyle)
    }

    @Test
    fun should_produce_accessibility_for_bmod() {
        val result = parser.parse("a \\bmod b")
        val desc = AccessibilityVisitor.describe(result)
        assertTrue(desc.contains("mod"), "Accessibility should contain 'mod', got: $desc")
    }

    @Test
    fun should_produce_accessibility_for_pmod() {
        val result = parser.parse("a \\pmod{n}")
        val desc = AccessibilityVisitor.describe(result)
        assertTrue(desc.contains("mod"), "Accessibility should contain 'mod', got: $desc")
    }

    @Test
    fun should_produce_mathml_for_bmod() {
        val result = parser.parse("a \\bmod b")
        val mathml = MathMLVisitor.convert(result)
        assertTrue(mathml.contains("<mo>mod</mo>"), "MathML should contain <mo>mod</mo>, got: $mathml")
    }

    @Test
    fun should_produce_mathml_for_pmod() {
        val result = parser.parse("a \\pmod{n}")
        val mathml = MathMLVisitor.convert(result)
        assertTrue(mathml.contains("<mo>mod</mo>"), "MathML should contain <mo>mod</mo>, got: $mathml")
    }
}
