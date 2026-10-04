package xyz.emuci.markdown.renderer

import xyz.emuci.markdown.parser.flavour.CommonMarkFlavour
import xyz.emuci.markdown.parser.flavour.ExtendedFlavour
import xyz.emuci.markdown.parser.flavour.GFMFlavour
import xyz.emuci.markdown.parser.flavour.MarkdownExtraFlavour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MarkdownConfigTest {

    @Test
    fun should_use_extended_flavour_by_default() {
        val config = MarkdownConfig()
        assertSame(ExtendedFlavour, config.flavour)
    }

    @Test
    fun should_have_empty_emoji_map_by_default() {
        val config = MarkdownConfig()
        assertTrue(config.customEmojiMap.isEmpty())
    }

    @Test
    fun should_disable_ascii_emoticons_by_default() {
        val config = MarkdownConfig()
        assertFalse(config.enableAsciiEmoticons)
    }

    @Test
    fun should_disable_linting_by_default() {
        val config = MarkdownConfig()
        assertFalse(config.enableLinting)
    }

    @Test
    fun should_accept_gfm_flavour() {
        val config = MarkdownConfig(flavour = GFMFlavour)
        assertSame(GFMFlavour, config.flavour)
    }

    @Test
    fun should_accept_commonmark_flavour() {
        val config = MarkdownConfig(flavour = CommonMarkFlavour)
        assertSame(CommonMarkFlavour, config.flavour)
    }

    @Test
    fun should_accept_markdown_extra_flavour() {
        val config = MarkdownConfig(flavour = MarkdownExtraFlavour)
        assertSame(MarkdownExtraFlavour, config.flavour)
    }

    @Test
    fun should_accept_custom_emoji_map() {
        val emojiMap = mapOf("rocket" to "\uD83D\uDE80", "star" to "\u2B50")
        val config = MarkdownConfig(customEmojiMap = emojiMap)
        assertEquals(2, config.customEmojiMap.size)
        assertEquals("\uD83D\uDE80", config.customEmojiMap["rocket"])
    }

    @Test
    fun should_enable_ascii_emoticons() {
        val config = MarkdownConfig(enableAsciiEmoticons = true)
        assertTrue(config.enableAsciiEmoticons)
    }

    @Test
    fun should_enable_linting() {
        val config = MarkdownConfig(enableLinting = true)
        assertTrue(config.enableLinting)
    }

    @Test
    fun should_have_default_singleton() {
        val config = MarkdownConfig.Default
        assertSame(ExtendedFlavour, config.flavour)
        assertTrue(config.customEmojiMap.isEmpty())
        assertFalse(config.enableAsciiEmoticons)
        assertFalse(config.enableLinting)
    }

    @Test
    fun should_support_data_class_equality() {
        val config1 = MarkdownConfig(flavour = GFMFlavour)
        val config2 = MarkdownConfig(flavour = GFMFlavour)
        assertEquals(config1, config2)
    }

    @Test
    fun should_distinguish_different_flavours() {
        val config1 = MarkdownConfig(flavour = GFMFlavour)
        val config2 = MarkdownConfig(flavour = CommonMarkFlavour)
        assertNotEquals(config1, config2)
    }

    @Test
    fun should_support_data_class_copy() {
        val base = MarkdownConfig.Default
        val modified = base.copy(flavour = GFMFlavour, enableLinting = true)
        assertSame(GFMFlavour, modified.flavour)
        assertTrue(modified.enableLinting)
        assertFalse(modified.enableAsciiEmoticons)
    }

    @Test
    fun should_disable_heading_numbering_by_default() {
        val config = MarkdownConfig()
        assertFalse(config.enableHeadingNumbering)
    }

    @Test
    fun should_enable_heading_numbering() {
        val config = MarkdownConfig(enableHeadingNumbering = true)
        assertTrue(config.enableHeadingNumbering)
    }

    @Test
    fun should_copy_with_heading_numbering() {
        val base = MarkdownConfig.Default
        val modified = base.copy(enableHeadingNumbering = true)
        assertTrue(modified.enableHeadingNumbering)
        assertFalse(modified.enableLinting)
        assertSame(ExtendedFlavour, modified.flavour)
    }

    @Test
    fun should_include_heading_numbering_in_equality() {
        val config1 = MarkdownConfig(enableHeadingNumbering = true)
        val config2 = MarkdownConfig(enableHeadingNumbering = false)
        assertNotEquals(config1, config2)
    }

    @Test
    fun should_default_singleton_not_have_heading_numbering() {
        assertFalse(MarkdownConfig.Default.enableHeadingNumbering)
    }

    @Test
    fun should_default_enable_highlight_to_null() {
        val config = MarkdownConfig()
        assertEquals(null, config.enableHighlight)
    }

    @Test
    fun should_override_enable_highlight() {
        val disabled = MarkdownConfig(enableHighlight = false)
        assertEquals(false, disabled.enableHighlight)
        val enabled = MarkdownConfig(enableHighlight = true)
        assertEquals(true, enabled.enableHighlight)
        assertNotEquals(disabled, enabled)
    }
}
