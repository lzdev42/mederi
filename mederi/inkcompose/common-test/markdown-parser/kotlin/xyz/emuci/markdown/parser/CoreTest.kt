package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SourceTextTest {

    @Test
    fun should_normalize_crlf() {
        val source = xyz.emuci.markdown.parser.core.SourceText.of("line1\r\nline2\r\nline3")
        assertEquals(3, source.lineCount)
        assertEquals("line1", source.lineContent(0))
        assertEquals("line2", source.lineContent(1))
        assertEquals("line3", source.lineContent(2))
    }

    @Test
    fun should_normalize_cr() {
        val source = xyz.emuci.markdown.parser.core.SourceText.of("line1\rline2")
        assertEquals(2, source.lineCount)
        assertEquals("line1", source.lineContent(0))
        assertEquals("line2", source.lineContent(1))
    }

    @Test
    fun should_replace_null_char() {
        val source = xyz.emuci.markdown.parser.core.SourceText.of("hello\u0000world")
        assertTrue(source.content.contains('\uFFFD'))
    }

    @Test
    fun should_find_line_at_offset() {
        val source = xyz.emuci.markdown.parser.core.SourceText.of("abc\ndef\nghi")
        assertEquals(0, source.lineAtOffset(0))
        assertEquals(0, source.lineAtOffset(2))
        assertEquals(1, source.lineAtOffset(4))
        assertEquals(2, source.lineAtOffset(8))
    }

    @Test
    fun should_handle_empty_input() {
        val source = xyz.emuci.markdown.parser.core.SourceText.of("")
        assertEquals(1, source.lineCount)
        assertEquals("", source.lineContent(0))
    }

    @Test
    fun should_handle_trailing_newline() {
        val source = xyz.emuci.markdown.parser.core.SourceText.of("hello\n")
        assertEquals(2, source.lineCount)
        assertEquals("hello", source.lineContent(0))
        assertEquals("", source.lineContent(1))
    }
}

class CharacterUtilsTest {

    @Test
    fun should_identify_ascii_punctuation() {
        assertTrue(xyz.emuci.markdown.parser.core.CharacterUtils.isAsciiPunctuation('!'))
        assertTrue(xyz.emuci.markdown.parser.core.CharacterUtils.isAsciiPunctuation('*'))
        assertTrue(xyz.emuci.markdown.parser.core.CharacterUtils.isAsciiPunctuation('['))
        assertTrue(!xyz.emuci.markdown.parser.core.CharacterUtils.isAsciiPunctuation('a'))
    }

    @Test
    fun should_identify_unicode_whitespace() {
        assertTrue(xyz.emuci.markdown.parser.core.CharacterUtils.isUnicodeWhitespace(' '))
        assertTrue(xyz.emuci.markdown.parser.core.CharacterUtils.isUnicodeWhitespace('\t'))
        assertTrue(xyz.emuci.markdown.parser.core.CharacterUtils.isUnicodeWhitespace('\n'))
        assertTrue(!xyz.emuci.markdown.parser.core.CharacterUtils.isUnicodeWhitespace('a'))
    }

    @Test
    fun should_count_leading_spaces() {
        assertEquals(0, xyz.emuci.markdown.parser.core.CharacterUtils.countLeadingSpaces("hello"))
        assertEquals(4, xyz.emuci.markdown.parser.core.CharacterUtils.countLeadingSpaces("    hello"))
        assertEquals(4, xyz.emuci.markdown.parser.core.CharacterUtils.countLeadingSpaces("\thello"))
    }

    @Test
    fun should_normalize_link_label() {
        assertEquals("foo bar", xyz.emuci.markdown.parser.core.CharacterUtils.normalizeLinkLabel("  FOO  BAR  "))
        assertEquals("hello", xyz.emuci.markdown.parser.core.CharacterUtils.normalizeLinkLabel("Hello"))
    }

    @Test
    fun should_identify_blank_lines() {
        assertTrue(xyz.emuci.markdown.parser.core.CharacterUtils.isBlank(""))
        assertTrue(xyz.emuci.markdown.parser.core.CharacterUtils.isBlank("   "))
        assertTrue(xyz.emuci.markdown.parser.core.CharacterUtils.isBlank("\t\t"))
        assertTrue(!xyz.emuci.markdown.parser.core.CharacterUtils.isBlank("  a  "))
    }
}

class HtmlEntitiesTest {

    @Test
    fun should_resolve_named_entities() {
        assertEquals("&", xyz.emuci.markdown.parser.core.HtmlEntities.resolve("&amp;"))
        assertEquals("<", xyz.emuci.markdown.parser.core.HtmlEntities.resolve("&lt;"))
        assertEquals(">", xyz.emuci.markdown.parser.core.HtmlEntities.resolve("&gt;"))
        assertEquals("\"", xyz.emuci.markdown.parser.core.HtmlEntities.resolve("&quot;"))
        assertEquals("\u00A0", xyz.emuci.markdown.parser.core.HtmlEntities.resolve("&nbsp;"))
    }

    @Test
    fun should_resolve_decimal_entity() {
        assertEquals("{", xyz.emuci.markdown.parser.core.HtmlEntities.resolve("&#123;"))
        assertEquals("A", xyz.emuci.markdown.parser.core.HtmlEntities.resolve("&#65;"))
    }

    @Test
    fun should_resolve_hex_entity() {
        assertEquals("\u00A9", xyz.emuci.markdown.parser.core.HtmlEntities.resolve("&#xA9;"))
    }

    @Test
    fun should_return_null_for_invalid_entity() {
        assertEquals(null, xyz.emuci.markdown.parser.core.HtmlEntities.resolve("&invalid;"))
    }

    @Test
    fun should_replace_null_codepoint() {
        assertEquals("\uFFFD", xyz.emuci.markdown.parser.core.HtmlEntities.resolve("&#0;"))
    }
}
