package xyz.emuci.inkcompose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import xyz.emuci.markdown.parser.ast.FencedCodeBlock
import xyz.emuci.markdown.renderer.internal.core.compile.DefaultRenderModelCompiler
import xyz.emuci.markdown.renderer.internal.core.compile.RenderCompileEnvironment
import xyz.emuci.markdown.renderer.internal.selection.buildSelectionIndex
import xyz.emuci.markdown.renderer.internal.selection.extractSelectedText
import xyz.emuci.markdown.renderer.internal.selection.SelectionRange

class LiteralSelectionDocumentTest {
    @Test
    fun plain_text_selection_preserves_markdown_symbols_blank_lines_and_trailing_newline() {
        val content = "# literal **text**\n\n```not a fence\n中文 😀\n"
        assertEquals(content, copiedLiteral(content, RenderType.TEXT))
    }

    @Test
    fun standalone_code_selection_keeps_source_and_language_without_parsing_fences() {
        val content = "val x = \"```\"\n  // 中文 😀\n"
        val document = literalRenderDocument(content, RenderType.CODE, "kotlin")
        assertEquals("kotlin", assertIs<FencedCodeBlock>(document.children.single()).language)
        assertEquals(content, copiedLiteral(content, RenderType.CODE))
    }

    private fun copiedLiteral(content: String, type: RenderType): String {
        val document = literalRenderDocument(content, type, "kotlin")
        val model = DefaultRenderModelCompiler.compile(document, RenderCompileEnvironment())
        val index = buildSelectionIndex(model.blocks)
        return extractSelectedText(index, SelectionRange(requireNotNull(index.firstAnchor), requireNotNull(index.lastAnchor)))
    }
}
