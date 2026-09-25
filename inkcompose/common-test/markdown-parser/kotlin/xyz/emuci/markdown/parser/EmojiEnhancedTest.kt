package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmojiEnhancedTest {

    @Test
    fun should_map_standard_emoji_to_unicode() {
        val parser = MarkdownParser()
        val doc = parser.parse(":smile:")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val emoji = para.children.first()
        assertIs<Emoji>(emoji)
        assertEquals("smile", emoji.shortcode)
        assertEquals("😄", emoji.literal)
        assertEquals("😄", emoji.unicode)
    }

    @Test
    fun should_map_heart_emoji() {
        val parser = MarkdownParser()
        val doc = parser.parse(":heart:")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val emoji = para.children.first()
        assertIs<Emoji>(emoji)
        assertEquals("❤️", emoji.literal)
    }

    @Test
    fun should_map_rocket_emoji() {
        val parser = MarkdownParser()
        val doc = parser.parse(":rocket:")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val emoji = para.children.first()
        assertIs<Emoji>(emoji)
        assertEquals("🚀", emoji.literal)
    }

    @Test
    fun should_keep_literal_for_unknown_shortcode() {
        val parser = MarkdownParser()
        val doc = parser.parse(":unknown_emoji:")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val emoji = para.children.first()
        assertIs<Emoji>(emoji)
        assertEquals("unknown_emoji", emoji.shortcode)
        assertEquals(":unknown_emoji:", emoji.literal)
        assertNull(emoji.unicode)
    }

    @Test
    fun should_support_custom_emoji_mapping() {
        val parser = MarkdownParser(
            customEmojiMap = mapOf("my-emoji" to "🦄")
        )
        val doc = parser.parse(":my-emoji:")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val emoji = para.children.first()
        assertIs<Emoji>(emoji)
        assertEquals("my-emoji", emoji.shortcode)
        assertEquals("🦄", emoji.literal)
        assertEquals("🦄", emoji.unicode)
    }

    @Test
    fun should_prioritize_custom_over_standard() {
        val parser = MarkdownParser(
            customEmojiMap = mapOf("smile" to "🌟")
        )
        val doc = parser.parse(":smile:")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val emoji = para.children.first()
        assertIs<Emoji>(emoji)
        assertEquals("🌟", emoji.literal)
    }

    @Test
    fun should_parse_ascii_emoticon_smiley() {
        val parser = MarkdownParser(enableAsciiEmoticons = true)
        val doc = parser.parse("hello :)")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        assertTrue(para.children.size >= 2)
        val emoji = para.children.last()
        assertIs<Emoji>(emoji)
        assertEquals("😊", emoji.literal)
    }

    @Test
    fun should_parse_ascii_emoticon_grin() {
        val parser = MarkdownParser(enableAsciiEmoticons = true)
        val doc = parser.parse("nice :D")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val emoji = para.children.last()
        assertIs<Emoji>(emoji)
        assertEquals("😃", emoji.literal)
    }

    @Test
    fun should_not_parse_ascii_emoticons_when_disabled() {
        val parser = MarkdownParser()
        val doc = parser.parse("hello :)")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val texts = para.children.filterIsInstance<Emoji>()
        assertTrue(texts.isEmpty())
    }

    @Test
    fun should_handle_multiple_emojis_in_text() {
        val parser = MarkdownParser()
        val doc = parser.parse("I :heart: Kotlin :rocket:")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val emojis = para.children.filterIsInstance<Emoji>()
        assertEquals(2, emojis.size)
        assertEquals("❤️", emojis[0].literal)
        assertEquals("🚀", emojis[1].literal)
    }
}
