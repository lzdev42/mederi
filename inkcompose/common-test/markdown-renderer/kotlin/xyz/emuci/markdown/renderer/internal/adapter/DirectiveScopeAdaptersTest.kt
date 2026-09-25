package xyz.emuci.markdown.renderer.internal.adapter

import kotlin.test.Test
import kotlin.test.assertEquals

class DirectiveScopeAdaptersTest {
    @Test
    fun should_create_block_scope_with_synthetic_directive_node() {
        val scope = createDirectiveBlockRenderScope(
            tagName = "video",
            args = mapOf("url" to "https://example.com/demo.mp4"),
        )

        assertEquals("video", scope.directive.tagName)
        assertEquals("https://example.com/demo.mp4", scope.directive.args["url"])
    }

    @Test
    fun should_create_inline_scope_with_synthetic_directive_node() {
        val scope = createDirectiveInlineRenderScope(
            tagName = "badge",
            args = mapOf("text" to "beta"),
            alternateText = "beta",
        )

        assertEquals("badge", scope.directive.tagName)
        assertEquals("beta", scope.directive.args["text"])
        assertEquals("beta", scope.directive.alternateText)
    }
}
