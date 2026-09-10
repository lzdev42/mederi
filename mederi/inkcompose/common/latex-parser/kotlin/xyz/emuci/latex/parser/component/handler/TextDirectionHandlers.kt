package xyz.emuci.latex.parser.component.handler

import xyz.emuci.latex.parser.model.LatexNode

/**
 * 文本方向命令：\RLE, \LRE, \textarabic, \texthebrew
 *
 * 支持 RTL（从右到左）和 LTR（从左到右）文本方向切换。
 * - \RLE{...} — 强制从右到左排列
 * - \LRE{...} — 强制从左到右排列（嵌套在 RTL 中使用）
 * - \textarabic{...} — 阿拉伯语文本（RTL 方向）
 * - \texthebrew{...} — 希伯来语文本（RTL 方向）
 */
internal fun CommandRegistry.installTextDirectionHandlers() {
    // \RLE{content} — Right-to-Left Embedding
    register("RLE") { _, ctx, _ ->
        val arg = ctx.parseArgument() ?: return@register LatexNode.Text("")
        val content = when (arg) {
            is LatexNode.Group -> arg.children
            else -> listOf(arg)
        }
        LatexNode.TextDirection(content, LatexNode.TextDirection.Direction.RTL)
    }

    // \LRE{content} — Left-to-Right Embedding
    register("LRE") { _, ctx, _ ->
        val arg = ctx.parseArgument() ?: return@register LatexNode.Text("")
        val content = when (arg) {
            is LatexNode.Group -> arg.children
            else -> listOf(arg)
        }
        LatexNode.TextDirection(content, LatexNode.TextDirection.Direction.LTR)
    }

    // \textarabic{content} — 阿拉伯语文本（RTL）
    register("textarabic") { _, ctx, _ ->
        val arg = ctx.parseArgument() ?: return@register LatexNode.Text("")
        val content = when (arg) {
            is LatexNode.Group -> arg.children
            else -> listOf(arg)
        }
        LatexNode.TextDirection(content, LatexNode.TextDirection.Direction.RTL)
    }

    // \texthebrew{content} — 希伯来语文本（RTL）
    register("texthebrew") { _, ctx, _ ->
        val arg = ctx.parseArgument() ?: return@register LatexNode.Text("")
        val content = when (arg) {
            is LatexNode.Group -> arg.children
            else -> listOf(arg)
        }
        LatexNode.TextDirection(content, LatexNode.TextDirection.Direction.RTL)
    }
}
