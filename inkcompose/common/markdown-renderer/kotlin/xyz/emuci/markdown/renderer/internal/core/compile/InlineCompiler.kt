package xyz.emuci.markdown.renderer.internal.core.compile

import xyz.emuci.markdown.parser.ast.Abbreviation
import xyz.emuci.markdown.parser.ast.Autolink
import xyz.emuci.markdown.parser.ast.CitationReference
import xyz.emuci.markdown.parser.ast.ContainerNode
import xyz.emuci.markdown.parser.ast.DirectiveInline
import xyz.emuci.markdown.parser.ast.Emoji
import xyz.emuci.markdown.parser.ast.Emphasis
import xyz.emuci.markdown.parser.ast.EscapedChar
import xyz.emuci.markdown.parser.ast.FootnoteReference
import xyz.emuci.markdown.parser.ast.HardLineBreak
import xyz.emuci.markdown.parser.ast.Highlight
import xyz.emuci.markdown.parser.ast.HtmlEntity
import xyz.emuci.markdown.parser.ast.Image
import xyz.emuci.markdown.parser.ast.InlineCode
import xyz.emuci.markdown.parser.ast.InlineHtml
import xyz.emuci.markdown.parser.ast.InlineMath
import xyz.emuci.markdown.parser.ast.InsertedText
import xyz.emuci.markdown.parser.ast.KeyboardInput
import xyz.emuci.markdown.parser.ast.LeafNode
import xyz.emuci.markdown.parser.ast.Link
import xyz.emuci.markdown.parser.ast.Node
import xyz.emuci.markdown.parser.ast.RubyText
import xyz.emuci.markdown.parser.ast.SoftLineBreak
import xyz.emuci.markdown.parser.ast.Spoiler
import xyz.emuci.markdown.parser.ast.Strikethrough
import xyz.emuci.markdown.parser.ast.StrongEmphasis
import xyz.emuci.markdown.parser.ast.StyledText
import xyz.emuci.markdown.parser.ast.Subscript
import xyz.emuci.markdown.parser.ast.Superscript
import xyz.emuci.markdown.parser.ast.Text
import xyz.emuci.markdown.parser.ast.WikiLink
import xyz.emuci.markdown.parser.SourceRange
import xyz.emuci.markdown.renderer.internal.core.identity.RenderIdentity
import xyz.emuci.markdown.renderer.internal.core.identity.renderIdentityFromText
import xyz.emuci.markdown.renderer.internal.core.identity.renderIdentityFromValues
import xyz.emuci.markdown.renderer.internal.core.identity.renderIdentityMix
import xyz.emuci.markdown.renderer.internal.core.identity.renderIdentitySeed
import xyz.emuci.markdown.renderer.internal.core.model.DirectiveInlineWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.ImageWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.InlineAtom
import xyz.emuci.markdown.renderer.internal.core.model.InlineCodeWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.InlineMathWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.InlineModel
import xyz.emuci.markdown.renderer.internal.core.model.RubyTextWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.SpanMark
import xyz.emuci.markdown.renderer.internal.core.model.SpoilerWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.TextAtom
import xyz.emuci.markdown.renderer.internal.core.model.WidgetAtom

internal fun compileInlineModel(
    nodes: List<Node>,
    inlineRevision: Long,
): InlineModel {
    return InlineModel(
        identity = RenderIdentity(
            stableId = stableInlineListId(nodes),
            contentRevision = inlineRevision,
            layoutRevision = inlineRevision,
            paintRevision = 0L,
        ),
        atoms = buildList {
            compileInlineNodes(
                nodes = nodes,
                activeMarks = emptyList(),
                sink = this,
                parentStableId = stableInlineListId(nodes),
            )
        }
    )
}

private fun compileInlineNodes(
    nodes: List<Node>,
    activeMarks: List<SpanMark>,
    sink: MutableList<InlineAtom>,
    parentStableId: Long,
) {
    nodes.forEachIndexed { index, node ->
        compileInlineNode(
            node = node,
            activeMarks = activeMarks,
            sink = sink,
            stableId = stableInlineNodeId(node, parentStableId, index),
        )
    }
}

