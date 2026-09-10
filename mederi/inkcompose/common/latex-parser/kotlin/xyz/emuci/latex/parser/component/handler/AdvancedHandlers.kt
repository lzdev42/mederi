package xyz.emuci.latex.parser.component.handler

import xyz.emuci.latex.parser.model.LatexNode

/**
 * 高级数学命令：\sideset, \tensor, \indices
 */
internal fun CommandRegistry.installAdvancedHandlers() {
    register("sideset") { _, ctx, stream ->
        val (leftSub, leftSup) = ParseUtils.parseScriptGroup(ctx, stream)
        val (rightSub, rightSup) = ParseUtils.parseScriptGroup(ctx, stream)
        val baseArg = ctx.parseArgument() ?: return@register LatexNode.Text("\\sideset")
        LatexNode.SideSet(leftSub, leftSup, rightSub, rightSup, baseArg)
    }

    register("tensor") { _, ctx, stream ->
        val baseArg = ctx.parseArgument() ?: return@register LatexNode.Text("\\tensor")
        val indices = ParseUtils.parseTensorIndicesGroup(ctx, stream)
        LatexNode.Tensor(baseArg, indices)
    }

    register("indices") { _, ctx, stream ->
        val indices = ParseUtils.parseTensorIndicesGroup(ctx, stream)
        LatexNode.Tensor(LatexNode.Text(""), indices)
    }

    // \prescript{上标}{下标}{基础符号}
    register("prescript") { _, ctx, _ ->
        val supArg = ctx.parseArgument() ?: return@register LatexNode.Text("\\prescript")
        val subArg = ctx.parseArgument() ?: return@register LatexNode.Text("\\prescript")
        val baseArg = ctx.parseArgument() ?: return@register LatexNode.Text("\\prescript")

        // 空参数（{}）视为 null
        val preSup = when {
            supArg is LatexNode.Group && supArg.children.isEmpty() -> null
            supArg is LatexNode.Text && supArg.content.isBlank() -> null
            else -> supArg
        }
        val preSub = when {
            subArg is LatexNode.Group && subArg.children.isEmpty() -> null
            subArg is LatexNode.Text && subArg.content.isBlank() -> null
            else -> subArg
        }

        LatexNode.Prescript(preSup, preSub, baseArg)
    }
}
