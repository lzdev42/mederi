package xyz.emuci.markdown.parser.block.starters

import xyz.emuci.markdown.parser.LineRange
import xyz.emuci.markdown.parser.ast.FootnoteDefinition
import xyz.emuci.markdown.parser.block.OpenBlock
import xyz.emuci.markdown.parser.core.LineCursor

/**
 * 脚注定义开启器：`[^label]: content`。
 */
internal class FootnoteDefinitionStarter : BlockStarter {
    override val priority: Int = 510
    override val canInterruptParagraph: Boolean = false

    override fun tryStart(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
        val indent = cursor.advanceSpaces(3)
        if (cursor.isAtEnd || cursor.peek() != '[') return null
        cursor.advance()
        if (cursor.isAtEnd || cursor.peek() != '^') return null
        cursor.advance()

        val label = StringBuilder()
        while (!cursor.isAtEnd && cursor.peek() != ']') {
            label.append(cursor.advance())
        }
        if (cursor.isAtEnd || label.isEmpty()) return null
        cursor.advance() // skip ']'
        if (cursor.isAtEnd || cursor.peek() != ':') return null
        cursor.advance() // skip ':'

        if (!cursor.isAtEnd && cursor.peek() == ' ') cursor.advance()

        val fd = FootnoteDefinition(label = label.toString())
        fd.lineRange = LineRange(lineIdx, lineIdx + 1)

        val ob = OpenBlock(fd, contentStartLine = lineIdx, lastLineIndex = lineIdx)
        ob.starterTag = this::class.simpleName
        return ob
    }
}
