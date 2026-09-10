package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import xyz.emuci.markdown.parser.block.starters.*
import xyz.emuci.markdown.parser.block.postprocessors.*
import xyz.emuci.markdown.parser.flavour.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * tests verifying each flavour enables the correct set of features.
 */
class FlavourCompatibilityTest {

    // ---- CommonMarkFlavour feature verification ----

    @Test
    fun commonmark_should_have_core_starters_only() {
        val starters = CommonMarkFlavour.blockStarters
        assertTrue(starters.any { it is HeadingStarter })
        assertTrue(starters.any { it is SetextHeadingStarter })
        assertTrue(starters.any { it is ThematicBreakStarter })
        assertTrue(starters.any { it is FencedCodeBlockStarter })
        assertTrue(starters.any { it is IndentedCodeBlockStarter })
        assertTrue(starters.any { it is BlockQuoteStarter })
        assertTrue(starters.any { it is ListItemStarter })
        assertTrue(starters.any { it is HtmlBlockStarter })

        // should NOT have extended starters
        assertFalse(starters.any { it is TableStarter })
        assertFalse(starters.any { it is CustomContainerStarter })
        assertFalse(starters.any { it is MathBlockStarter })
        assertFalse(starters.any { it is FootnoteDefinitionStarter })
        assertFalse(starters.any { it is DefinitionDescriptionStarter })
        assertFalse(starters.any { it is FrontMatterStarter })
    }

    @Test
    fun commonmark_should_have_no_post_processors() {
        assertTrue(CommonMarkFlavour.postProcessors.isEmpty())
    }

    @Test
    fun commonmark_should_disable_gfm_autolinks() {
        assertFalse(CommonMarkFlavour.enableGfmAutolinks)
    }

    @Test
    fun commonmark_should_disable_extended_inline() {
        assertFalse(CommonMarkFlavour.enableExtendedInline)
    }

    @Test
    fun commonmark_should_not_parse_tables() {
        val parser = MarkdownParser(CommonMarkFlavour)
        val doc = parser.parse("| A | B |\n| - | - |\n| 1 | 2 |")
        val tables = doc.children.filterIsInstance<Table>()
        assertEquals(0, tables.size, "CommonMark should not parse tables")
    }

    // ---- GFMFlavour feature verification ----

    @Test
    fun gfm_should_have_table_starter() {
        assertTrue(GFMFlavour.blockStarters.any { it is TableStarter })
    }

    @Test
    fun gfm_should_have_indented_code_block() {
        // GFM 0.29 retains indented code blocks (consistent with CommonMark)
        assertTrue(GFMFlavour.blockStarters.any { it is IndentedCodeBlockStarter })
    }

    @Test
    fun gfm_should_not_have_custom_container() {
        assertFalse(GFMFlavour.blockStarters.any { it is CustomContainerStarter })
    }

    @Test
    fun gfm_should_not_have_math_block() {
        assertFalse(GFMFlavour.blockStarters.any { it is MathBlockStarter })
    }

    @Test
    fun gfm_should_parse_tables() {
        val parser = MarkdownParser(GFMFlavour)
        val doc = parser.parse("| A | B |\n| - | - |\n| 1 | 2 |")
        val tables = doc.children.filterIsInstance<Table>()
        assertEquals(1, tables.size, "GFM should parse tables")
    }

    @Test
    fun gfm_should_parse_strikethrough() {
        val parser = MarkdownParser(GFMFlavour)
        val doc = parser.parse("~~deleted~~")
        val para = doc.children.filterIsInstance<Paragraph>().first()
        assertTrue(para.children.any { it is Strikethrough }, "GFM should parse strikethrough")
    }

    // ---- ExtendedFlavour feature verification ----

    @Test
    fun extended_should_have_all_starters() {
        val starters = ExtendedFlavour.blockStarters
        assertTrue(starters.any { it is FrontMatterStarter })
        assertTrue(starters.any { it is TableStarter })
        assertTrue(starters.any { it is CustomContainerStarter })
        assertTrue(starters.any { it is MathBlockStarter })
        assertTrue(starters.any { it is FootnoteDefinitionStarter })
        assertTrue(starters.any { it is DefinitionDescriptionStarter })
        assertTrue(starters.any { it is PageBreakStarter })
        assertTrue(starters.any { it is DirectiveBlockStarter })
    }

    @Test
    fun extended_should_have_post_processors() {
        val processors = ExtendedFlavour.postProcessors
        assertTrue(processors.any { it is HeadingIdProcessor })
        assertTrue(processors.any { it is BlockAttributeProcessor })
        assertTrue(processors.any { it is AbbreviationProcessor })
        assertTrue(processors.any { it is DiagramProcessor })
        assertTrue(processors.any { it is ColumnsLayoutProcessor })
        assertTrue(processors.any { it is HtmlFilterProcessor })
    }

