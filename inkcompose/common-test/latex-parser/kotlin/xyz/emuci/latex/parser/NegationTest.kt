package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * \not 否定修饰符测试
 */
class NegationTest {

    private val parser = LatexParser()

    @Test
    fun should_parse_not_equals() {
        val result = parser.parse("\\not=")
        assertIs<LatexNode.Document>(result)
        val neg = result.children[0]
        assertIs<LatexNode.Negation>(neg)
    }

    @Test
    fun should_parse_not_in() {
        val result = parser.parse("\\not\\in")
        assertIs<LatexNode.Document>(result)
        val neg = result.children[0]
        assertIs<LatexNode.Negation>(neg)
        assertIs<LatexNode.Symbol>(neg.content)
    }

    @Test
    fun should_parse_not_subset() {
        val result = parser.parse("\\not\\subset")
        assertIs<LatexNode.Document>(result)
        val neg = result.children[0]
        assertIs<LatexNode.Negation>(neg)
    }
}
