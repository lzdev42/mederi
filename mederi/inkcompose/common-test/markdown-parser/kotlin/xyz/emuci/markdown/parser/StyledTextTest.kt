package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StyledTextTest {

    @Test
    fun should_parse_styled_text_with_class() {
        val parser = MarkdownParser()
        val doc = parser.parse("[文本]{.red .bold}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val styled = para.children.first()
        assertIs<StyledText>(styled)
        assertEquals(listOf("red", "bold"), styled.cssClasses)
    }

    @Test
    fun should_parse_styled_text_with_style() {
        val parser = MarkdownParser()
        val doc = parser.parse("[高亮文本]{style=\"background:yellow\"}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val styled = para.children.first()
        assertIs<StyledText>(styled)
        assertEquals("background:yellow", styled.style)
    }

    @Test
    fun should_parse_styled_text_with_id() {
        val parser = MarkdownParser()
        val doc = parser.parse("[文本]{#my-text}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val styled = para.children.first()
        assertIs<StyledText>(styled)
        assertEquals("my-text", styled.cssId)
    }

    @Test
    fun should_parse_styled_text_with_mixed_attributes() {
        val parser = MarkdownParser()
        val doc = parser.parse("[文本]{.red #important style=\"font-size:20px\"}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val styled = para.children.first()
        assertIs<StyledText>(styled)
        assertEquals(listOf("red"), styled.cssClasses)
        assertEquals("important", styled.cssId)
        assertEquals("font-size:20px", styled.style)
    }

    @Test
    fun should_preserve_inner_formatting() {
        val parser = MarkdownParser()
        val doc = parser.parse("[**粗体**文本]{.red}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val styled = para.children.first()
        assertIs<StyledText>(styled)
        assertEquals(listOf("red"), styled.cssClasses)
        assertTrue(styled.children.any { it is StrongEmphasis })
    }

    @Test
    fun should_not_parse_as_styled_text_without_braces() {
        val parser = MarkdownParser()
        val doc = parser.parse("[just text]")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val first = para.children.first()
        assertIs<Text>(first)
    }

    @Test
    fun should_differentiate_link_and_styled_text() {
        val parser = MarkdownParser()
        val doc = parser.parse("[text](url){.cls}")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val link = para.children.first()
        assertIs<Link>(link)
        assertEquals(listOf("cls"), link.cssClasses)
    }
}
