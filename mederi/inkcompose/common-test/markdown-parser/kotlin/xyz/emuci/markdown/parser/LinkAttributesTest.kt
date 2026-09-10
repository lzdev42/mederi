package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinkAttributesTest {

    @Test
    fun should_parse_link_with_rel_attribute() {
        val parser = MarkdownParser()
        val doc = parser.parse("[链接](https://example.com){rel=\"nofollow\"}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Link>(link)
        assertEquals("https://example.com", link.destination)
        assertEquals("nofollow", link.attributes["rel"])
    }

    @Test
    fun should_parse_link_with_target_blank() {
        val parser = MarkdownParser()
        val doc = parser.parse("[链接](https://example.com){target=\"_blank\"}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Link>(link)
        assertEquals("_blank", link.attributes["target"])
    }

    @Test
    fun should_parse_link_with_download_attribute() {
        val parser = MarkdownParser()
        val doc = parser.parse("[下载](https://example.com/file.pdf){download=\"文件.pdf\"}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Link>(link)
        assertEquals("文件.pdf", link.attributes["download"])
    }

    @Test
    fun should_parse_link_with_multiple_attributes() {
        val parser = MarkdownParser()
        val doc = parser.parse("[链接](https://example.com){rel=\"nofollow\" target=\"_blank\" download=\"文件.pdf\"}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Link>(link)
        assertEquals("nofollow", link.attributes["rel"])
        assertEquals("_blank", link.attributes["target"])
        assertEquals("文件.pdf", link.attributes["download"])
    }

    @Test
    fun should_parse_link_with_css_class_and_id() {
        val parser = MarkdownParser()
        val doc = parser.parse("[链接](https://example.com){.btn .primary #main-link}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Link>(link)
        assertEquals(listOf("btn", "primary"), link.cssClasses)
        assertEquals("main-link", link.cssId)
    }

    @Test
    fun should_parse_link_without_attributes() {
        val parser = MarkdownParser()
        val doc = parser.parse("[链接](https://example.com)")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Link>(link)
        assertTrue(link.attributes.isEmpty())
    }

    @Test
    fun should_parse_link_with_title_and_attributes() {
        val parser = MarkdownParser()
        val doc = parser.parse("[链接](https://example.com \"标题\"){rel=\"noopener\"}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Link>(link)
        assertEquals("标题", link.title)
        assertEquals("noopener", link.attributes["rel"])
    }
}
