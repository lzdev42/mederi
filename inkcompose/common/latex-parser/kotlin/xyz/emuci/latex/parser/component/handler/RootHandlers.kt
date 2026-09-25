package xyz.emuci.latex.parser.component.handler

import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.parser.tokenizer.LatexToken

/**
 * 根号命令：\sqrt
 */
internal fun CommandRegistry.installRootHandlers() {
    register("sqrt") { _, ctx, stream ->
        val index = if (stream.peek() is LatexToken.LeftBracket) {
            stream.advance() // [
            val indexNodes = ParseUtils.parseUntil(ctx, stream) { it is LatexToken.RightBracket }
            if (!stream.isEOF()) {
                stream.expect("]")
            }
            LatexNode.Group(indexNodes)
        } else {
            null
        }

        val content = ctx.parseArgument() ?: LatexNode.Text("")
        LatexNode.Root(content, index)
    }
}