private fun compileInlineNode(
    node: Node,
    activeMarks: List<SpanMark>,
    sink: MutableList<InlineAtom>,
    stableId: Long,
) {
    val identity = nodeIdentity(node, stableId)
    when (node) {
        is Text -> sink += TextAtom(identity, node.literal, activeMarks)
        is SoftLineBreak -> sink += TextAtom(identity, " ", activeMarks)
        is HardLineBreak -> sink += TextAtom(identity, "\n", activeMarks)
        is Emphasis -> compileInlineNodes(node.children, activeMarks + SpanMark("emphasis"), sink, stableId)
        is StrongEmphasis -> compileInlineNodes(node.children, activeMarks + SpanMark("strong"), sink, stableId)
        is Strikethrough -> compileInlineNodes(node.children, activeMarks + SpanMark("strikethrough"), sink, stableId)
        is Highlight -> compileInlineNodes(node.children, activeMarks + SpanMark("highlight"), sink, stableId)
        is Superscript -> compileInlineNodes(node.children, activeMarks + SpanMark("superscript"), sink, stableId)
        is Subscript -> compileInlineNodes(node.children, activeMarks + SpanMark("subscript"), sink, stableId)
        is InsertedText -> compileInlineNodes(node.children, activeMarks + SpanMark("inserted"), sink, stableId)
        is StyledText -> {
            compileInlineNodes(
                node.children,
                activeMarks + SpanMark(
                    kind = "styled",
                    payload = buildMap {
                        node.style?.let { put("style", it) }
                        if (node.cssClasses.isNotEmpty()) {
                            put("class", node.cssClasses.joinToString(" "))
                        }
                    }
                ),
                sink,
                stableId,
            )
        }

        is Link -> {
            compileInlineNodes(
                node.children,
                activeMarks + SpanMark(
                    kind = "link",
                    payload = mapOf(
                        "target" to node.destination,
                        "tag" to "link",
                    )
                ),
                sink,
                stableId,
            )
        }

        is Autolink -> {
            sink += TextAtom(
                identity = identity,
                text = node.destination,
                marks = activeMarks + SpanMark(
                    kind = "link",
                    payload = mapOf(
                        "target" to node.destination,
                        "tag" to "link",
                    )
                ),
            )
        }

        is WikiLink -> {
            sink += TextAtom(
                identity = identity,
                text = node.label ?: node.target,
                marks = activeMarks + SpanMark(
                    kind = "link",
                    payload = mapOf(
                        "target" to node.target,
                        "tag" to "wikilink",
                    )
                ),
            )
        }

        is InlineCode -> {
            sink += WidgetAtom(
                identity = identity,
                widget = InlineCodeWidgetModel(
                    identity = identity,
                    code = node.literal,
                )
            )
        }

        is Image -> {
            sink += WidgetAtom(
                identity = identity,
                widget = ImageWidgetModel(
                    identity = identity,
                    url = node.destination,
                    altText = extractPlainText(node),
                    title = node.title,
                    width = node.imageWidth,
                    height = node.imageHeight,
                    attributes = node.attributes,
                )
            )
        }

        is InlineMath -> {
            sink += WidgetAtom(
                identity = identity,
                widget = InlineMathWidgetModel(
                    identity = identity,
                    latex = node.literal,
                )
            )
        }

        is FootnoteReference -> {
            sink += TextAtom(
                identity = identity,
                text = "[${node.index}]",
                marks = activeMarks + SpanMark(
                    kind = "footnote",
                    payload = mapOf(
                        "label" to node.label,
                        "index" to node.index.toString(),
                    )
                ),
            )
        }

        is CitationReference -> {
            sink += TextAtom(
                identity = identity,
                text = "[${node.key}]",
                marks = activeMarks + SpanMark(
                    kind = "citation",
                    payload = mapOf("key" to node.key),
                ),
            )
        }

        is InlineHtml -> sink += TextAtom(identity, node.literal, activeMarks + SpanMark("inline_html"))
        is HtmlEntity -> sink += TextAtom(identity, node.resolved.ifEmpty { node.literal }, activeMarks)
        is EscapedChar -> sink += TextAtom(identity, node.literal, activeMarks)
        is Emoji -> sink += TextAtom(identity, node.unicode ?: node.literal.ifEmpty { ":${node.shortcode}:" }, activeMarks)
        is Abbreviation -> {
            sink += TextAtom(
                identity = identity,
                text = node.abbreviation,
                marks = activeMarks + SpanMark(
                    kind = "abbreviation",
                    payload = mapOf("fullText" to node.fullText),
                ),
            )
        }

        is KeyboardInput -> sink += TextAtom(identity, node.literal, activeMarks + SpanMark("kbd"))
        is Spoiler -> {
            sink += WidgetAtom(
                identity = identity,
                widget = SpoilerWidgetModel(
                    identity = identity,
                    content = compileInlineModel(node.children, node.contentHash),
                    alternateText = extractPlainText(node),
                ),
            )
        }

        is DirectiveInline -> {
            sink += WidgetAtom(
                identity = identity,
                widget = DirectiveInlineWidgetModel(
                    identity = identity,
                    tagName = node.tagName,
                    args = node.args,
                    alternateText = buildInlineDirectiveFallbackText(node),
                ),
            )
        }

        is RubyText -> {
            sink += WidgetAtom(
                identity = identity,
                widget = RubyTextWidgetModel(
                    identity = identity,
                    base = node.base,
                    annotation = node.annotation,
                ),
            )
        }

        else -> {
            if (node is ContainerNode) {
                compileInlineNodes(node.children, activeMarks, sink, stableId)
            }
        }
    }
}

