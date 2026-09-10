package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WikiLinkTest {

    private val parser = MarkdownParser()

    // ────── Basic Wiki Links ──────

    @Test
    fun should_parse_simple_wiki_link() {
        val doc = parser.parse("[[page]]")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val wiki = para.children.first()
        assertIs<WikiLink>(wiki)
        assertEquals("page", wiki.target)
        assertNull(wiki.label)
        assertEquals("page", wiki.literal)
    }

    @Test
    fun should_parse_wiki_link_with_label() {
        val doc = parser.parse("[[page|显示文本]]")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val wiki = para.children.first()
        assertIs<WikiLink>(wiki)
        assertEquals("page", wiki.target)
        assertEquals("显示文本", wiki.label)
        assertEquals("显示文本", wiki.literal)
    }

    @Test
    fun should_parse_wiki_link_with_spaces_in_target() {
        val doc = parser.parse("[[my page name]]")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val wiki = para.children.first()
        assertIs<WikiLink>(wiki)
        assertEquals("my page name", wiki.target)
        assertNull(wiki.label)
    }

    @Test
    fun should_parse_wiki_link_with_cjk_characters() {
        val doc = parser.parse("[[笔记页面|我的笔记]]")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val wiki = para.children.first()
        assertIs<WikiLink>(wiki)
        assertEquals("笔记页面", wiki.target)
        assertEquals("我的笔记", wiki.label)
    }

    // ────── Wiki Links in Context ──────

    @Test
    fun should_parse_wiki_link_surrounded_by_text() {
        val doc = parser.parse("前面文本 [[page]] 后面文本")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val children = para.children
        assertTrue(children.size >= 3)
        val wiki = children.filterIsInstance<WikiLink>().first()
        assertEquals("page", wiki.target)
    }

    @Test
    fun should_parse_multiple_wiki_links() {
        val doc = parser.parse("[[page1]] 和 [[page2|标签]]")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val wikiLinks = para.children.filterIsInstance<WikiLink>()
        assertEquals(2, wikiLinks.size)
        assertEquals("page1", wikiLinks[0].target)
        assertNull(wikiLinks[0].label)
        assertEquals("page2", wikiLinks[1].target)
        assertEquals("标签", wikiLinks[1].label)
    }

    // ────── Edge Cases ──────

    @Test
    fun should_not_parse_empty_wiki_link() {
        val doc = parser.parse("[[]]")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.none { it is WikiLink })
    }

    @Test
    fun should_not_parse_unclosed_wiki_link() {
        val doc = parser.parse("[[page")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.none { it is WikiLink })
    }

    @Test
    fun should_not_parse_single_bracket_as_wiki_link() {
        val doc = parser.parse("[page]")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.none { it is WikiLink })
    }

    @Test
    fun should_handle_wiki_link_with_empty_label() {
        val doc = parser.parse("[[page|]]")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        // Either parsed with empty label or not parsed at all — both are valid graceful handling
        val wikiLinks = para.children.filterIsInstance<WikiLink>()
        if (wikiLinks.isNotEmpty()) {
            assertEquals("page", wikiLinks.first().target)
        }
    }

    @Test
    fun should_not_cross_line_boundary() {
        val doc = parser.parse("[[page\nname]]")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.none { it is WikiLink })
    }
}
