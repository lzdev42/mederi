package xyz.emuci.latex.parser.component.handler

import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.parser.tokenizer.LatexToken

/**
 * 标签、引用与公式编号命令：\label, \ref, \eqref, \tag, \substack
 */
internal fun CommandRegistry.installReferenceHandlers() {
    register("label") { _, ctx, _ ->
        val arg = ctx.parseArgument() ?: return@register LatexNode.Text("\\label")
        val key = when (arg) {
            is LatexNode.Text -> arg.content
            is LatexNode.Group -> ParseUtils.extractText(arg.children)
            else -> ""
        }
        LatexNode.Label(key)
    }

    register("ref") { _, ctx, _ ->
        val arg = ctx.parseArgument() ?: return@register LatexNode.Text("\\ref")
        val key = when (arg) {
            is LatexNode.Text -> arg.content
            is LatexNode.Group -> ParseUtils.extractText(arg.children)
            else -> ""
        }
        LatexNode.Ref(key)
    }

    register("eqref") { _, ctx, _ ->
        val arg = ctx.parseArgument() ?: return@register LatexNode.Text("\\eqref")
        val key = when (arg) {
            is LatexNode.Text -> arg.content
            is LatexNode.Group -> ParseUtils.extractText(arg.children)
            else -> ""
        }
        LatexNode.EqRef(key)
    }

    register("tag") { _, ctx, stream ->
        // 检查 \tag 后面是否跟着 *
        val starred = stream.peek()?.let { it is LatexToken.Text && it.content.startsWith("*") } == true
        if (starred) {
            val textToken = stream.peek() as LatexToken.Text
            stream.advance()
            val remaining = textToken.content.removePrefix("*")
            if (remaining.isNotEmpty()) {
                // 不太可能出现，但安全起见保留
            }
        }
        val arg = ctx.parseArgument() ?: LatexNode.Text("")
        LatexNode.Tag(arg, starred)
    }

    register("notag", "nonumber") { _, _, _ ->
        LatexNode.Tag(LatexNode.Group(emptyList()), starred = true)
    }

    register("intertext", "shortintertext") { _, ctx, _ ->
        val arg = ctx.parseArgument() ?: LatexNode.Text("")
        val text = when (arg) {
            is LatexNode.Group -> ParseUtils.extractText(arg.children)
            else -> ParseUtils.extractText(listOf(arg))
        }
        LatexNode.TextMode(text)
    }

    register("substack") { _, ctx, stream ->
        if (stream.peek() !is LatexToken.LeftBrace) {
            return@register LatexNode.Text("\\substack")
        }
        stream.advance() // 消费 {

        val rows = mutableListOf<List<LatexNode>>()
        var currentRow = mutableListOf<LatexNode>()

        while (!stream.isEOF()) {
            val token = stream.peek()
            when {
                token is LatexToken.RightBrace -> {
                    stream.advance()
                    break
                }
                token is LatexToken.NewLine -> {
                    stream.advance()
                    rows.add(currentRow.toList())
                    currentRow = mutableListOf()
                }
                else -> {
                    val node = ctx.parseExpression()
                    if (node != null) currentRow.add(node)
                }
            }
        }

        if (currentRow.isNotEmpty()) {
            rows.add(currentRow)
        }

        LatexNode.Substack(rows)
    }
}
