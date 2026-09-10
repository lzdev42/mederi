package xyz.emuci.latex.parser.component.handler

import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.parser.tokenizer.LatexToken

/**
 * 章节结构命令：\section, \subsection, \subsubsection, \paragraph, \subparagraph
 *
 * 支持星号变体（\section*{...}）：tokenizer 将 \section 和 * 分为两个 token，
 * 因此在 handler 内部窥探下一个 token 检测是否为星号。
 */
internal fun CommandRegistry.installSectionHandlers() {
    val sectionLevels = mapOf(
        "section" to LatexNode.SectionHeading.HeadingLevel.SECTION,
        "subsection" to LatexNode.SectionHeading.HeadingLevel.SUBSECTION,
        "subsubsection" to LatexNode.SectionHeading.HeadingLevel.SUBSUBSECTION,
        "paragraph" to LatexNode.SectionHeading.HeadingLevel.PARAGRAPH,
        "subparagraph" to LatexNode.SectionHeading.HeadingLevel.SUBPARAGRAPH,
    )

    for ((cmdName, level) in sectionLevels) {
        register(cmdName) { _, ctx, stream ->
            // 检查后续是否为星号 *
            val starred = isNextStar(stream)
            if (starred) {
                stream.advance() // 消耗 * token
            }

            // 解析标题内容 {title}
            val arg = ctx.parseArgument() ?: return@register LatexNode.Text("\\$cmdName")
            val content = when (arg) {
                is LatexNode.Group -> arg.children
                else -> listOf(arg)
            }

            LatexNode.SectionHeading(content, level, starred)
        }
    }
}

/**
 * 检查下一个 token 是否为星号 "*"
 */
private fun isNextStar(stream: xyz.emuci.latex.parser.component.LatexTokenStream): Boolean {
    val next = stream.peek() ?: return false
    return next is LatexToken.Text && next.content == "*"
}
