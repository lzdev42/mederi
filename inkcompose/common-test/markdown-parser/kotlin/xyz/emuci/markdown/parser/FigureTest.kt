package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FigureTest {

    private val parser = MarkdownParser()

    // ────── Basic Figure ──────

    @Test
    fun should_convert_standalone_image_to_figure() {
        val doc = parser.parse("![Alt Text](https://example.com/image.png)")
        val figure = doc.children.first()
        assertIs<Figure>(figure)
        assertEquals("https://example.com/image.png", figure.imageUrl)
        assertEquals("Alt Text", figure.caption)
    }

    @Test
    fun should_use_title_as_caption_when_available() {
        val doc = parser.parse("![Alt](https://example.com/img.png \"图片标题\")")
        val figure = doc.children.first()
        assertIs<Figure>(figure)
        assertEquals("图片标题", figure.caption)
    }

    @Test
    fun should_use_alt_text_as_caption_fallback() {
        val doc = parser.parse("![图片说明](https://example.com/img.png)")
        val figure = doc.children.first()
        assertIs<Figure>(figure)
        assertEquals("图片说明", figure.caption)
    }

    @Test
    fun should_preserve_image_dimensions() {
        val doc = parser.parse("![Alt](https://example.com/img.png =200x300)")
        val figure = doc.children.first()
        assertIs<Figure>(figure)
        assertEquals(200, figure.imageWidth)
        assertEquals(300, figure.imageHeight)
    }

    // ────── Non-Figure Cases ──────

    @Test
    fun should_not_convert_image_with_surrounding_text() {
        val doc = parser.parse("Text before ![Alt](https://example.com/img.png) text after")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        // Should remain a Paragraph, not a Figure
    }

    @Test
    fun should_not_convert_multiple_images_paragraph() {
        val doc = parser.parse("![A](a.png) ![B](b.png)")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
    }

    @Test
    fun should_not_convert_image_in_list() {
        val doc = parser.parse("- ![Alt](https://example.com/img.png)")
        val list = doc.children.first()
        assertIs<ListBlock>(list)
        // Image inside list item should not become a Figure at the top level
    }

    // ────── Multiple Figures ──────

    @Test
    fun should_convert_multiple_standalone_images() {
        val doc = parser.parse(
            """
            ![First](first.png)

            ![Second](second.png)
            """.trimIndent()
        )
        val figures = doc.children.filterIsInstance<Figure>()
        assertEquals(2, figures.size)
        assertEquals("first.png", figures[0].imageUrl)
        assertEquals("second.png", figures[1].imageUrl)
    }

    // ────── Edge Cases ──────

    @Test
    fun should_handle_empty_alt_text() {
        val doc = parser.parse("![](https://example.com/img.png)")
        val figure = doc.children.first()
        assertIs<Figure>(figure)
        assertEquals("", figure.caption)
    }

    @Test
    fun should_handle_figure_with_attributes() {
        val doc = parser.parse("![Alt](https://example.com/img.png){.hero}")
        val figure = doc.children.first()
        assertIs<Figure>(figure)
        assertEquals("https://example.com/img.png", figure.imageUrl)
    }
}
