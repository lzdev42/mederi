package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * \bcancel 和 \xcancel 取消线变体测试
 */
class CancelVariantsTest {

    private val parser = LatexParser()

    @Test
    fun should_parse_bcancel() {
        val result = parser.parse("\\bcancel{x}")
        assertIs<LatexNode.Document>(result)
        val accent = result.children[0]
        assertIs<LatexNode.Accent>(accent)
        assertEquals(LatexNode.Accent.AccentType.BCANCEL, accent.accentType)
    }

    @Test
    fun should_parse_xcancel() {
        val result = parser.parse("\\xcancel{abc}")
        assertIs<LatexNode.Document>(result)
        val accent = result.children[0]
        assertIs<LatexNode.Accent>(accent)
        assertEquals(LatexNode.Accent.AccentType.XCANCEL, accent.accentType)
    }

    @Test
    fun should_parse_cancel_variants_in_expression() {
        val result = parser.parse("\\cancel{a} + \\bcancel{b} + \\xcancel{c}")
        assertIs<LatexNode.Document>(result)
        val accents = result.children.filterIsInstance<LatexNode.Accent>()
        assertEquals(3, accents.size)
        assertEquals(LatexNode.Accent.AccentType.CANCEL, accents[0].accentType)
        assertEquals(LatexNode.Accent.AccentType.BCANCEL, accents[1].accentType)
        assertEquals(LatexNode.Accent.AccentType.XCANCEL, accents[2].accentType)
    }
}
