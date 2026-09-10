package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * \underbrace{...}_{text} 和 \overbrace{...}^{text} 花括号标注测试
 */
class BraceAnnotationTest {

    private val parser = LatexParser()

    @Test
    fun should_parse_underbrace_with_subscript() {
        val result = parser.parse("\\underbrace{x+y}_{n}")
        assertIs<LatexNode.Document>(result)
        val sub = result.children[0]
        assertIs<LatexNode.Subscript>(sub)
        val accent = sub.base
        assertIs<LatexNode.Accent>(accent)
        assertEquals(LatexNode.Accent.AccentType.UNDERBRACE, accent.accentType)
    }

    @Test
    fun should_parse_overbrace_with_superscript() {
        val result = parser.parse("\\overbrace{a+b+c}^{3}")
        assertIs<LatexNode.Document>(result)
        val sup = result.children[0]
        assertIs<LatexNode.Superscript>(sup)
        val accent = sup.base
        assertIs<LatexNode.Accent>(accent)
        assertEquals(LatexNode.Accent.AccentType.OVERBRACE, accent.accentType)
    }
}
