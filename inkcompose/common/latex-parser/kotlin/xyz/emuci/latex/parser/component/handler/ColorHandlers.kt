package xyz.emuci.latex.parser.component.handler

import xyz.emuci.latex.parser.model.LatexNode

/**
 * 颜色命令：\color, \textcolor
 */
internal fun CommandRegistry.installColorHandlers() {
    val colorHandler = CommandHandler { _, ctx, _ ->
        val colorArg = ctx.parseArgument() ?: return@CommandHandler LatexNode.Text("")
        val colorName = ParseUtils.extractColorName(colorArg)

        val contentArg = ctx.parseArgument() ?: return@CommandHandler LatexNode.Text("")
        val content = when (contentArg) {
            is LatexNode.Group -> contentArg.children
            else -> listOf(contentArg)
        }

        LatexNode.Color(content, colorName)
    }

    register("color", "textcolor", handler = colorHandler)

    // \colorbox{color}{text}
    register("colorbox") { _, ctx, _ ->
        val colorArg = ctx.parseArgument() ?: return@register LatexNode.Text("")
        val colorName = ParseUtils.extractColorName(colorArg)

        val contentArg = ctx.parseArgument() ?: return@register LatexNode.Text("")
        val content = when (contentArg) {
            is LatexNode.Group -> contentArg.children
            else -> listOf(contentArg)
        }

        LatexNode.ColorBox(content, colorName)
    }

    // \fcolorbox{borderColor}{bgColor}{text}
    register("fcolorbox") { _, ctx, _ ->
        val borderColorArg = ctx.parseArgument() ?: return@register LatexNode.Text("")
        val borderColor = ParseUtils.extractColorName(borderColorArg)

        val bgColorArg = ctx.parseArgument() ?: return@register LatexNode.Text("")
        val bgColor = ParseUtils.extractColorName(bgColorArg)

        val contentArg = ctx.parseArgument() ?: return@register LatexNode.Text("")
        val content = when (contentArg) {
            is LatexNode.Group -> contentArg.children
            else -> listOf(contentArg)
        }

        LatexNode.ColorBox(content, bgColor, borderColor)
    }
}
