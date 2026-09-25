package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RubyTextTest {

    private val parser = MarkdownParser()

    // ────── Basic Ruby Text ──────

    @Test
    fun should_parse_basic_ruby_text() {
        val doc = parser.parse("{漢字|かんじ}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val ruby = para.children.first()
        assertIs<RubyText>(ruby)
        assertEquals("漢字", ruby.base)
        assertEquals("かんじ", ruby.annotation)
        assertEquals("漢字", ruby.literal)
    }

    @Test
    fun should_parse_chinese_pinyin() {
        val doc = parser.parse("{中文|zhōngwén}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val ruby = para.children.first()
        assertIs<RubyText>(ruby)
        assertEquals("中文", ruby.base)
        assertEquals("zhōngwén", ruby.annotation)
    }

    @Test
    fun should_parse_ruby_with_single_characters() {
        val doc = parser.parse("{字|じ}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val ruby = para.children.first()
        assertIs<RubyText>(ruby)
        assertEquals("字", ruby.base)
        assertEquals("じ", ruby.annotation)
    }

    // ────── Ruby Text in Context ──────

    @Test
    fun should_parse_ruby_surrounded_by_text() {
        val doc = parser.parse("これは{漢字|かんじ}です")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val rubyNodes = para.children.filterIsInstance<RubyText>()
        assertEquals(1, rubyNodes.size)
        assertEquals("漢字", rubyNodes.first().base)
        assertEquals("かんじ", rubyNodes.first().annotation)
    }

    @Test
    fun should_parse_multiple_ruby_texts() {
        val doc = parser.parse("{漢|かん}{字|じ}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val rubyNodes = para.children.filterIsInstance<RubyText>()
        assertEquals(2, rubyNodes.size)
        assertEquals("漢", rubyNodes[0].base)
        assertEquals("かん", rubyNodes[0].annotation)
        assertEquals("字", rubyNodes[1].base)
        assertEquals("じ", rubyNodes[1].annotation)
    }

    // ────── Edge Cases ──────

    @Test
    fun should_not_parse_ruby_without_pipe() {
        val doc = parser.parse("{漢字}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.none { it is RubyText })
    }

    @Test
    fun should_not_parse_empty_base() {
        val doc = parser.parse("{|かんじ}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.none { it is RubyText })
    }

    @Test
    fun should_not_parse_empty_annotation() {
        val doc = parser.parse("{漢字|}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.none { it is RubyText })
    }

    @Test
    fun should_not_cross_line_boundary() {
        val doc = parser.parse("{漢字\n|かんじ}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.none { it is RubyText })
    }

    @Test
    fun should_not_conflict_with_directive_syntax() {
        // {% directive %} should not be parsed as ruby
        val doc = parser.parse("{% tag args %}")
        val node = doc.children.first()
        assertIs<DirectiveBlock>(node)
    }

    @Test
    fun should_fallback_unclosed_ruby_to_text() {
        val doc = parser.parse("{漢字|かんじ")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.none { it is RubyText })
        // Should contain text nodes with the original characters
        val textContent = para.children.filterIsInstance<Text>().joinToString("") { it.literal }
        assertTrue(textContent.contains("{"))
    }
}