    @Test
    fun extended_should_enable_gfm_autolinks() {
        assertTrue(ExtendedFlavour.enableGfmAutolinks)
    }

    @Test
    fun extended_should_enable_extended_inline() {
        assertTrue(ExtendedFlavour.enableExtendedInline)
    }

    @Test
    fun extended_should_parse_custom_containers() {
        val parser = MarkdownParser(ExtendedFlavour)
        val doc = parser.parse("::: warning\ncontent\n:::")
        assertTrue(doc.children.any { it is CustomContainer })
    }

    @Test
    fun extended_should_parse_math_blocks() {
        val parser = MarkdownParser(ExtendedFlavour)
        val doc = parser.parse("\$\$\ny = mx + b\n\$\$")
        assertTrue(doc.children.any { it is MathBlock })
    }

    // ---- MarkdownExtraFlavour feature verification ----

    @Test
    fun extra_should_have_tables() {
        assertTrue(MarkdownExtraFlavour.blockStarters.any { it is TableStarter })
    }

    @Test
    fun extra_should_have_footnotes() {
        assertTrue(MarkdownExtraFlavour.blockStarters.any { it is FootnoteDefinitionStarter })
    }

    @Test
    fun extra_should_have_definition_lists() {
        assertTrue(MarkdownExtraFlavour.blockStarters.any { it is DefinitionDescriptionStarter })
    }

    @Test
    fun extra_should_have_fenced_code() {
        assertTrue(MarkdownExtraFlavour.blockStarters.any { it is FencedCodeBlockStarter })
    }

    @Test
    fun extra_should_have_abbreviation_processor() {
        assertTrue(MarkdownExtraFlavour.postProcessors.any { it is AbbreviationProcessor })
    }

    @Test
    fun extra_should_not_have_custom_containers() {
        assertFalse(MarkdownExtraFlavour.blockStarters.any { it is CustomContainerStarter })
    }

    @Test
    fun extra_should_not_have_math_block() {
        assertFalse(MarkdownExtraFlavour.blockStarters.any { it is MathBlockStarter })
    }

    @Test
    fun extra_should_not_have_directives() {
        assertFalse(MarkdownExtraFlavour.blockStarters.any { it is DirectiveBlockStarter })
    }

    @Test
    fun extra_should_not_have_front_matter() {
        assertFalse(MarkdownExtraFlavour.blockStarters.any { it is FrontMatterStarter })
    }

    @Test
    fun extra_should_not_have_page_break() {
        assertFalse(MarkdownExtraFlavour.blockStarters.any { it is PageBreakStarter })
    }

    @Test
    fun extra_should_disable_gfm_autolinks() {
        assertFalse(MarkdownExtraFlavour.enableGfmAutolinks)
    }

    @Test
    fun extra_should_disable_extended_inline() {
        assertFalse(MarkdownExtraFlavour.enableExtendedInline)
    }

    @Test
    fun extra_should_parse_tables() {
        val parser = MarkdownParser(MarkdownExtraFlavour)
        val doc = parser.parse("| A | B |\n| - | - |\n| 1 | 2 |")
        assertTrue(doc.children.any { it is Table })
    }

    @Test
    fun extra_should_parse_definition_lists() {
        val parser = MarkdownParser(MarkdownExtraFlavour)
        val doc = parser.parse("Term\n: Definition")
        assertTrue(doc.children.any { it is DefinitionList })
    }

    @Test
    fun extra_should_not_parse_custom_containers() {
        val parser = MarkdownParser(MarkdownExtraFlavour)
        val doc = parser.parse("::: warning\ncontent\n:::")
        assertFalse(doc.children.any { it is CustomContainer })
    }

    @Test
    fun extra_should_not_parse_math_blocks() {
        val parser = MarkdownParser(MarkdownExtraFlavour)
        val doc = parser.parse("\$\$\ny = mx + b\n\$\$")
        assertFalse(doc.children.any { it is MathBlock })
    }

    @Test
    fun extra_should_not_parse_emoji() {
        val parser = MarkdownParser(MarkdownExtraFlavour)
        val doc = parser.parse(":smile:")
        val para = doc.children.filterIsInstance<Paragraph>().firstOrNull()
        assertTrue(para != null)
        // emoji should not be parsed, text should remain as-is
        assertFalse(para.children.any { it is Emoji })
    }

    // ---- FlavourCache singleton registration ----

    @Test
    fun extra_flavour_should_be_cached_as_singleton() {
        val cache1 = FlavourCache.of(MarkdownExtraFlavour)
        val cache2 = FlavourCache.of(MarkdownExtraFlavour)
        assertTrue(cache1 === cache2, "MarkdownExtraFlavour should be cached as singleton")
    }
}
