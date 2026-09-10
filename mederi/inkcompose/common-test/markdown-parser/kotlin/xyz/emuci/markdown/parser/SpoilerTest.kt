package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import xyz.emuci.markdown.parser.flavour.ExtendedFlavour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpoilerTest {

    private val parser = MarkdownParser(ExtendedFlavour)

    @Test
    fun should_parse_basic_spoiler() {
        val input = "This is >!hidden text!< revealed."

        val doc = parser.parse(input)
        val para = doc.children.filterIsInstance<Paragraph>().first()
        val spoiler = para.children.filterIsInstance<Spoiler>().firstOrNull()
        assertTrue(spoiler != null, "Expected a Spoiler node")

        val text = spoiler.children.filterIsInstance<Text>().firstOrNull()
        assertTrue(text != null, "Spoiler should contain text")
        assertEquals("hidden text", text.literal)
    }

    @Test
    fun should_parse_multiple_spoilers() {
        val input = "First >!spoiler one!< and >!spoiler two!< here."

        val doc = parser.parse(input)
        val para = doc.children.filterIsInstance<Paragraph>().first()
        val spoilers = para.children.filterIsInstance<Spoiler>()
        assertEquals(2, spoilers.size)
    }

    @Test
    fun should_not_parse_incomplete_spoiler() {
        val input = "This is >!not closed spoiler."

        val doc = parser.parse(input)
        val para = doc.children.filterIsInstance<Paragraph>().first()
        val spoilers = para.children.filterIsInstance<Spoiler>()
        assertEquals(0, spoilers.size, "Should not parse incomplete spoiler")
    }

    @Test
    fun should_not_parse_mismatched_spoiler() {
        val input = "This is >!wrong close<! text."

        val doc = parser.parse(input)
        val para = doc.children.filterIsInstance<Paragraph>().first()
        val spoilers = para.children.filterIsInstance<Spoiler>()
        assertEquals(0, spoilers.size, "Should not parse mismatched spoiler")
    }

    @Test
    fun should_parse_spoiler_at_start() {
        val input = ">!surprise!< at the beginning."

        val doc = parser.parse(input)
        val para = doc.children.filterIsInstance<Paragraph>().first()
        val spoiler = para.children.filterIsInstance<Spoiler>().firstOrNull()
        assertTrue(spoiler != null, "Expected a Spoiler node at start")
    }

    @Test
    fun should_parse_spoiler_at_end() {
        val input = "Text at the end >!surprise!<"

        val doc = parser.parse(input)
        val para = doc.children.filterIsInstance<Paragraph>().first()
        val spoiler = para.children.filterIsInstance<Spoiler>().firstOrNull()
        assertTrue(spoiler != null, "Expected a Spoiler node at end")
    }

    @Test
    fun should_render_spoiler_to_html() {
        val input = "See >!secret!< here."
        val html = xyz.emuci.markdown.parser.html.HtmlRenderer.renderMarkdown(input)
        assertTrue(html.contains("spoiler"), "HTML should contain spoiler class")
        assertTrue(html.contains("secret"), "HTML should contain spoiler content")
    }

    @Test
    fun should_handle_empty_spoiler_gracefully() {
        val input = "This is >!!< text."

        val doc = parser.parse(input)
        val para = doc.children.filterIsInstance<Paragraph>().first()
        val spoiler = para.children.filterIsInstance<Spoiler>().firstOrNull()
        assertTrue(spoiler != null, "Empty spoiler should still parse")
    }
}
