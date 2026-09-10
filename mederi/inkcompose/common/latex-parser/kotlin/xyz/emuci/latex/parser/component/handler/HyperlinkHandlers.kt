package xyz.emuci.latex.parser.component.handler

import xyz.emuci.latex.parser.model.LatexNode

/**
 * 超链接命令：\href, \url
 */
internal fun CommandRegistry.installHyperlinkHandlers() {
    // \href{url}{text}
    register("href") { _, ctx, _ ->
        val urlArg = ctx.parseArgument() ?: return@register LatexNode.Text("")
        val url = ParseUtils.extractText(
            when (urlArg) {
                is LatexNode.Group -> urlArg.children
                else -> listOf(urlArg)
            }
        )

        val contentArg = ctx.parseArgument() ?: return@register LatexNode.Text("")
        val content = when (contentArg) {
            is LatexNode.Group -> contentArg.children
            else -> listOf(contentArg)
        }

        LatexNode.Hyperlink(url, content)
    }

    // \url{url} — 显示 URL 文本本身
    register("url") { _, ctx, _ ->
        val urlArg = ctx.parseArgument() ?: return@register LatexNode.Text("")
        val url = ParseUtils.extractText(
            when (urlArg) {
                is LatexNode.Group -> urlArg.children
                else -> listOf(urlArg)
            }
        )

        // \url 没有显示文本参数，内容为空列表，渲染时显示 URL 本身
        LatexNode.Hyperlink(url, emptyList())
    }
}
