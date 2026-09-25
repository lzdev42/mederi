package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import xyz.emuci.markdown.parser.html.HtmlRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * tests for custom directive syntax: `{% tag arg1 "arg2" key=value %}`.
 */
class DirectiveTest {

    private fun parse(input: String): Document {
        return MarkdownParser().parse(input)
    }

    private fun renderHtml(input: String): String {
        return HtmlRenderer.renderMarkdown(input)
    }

    // ---- block-level directives ----

    @Test
    fun should_parse_self_closing_block_directive() {
        val doc = parse("{% youtube abc123 %}")
        val directive = doc.children.filterIsInstance<DirectiveBlock>().firstOrNull()
        assertTrue(directive != null, "should produce a DirectiveBlock")
        assertEquals("youtube", directive.tagName)
        assertEquals("abc123", directive.args["_0"])
    }

    @Test
    fun should_parse_block_directive_with_key_value_args() {
        val doc = parse("""{% include file="header.html" cache=true %}""")
        val directive = doc.children.filterIsInstance<DirectiveBlock>().firstOrNull()
        assertTrue(directive != null, "should produce a DirectiveBlock")
        assertEquals("include", directive.tagName)
        assertEquals("header.html", directive.args["file"])
        assertEquals("true", directive.args["cache"])
    }

    @Test
    fun should_parse_block_directive_with_content() {
        val doc = parse("""
{% alert %}
This is a warning message.
{% endalert %}
        """.trimIndent())
        val directive = doc.children.filterIsInstance<DirectiveBlock>().firstOrNull()
        assertTrue(directive != null, "should produce a DirectiveBlock")
        assertEquals("alert", directive.tagName)
        // content between opening and closing tag should be parsed as children
        assertTrue(directive.children.isNotEmpty(), "should have children content")
    }

    @Test
    fun should_render_block_directive_html() {
        val html = renderHtml("{% youtube abc123 %}")
        assertTrue(html.contains("data-directive=\"youtube\""), "html should contain data-directive")
    }

    @Test
    fun should_render_block_directive_with_args_html() {
        val html = renderHtml("""{% include file="header.html" %}""")
        assertTrue(html.contains("data-directive=\"include\""), "html should contain data-directive")
        assertTrue(html.contains("data-args="), "html should contain data-args")
    }

    @Test
    fun should_render_block_directive_with_content_html() {
        val html = renderHtml("""
{% note %}
Important stuff here.
{% endnote %}
        """.trimIndent())
        assertTrue(html.contains("data-directive=\"note\""))
        assertTrue(html.contains("Important stuff here."))
    }

    // ---- inline directives ----

    @Test
    fun should_parse_inline_directive() {
        val doc = parse("Text with {% icon name=star %} inside.")
        val para = doc.children.filterIsInstance<Paragraph>().firstOrNull()
        assertTrue(para != null, "should have a paragraph")
        val directive = para.children.filterIsInstance<DirectiveInline>().firstOrNull()
        assertTrue(directive != null, "should produce a DirectiveInline")
        assertEquals("icon", directive.tagName)
        assertEquals("star", directive.args["name"])
    }

    @Test
    fun should_render_inline_directive_html() {
        val html = renderHtml("Text with {% icon name=star %} inside.")
        assertTrue(html.contains("data-directive=\"icon\""))
        assertTrue(html.contains("<span"))
    }

    // ---- edge cases ----

    @Test
    fun should_not_parse_invalid_directive_missing_close() {
        val doc = parse("Text with {% incomplete tag")
        val para = doc.children.filterIsInstance<Paragraph>().firstOrNull()
        assertTrue(para != null, "should have a paragraph")
        // should not have any directive nodes
        val directives = para.children.filterIsInstance<DirectiveInline>()
        assertEquals(0, directives.size, "should not produce DirectiveInline for unclosed tag")
    }

    @Test
    fun should_not_parse_end_tag_as_directive() {
        val doc = parse("{% endfoo %}")
        // end tags should not create block directives
        val directives = doc.children.filterIsInstance<DirectiveBlock>()
        assertEquals(0, directives.size, "end tags should not produce DirectiveBlock")
    }

    @Test
    fun should_parse_directive_with_quoted_positional_args() {
        val doc = parse("""{% tag "hello world" %}""")
        val directive = doc.children.filterIsInstance<DirectiveBlock>().firstOrNull()
        assertTrue(directive != null)
        assertEquals("tag", directive.tagName)
        assertEquals("hello world", directive.args["_0"])
    }
}
