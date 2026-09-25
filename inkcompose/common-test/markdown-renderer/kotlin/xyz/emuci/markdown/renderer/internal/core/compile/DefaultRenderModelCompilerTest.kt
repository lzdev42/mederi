package xyz.emuci.markdown.renderer.internal.core.compile

import xyz.emuci.markdown.parser.MarkdownParser
import xyz.emuci.markdown.parser.ast.Document
import xyz.emuci.markdown.parser.ast.Paragraph
import xyz.emuci.markdown.parser.ast.TableHead
import xyz.emuci.markdown.parser.ast.Text
import xyz.emuci.markdown.renderer.inline.InlinePlaceholderId
import xyz.emuci.markdown.renderer.internal.core.model.AdmonitionBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.FallbackContainerBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.FallbackLeafBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.InlineMathWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.ParagraphBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.WidgetAtom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultRenderModelCompilerTest {
    @Test
    fun should_compile_unknown_container_node_into_internal_fallback_container() {
        val document = Document().apply {
            appendChild(
                TableHead().apply {
                    appendChild(
                        Paragraph().apply {
                            appendChild(Text("fallback child"))
                        }
                    )
                }
            )
        }

        val renderDocument = DefaultRenderModelCompiler.compile(document, RenderCompileEnvironment())

        val fallback = assertIs<FallbackContainerBlockModel>(renderDocument.blocks.single())
        assertIs<ParagraphBlockModel>(fallback.children.single())
    }

    @Test
    fun should_compile_unknown_leaf_node_into_internal_fallback_leaf() {
        val document = Document().apply {
            appendChild(Text("orphan inline"))
        }

        val renderDocument = DefaultRenderModelCompiler.compile(document, RenderCompileEnvironment())

        assertIs<FallbackLeafBlockModel>(renderDocument.blocks.single())
    }

    @Test
    fun should_assign_distinct_placeholder_ids_to_multiple_inline_math_widgets() {
        val document = MarkdownParser().parse(
            "A battery does \$144\\text{ J}\$ of work with a potential difference of \$12\\text{ V}\$."
        )

        val renderDocument = DefaultRenderModelCompiler.compile(document, RenderCompileEnvironment())

        val paragraph = assertIs<ParagraphBlockModel>(renderDocument.blocks.single())
        val widgets = paragraph.inline.atoms
            .filterIsInstance<WidgetAtom>()
            .map { it.widget }
            .filterIsInstance<InlineMathWidgetModel>()
        val placeholderIds = widgets.map { InlinePlaceholderId.from(it) }

        assertEquals(listOf("144\\text{ J}", "12\\text{ V}"), widgets.map { it.latex })
        assertEquals(placeholderIds.size, placeholderIds.toSet().size)
    }

    @Test
    fun should_compile_numeric_answer_inline_math_as_widget() {
        val document = MarkdownParser().parse("\$12\\text{ C}\$")

        val renderDocument = DefaultRenderModelCompiler.compile(document, RenderCompileEnvironment())

        val paragraph = assertIs<ParagraphBlockModel>(renderDocument.blocks.single())
        val widget = paragraph.inline.atoms
            .filterIsInstance<WidgetAtom>()
            .map { it.widget }
            .single()

        assertIs<InlineMathWidgetModel>(widget)
        assertEquals("12\\text{ C}", widget.latex)
    }

    @Test
    fun should_update_render_identity_when_paragraph_text_changes() {
        val parser = MarkdownParser()
        val doc1 = parser.parse("Hello ")
        val doc2 = parser.parse("Hello World")
        val env = RenderCompileEnvironment()
        val renderDoc1 = DefaultRenderModelCompiler.compile(doc1, env)
        val renderDoc2 = DefaultRenderModelCompiler.compile(doc2, env)

        val p1 = assertIs<ParagraphBlockModel>(renderDoc1.blocks.single())
        val p2 = assertIs<ParagraphBlockModel>(renderDoc2.blocks.single())

        assertTrue(
            p1.identity.contentRevision != p2.identity.contentRevision,
            "Paragraph contentRevision must differ when text changes"
        )
        assertTrue(
            p1.identity.layoutRevision != p2.identity.layoutRevision,
            "Paragraph layoutRevision must differ when text changes"
        )
    }

    @Test
    fun should_update_admonition_children_identity_during_streaming_append() {
        val parser = MarkdownParser()
        parser.beginStream()
        val doc1 = parser.append("> [!IMPORTANT]\n> First")
        val env = RenderCompileEnvironment()
        val renderDoc1 = DefaultRenderModelCompiler.compile(doc1, env)

        val doc2 = parser.append(" Second")
        val renderDoc2 = DefaultRenderModelCompiler.compile(doc2, env)

        val adm1 = renderDoc1.blocks.filterIsInstance<AdmonitionBlockModel>().single()
        val adm2 = renderDoc2.blocks.filterIsInstance<AdmonitionBlockModel>().single()

        val p1 = adm1.children.filterIsInstance<ParagraphBlockModel>().single()
        val p2 = adm2.children.filterIsInstance<ParagraphBlockModel>().single()

        assertTrue(
            p1.identity.contentRevision != p2.identity.contentRevision,
            "Admonition child paragraph contentRevision must update as text streams: p1=${p1.identity}, p2=${p2.identity}"
        )
        assertTrue(
            p1.identity.layoutRevision != p2.identity.layoutRevision,
            "Admonition child paragraph layoutRevision must update as text streams: p1=${p1.identity}, p2=${p2.identity}"
        )

        parser.endStream()
    }
}
