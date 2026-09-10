package xyz.emuci.latex.parser.component.handler

import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.parser.tokenizer.LatexToken

/**
 * 可扩展箭头 & 堆叠命令：\xrightarrow, \overset, \underset, \stackrel
 */
internal fun CommandRegistry.installArrowAndStackHandlers() {
    // 可扩展箭头
    val arrowMapping = mapOf(
        "xrightarrow" to LatexNode.ExtensibleArrow.Direction.RIGHT,
        "xleftarrow" to LatexNode.ExtensibleArrow.Direction.LEFT,
        "xleftrightarrow" to LatexNode.ExtensibleArrow.Direction.BOTH,
        "xhookrightarrow" to LatexNode.ExtensibleArrow.Direction.HOOK_RIGHT,
        "xhookleftarrow" to LatexNode.ExtensibleArrow.Direction.HOOK_LEFT,
        "xRightarrow" to LatexNode.ExtensibleArrow.Direction.RIGHT_DOUBLE,
        "xLeftarrow" to LatexNode.ExtensibleArrow.Direction.LEFT_DOUBLE,
        "xLeftrightarrow" to LatexNode.ExtensibleArrow.Direction.BOTH_DOUBLE,
        "xmapsto" to LatexNode.ExtensibleArrow.Direction.MAPSTO,
        "xlongequal" to LatexNode.ExtensibleArrow.Direction.EQUAL,
    )

    for ((cmd, direction) in arrowMapping) {
        register(cmd) { _, ctx, stream ->
            // 可选参数（下方文字）
            val below = if (stream.peek() is LatexToken.LeftBracket) {
                stream.advance() // 消费 [
                val nodes = ParseUtils.parseUntil(ctx, stream) { it is LatexToken.RightBracket }
                stream.advance() // 消费 ]
                if (nodes.isEmpty()) null else LatexNode.Group(nodes)
            } else {
                null
            }

            // 必选参数（上方文字）
            val above = ctx.parseArgument() ?: LatexNode.Text("")

            LatexNode.ExtensibleArrow(above, below, direction)
        }
    }

    // 堆叠：\overset, \stackrel
    register("overset", "stackrel") { _, ctx, _ ->
        val firstArg = ctx.parseArgument() ?: LatexNode.Text("")
        val base = ctx.parseArgument() ?: LatexNode.Text("")
        LatexNode.Stack(base = base, above = firstArg, below = null)
    }

    // 堆叠：\underset
    register("underset") { _, ctx, _ ->
        val firstArg = ctx.parseArgument() ?: LatexNode.Text("")
        val base = ctx.parseArgument() ?: LatexNode.Text("")
        LatexNode.Stack(base = base, above = null, below = firstArg)
    }
}
