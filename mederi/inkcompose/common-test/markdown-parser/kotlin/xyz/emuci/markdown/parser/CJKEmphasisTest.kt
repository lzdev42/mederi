package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.Emphasis
import xyz.emuci.markdown.parser.ast.Paragraph
import xyz.emuci.markdown.parser.ast.StrongEmphasis
import xyz.emuci.markdown.parser.ast.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 回归测试：CJK 与标点紧邻时的强调/粗体解析。
 *
 * 典型场景是 `**'确实'**厉害啊` 这种「标点 + CJK」边界，
 * 原 CommonMark flanking delimiter 规则会导致 closer 不被识别。
 */
class CJKEmphasisTest {
    private val parser = MarkdownParser()

    @Test
    fun should_parse_bold_with_quotes_before_cjk() {
        val doc = parser.parse("**'确实'**厉害啊")
        val para = doc.children.first()
        assertIs<Paragraph>(para)

        val strong = para.children.first { it is StrongEmphasis }
        assertIs<StrongEmphasis>(strong)
        val text = strong.children.single()
        assertIs<Text>(text)
        assertEquals("'确实'", text.literal)
    }

    @Test
    fun should_parse_bold_with_double_quotes_before_cjk() {
        val doc = parser.parse("**\"确实\"**厉害啊")
        val para = doc.children.first()
        assertIs<Paragraph>(para)

        val strong = para.children.first { it is StrongEmphasis }
        assertIs<StrongEmphasis>(strong)
        val text = strong.children.single()
        assertIs<Text>(text)
        assertEquals("\"确实\"", text.literal)
    }

    @Test
    fun should_parse_cjk_before_bold_with_quotes() {
        val doc = parser.parse("厉害啊**'确实'**")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.any { it is StrongEmphasis })
    }

    @Test
    fun should_parse_basic_cjk_bold() {
        val doc = parser.parse("这很**厉害**啊")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.any { it is StrongEmphasis })
    }

    @Test
    fun should_parse_bold_with_curly_quotes_before_cjk() {
        val doc = parser.parse("**‘确实’**厉害啊")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.any { it is StrongEmphasis })
    }

    @Test
    fun should_parse_italic_with_quotes_before_cjk() {
        val doc = parser.parse("*'确实'*厉害啊")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.any { it is Emphasis })
    }

    @Test
    fun should_parse_two_bold_segments_with_quotes() {
        val doc = parser.parse("**\"渴望\"**和**\"行动力\"**的能量")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val strongCount = para.children.count { it is StrongEmphasis }
        assertEquals(2, strongCount)
    }
}

