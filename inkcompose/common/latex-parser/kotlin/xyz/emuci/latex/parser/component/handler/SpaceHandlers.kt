package xyz.emuci.latex.parser.component.handler

import xyz.emuci.latex.parser.model.LatexNode

/**
 * 空格命令：\,  \:  \;  \quad  \qquad  \!  \hspace 及常用别名
 */
internal fun CommandRegistry.installSpaceHandlers() {
    register(" ") { _, _, _ -> LatexNode.Space(LatexNode.Space.SpaceType.NORMAL) }
    register("space") { _, _, _ -> LatexNode.Space(LatexNode.Space.SpaceType.NORMAL) }
    register(",") { _, _, _ -> LatexNode.Space(LatexNode.Space.SpaceType.THIN) }
    register("thinspace") { _, _, _ -> LatexNode.Space(LatexNode.Space.SpaceType.THIN) }
    register(":") { _, _, _ -> LatexNode.Space(LatexNode.Space.SpaceType.MEDIUM) }
    register(">") { _, _, _ -> LatexNode.Space(LatexNode.Space.SpaceType.MEDIUM) }
    register("medspace") { _, _, _ -> LatexNode.Space(LatexNode.Space.SpaceType.MEDIUM) }
    register(";") { _, _, _ -> LatexNode.Space(LatexNode.Space.SpaceType.THICK) }
    register("thickspace") { _, _, _ -> LatexNode.Space(LatexNode.Space.SpaceType.THICK) }
    register("quad") { _, _, _ -> LatexNode.Space(LatexNode.Space.SpaceType.QUAD) }
    register("qquad") { _, _, _ -> LatexNode.Space(LatexNode.Space.SpaceType.QQUAD) }
    register("!") { _, _, _ -> LatexNode.Space(LatexNode.Space.SpaceType.NEGATIVE_THIN) }
    register("negthinspace") { _, _, _ -> LatexNode.Space(LatexNode.Space.SpaceType.NEGATIVE_THIN) }
    register("enspace") { _, _, _ -> LatexNode.HSpace("0.5em") }
    register("enskip") { _, _, _ -> LatexNode.HSpace("0.5em") }
    register("negmedspace") { _, _, _ -> LatexNode.HSpace("-0.222em") }
    register("negthickspace") { _, _, _ -> LatexNode.HSpace("-0.277em") }

    register("hspace") { _, ctx, _ ->
        val dimension = when (val arg = ctx.parseArgument()) {
            is LatexNode.Text -> arg.content
            is LatexNode.Group -> ParseUtils.extractText(arg.children)
            else -> "0pt"
        }
        LatexNode.HSpace(dimension)
    }

    register("kern", "mkern") { _, ctx, stream ->
        LatexNode.HSpace(ParseUtils.parseDimension(ctx, stream))
    }
    register("allowbreak") { _, _, _ -> LatexNode.HSpace("0pt") }
    register("cr") { _, _, _ -> LatexNode.NewLine() }
}
