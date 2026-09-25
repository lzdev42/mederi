package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * \smash、\vphantom、\hphantom 间距微调命令测试
 */
class SpacingAdjustmentTest {

    private val parser = LatexParser()

    @Test
    fun should_parse_smash() {
        val result = parser.parse("\\smash{\\frac{a}{b}}")
        assertIs<LatexNode.Document>(result)
        val smash = result.children[0]
        assertIs<LatexNode.Smash>(smash)
        assertTrue(smash.content.isNotEmpty())
    }

    @Test
    fun should_parse_vphantom() {
        val result = parser.parse("\\vphantom{\\frac{a}{b}}")
        assertIs<LatexNode.Document>(result)
        val vphantom = result.children[0]
        assertIs<LatexNode.VPhantom>(vphantom)
        assertTrue(vphantom.content.isNotEmpty())
    }

    @Test
    fun should_parse_hphantom() {
        val result = parser.parse("\\hphantom{xyz}")
        assertIs<LatexNode.Document>(result)
        val hphantom = result.children[0]
        assertIs<LatexNode.HPhantom>(hphantom)
        assertTrue(hphantom.content.isNotEmpty())
    }

    @Test
    fun should_parse_smash_in_expression() {
        val result = parser.parse("x + \\smash{\\frac{a}{b}} + y")
        assertIs<LatexNode.Document>(result)
        val hasSmash = result.children.any { it is LatexNode.Smash }
        assertTrue(hasSmash, "Should contain smash node")
    }
}
