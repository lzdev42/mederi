package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import xyz.emuci.markdown.parser.flavour.ExtendedFlavour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CitationReferenceTest {

    private val parser = MarkdownParser(ExtendedFlavour)

    @Test
    fun should_parse_citation_reference() {
        val input = "As shown by [@smith2020], the results are significant."

        val doc = parser.parse(input)
        val para = doc.children.filterIsInstance<Paragraph>().first()
        val citation = para.children.filterIsInstance<CitationReference>().firstOrNull()
        assertTrue(citation != null, "Expected a CitationReference node")
        assertEquals("smith2020", citation.key)
    }

    @Test
    fun should_parse_multiple_citations() {
        val input = "See [@smith2020] and [@jones2021] for details."

        val doc = parser.parse(input)
        val para = doc.children.filterIsInstance<Paragraph>().first()
        val citations = para.children.filterIsInstance<CitationReference>()
        assertEquals(2, citations.size)
        assertEquals("smith2020", citations[0].key)
        assertEquals("jones2021", citations[1].key)
    }

    @Test
    fun should_parse_bibliography_definition() {
        val input = """
            |Some text with a citation [@smith2020].
            |
            |[^bibliography]: smith2020: Smith, J. "A Great Paper", 2020
        """.trimMargin()

        val doc = parser.parse(input)
        val bibDef = doc.children.filterIsInstance<BibliographyDefinition>().firstOrNull()
        assertTrue(bibDef != null, "Expected a BibliographyDefinition node")
        assertTrue(bibDef.entries.containsKey("smith2020"))
        assertEquals("Smith, J. \"A Great Paper\", 2020", bibDef.entries["smith2020"]?.content)
    }

    @Test
    fun should_parse_bibliography_with_multiple_entries() {
        val input = """
            |[@smith2020] and [@jones2021]
            |
            |[^bibliography]: smith2020: Smith, "Paper A", 2020
            |jones2021: Jones, "Paper B", 2021
        """.trimMargin()

        val doc = parser.parse(input)
        val bibDef = doc.children.filterIsInstance<BibliographyDefinition>().firstOrNull()
        assertTrue(bibDef != null, "Expected a BibliographyDefinition node")
        assertEquals(2, bibDef.entries.size)
    }

    @Test
    fun should_not_parse_citation_without_at() {
        val input = "This is [not a citation]."

        val doc = parser.parse(input)
        val para = doc.children.filterIsInstance<Paragraph>().first()
        val citations = para.children.filterIsInstance<CitationReference>()
        assertEquals(0, citations.size, "Should not parse as citation without @")
    }

    @Test
    fun should_not_parse_citation_with_spaces_in_key() {
        val input = "This is [@has space]."

        val doc = parser.parse(input)
        val para = doc.children.filterIsInstance<Paragraph>().first()
        val citations = para.children.filterIsInstance<CitationReference>()
        assertEquals(0, citations.size, "Should not parse citation with spaces in key")
    }

    @Test
    fun should_render_citation_to_html() {
        val input = "See [@smith2020]."
        val html = xyz.emuci.markdown.parser.html.HtmlRenderer.renderMarkdown(input)
        assertTrue(html.contains("citation-ref"), "HTML should contain citation-ref class")
        assertTrue(html.contains("bib-smith2020"), "HTML should contain bib-smith2020 href")
    }
}
