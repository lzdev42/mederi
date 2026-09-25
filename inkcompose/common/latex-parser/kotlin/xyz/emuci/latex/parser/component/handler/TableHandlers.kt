package xyz.emuci.latex.parser.component.handler

import xyz.emuci.latex.parser.model.LatexNode

/**
 * 表格相关命令：\hline, \cline, \multicolumn
 */
internal fun CommandRegistry.installTableHandlers() {
    register("hline") { _, _, _ -> LatexNode.HLine() }

    register("cline") { _, ctx, _ ->
        val arg = ctx.parseArgument() ?: return@register LatexNode.Text("\\cline")
        val rangeStr = when (arg) {
            is LatexNode.Text -> arg.content
            is LatexNode.Group -> ParseUtils.extractText(arg.children)
            else -> ""
        }
        val parts = rangeStr.split("-")
        val startCol = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
        val endCol = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: startCol
        LatexNode.CLine(startCol, endCol)
    }

    register("multicolumn") { _, ctx, _ ->
        // 第一个参数：列数
        val numArg = ctx.parseArgument() ?: return@register LatexNode.Text("\\multicolumn")
        val numStr = when (numArg) {
            is LatexNode.Text -> numArg.content
            is LatexNode.Group -> ParseUtils.extractText(numArg.children)
            else -> "1"
        }
        val columnCount = numStr.trim().toIntOrNull() ?: 1

        // 第二个参数：对齐方式
        val alignArg = ctx.parseArgument() ?: return@register LatexNode.Text("\\multicolumn")
        val alignment = when (alignArg) {
            is LatexNode.Text -> alignArg.content
            is LatexNode.Group -> ParseUtils.extractText(alignArg.children)
            else -> "c"
        }

        // 第三个参数：内容
        val contentArg = ctx.parseArgument() ?: return@register LatexNode.Text("\\multicolumn")
        val content = when (contentArg) {
            is LatexNode.Group -> contentArg.children
            else -> listOf(contentArg)
        }

        LatexNode.Multicolumn(columnCount, alignment, content)
    }
}