private fun nodeIdentity(node: Node, stableId: Long): RenderIdentity {
    val revision = if (node.contentHash != 0L) {
        node.contentHash
    } else {
        renderIdentityFromValues(stableId, inlineContentFingerprint(node))
    }
    return RenderIdentity(
        stableId = stableId,
        contentRevision = revision,
        layoutRevision = revision,
        paintRevision = 0L,
    )
}

private fun stableInlineListId(nodes: List<Node>): Long {
    if (nodes.isEmpty()) return 0L
    var acc = renderIdentitySeed()
    nodes.forEachIndexed { index, node ->
        acc = renderIdentityMix(acc, stableInlineNodeId(node, renderIdentitySeed(), index))
    }
    return acc
}

private fun stableInlineNodeId(node: Node, parentStableId: Long, siblingIndex: Int): Long {
    val typeId = renderIdentityFromText(node::class.simpleName ?: "inline")
    val range = node.sourceRange
    if (range != SourceRange.EMPTY && range.length > 0) {
        return renderIdentityFromValues(
            typeId,
            range.start.offset.toLong(),
            range.end.offset.toLong(),
            node.lineRange.startLine.toLong(),
            node.lineRange.endLine.toLong(),
        )
    }
    return renderIdentityFromValues(parentStableId, typeId, siblingIndex.toLong())
}

private fun inlineContentFingerprint(node: Node): Long {
    var acc = renderIdentityFromText(node::class.simpleName ?: "inline")
    when (node) {
        is Autolink -> acc = renderIdentityFromText(node.destination, acc)
        is WikiLink -> {
            acc = renderIdentityFromText(node.target, acc)
            acc = renderIdentityFromText(node.label.orEmpty(), acc)
        }
        is LeafNode -> acc = renderIdentityFromText(node.literal, acc)
        is Link -> {
            acc = renderIdentityFromText(node.destination, acc)
            acc = renderIdentityFromText(node.title.orEmpty(), acc)
        }
        is Image -> {
            acc = renderIdentityFromText(node.destination, acc)
            acc = renderIdentityFromText(node.title.orEmpty(), acc)
            acc = renderIdentityMix(acc, node.imageWidth?.toLong() ?: 0L)
            acc = renderIdentityMix(acc, node.imageHeight?.toLong() ?: 0L)
        }
        is ContainerNode -> {
            for (child in node.children) {
                acc = renderIdentityMix(acc, inlineContentFingerprint(child))
            }
        }
    }
    return acc
}

private fun buildInlineDirectiveFallbackText(node: DirectiveInline): String {
    val argsText = if (node.args.isNotEmpty()) {
        " " + node.args.entries.joinToString(" ") { (key, value) ->
            if (key.startsWith("_")) value else "$key=$value"
        }
    } else {
        ""
    }
    return "{% ${node.tagName}$argsText %}"
}

private fun extractPlainText(node: Node): String = buildString {
    when (node) {
        is Text -> append(node.literal)
        is InlineCode -> append(node.literal)
        is Image -> node.children.forEach { append(extractPlainText(it)) }
        is ContainerNode -> node.children.forEach { append(extractPlainText(it)) }
        else -> Unit
    }
}
