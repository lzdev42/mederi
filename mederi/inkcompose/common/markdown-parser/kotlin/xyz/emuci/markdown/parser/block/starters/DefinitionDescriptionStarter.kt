package xyz.emuci.markdown.parser.block.starters

import xyz.emuci.markdown.parser.LineRange
import xyz.emuci.markdown.parser.ast.DefinitionDescription
import xyz.emuci.markdown.parser.ast.DefinitionList
import xyz.emuci.markdown.parser.block.OpenBlock
import xyz.emuci.markdown.parser.core.LineCursor

/**
 * 定义描述开启器：`: description text`。
 * 必须在段落（作为术语）或已存在的定义列表之后。
 */
internal class DefinitionDescriptionStarter : BlockStarter {
    override val priority: Int = 520
    override val canInterruptParagraph: Boolean = true

    override fun tryStart(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
        if (tip.paragraphContent == null && tip.node !is DefinitionList) return null

        val snap = cursor.snapshot()
        val indent = cursor.advanceSpaces(3)
        if (cursor.isAtEnd || cursor.peek() != ':') {
            cursor.restore(snap)
            return null
        }
        cursor.advance()
        if (cursor.isAtEnd || (cursor.peek() != ' ' && cursor.peek() != '\t')) {
            cursor.restore(snap)
            return null
        }
        cursor.advance()

        val desc = DefinitionDescription()
        desc.lineRange = LineRange(lineIdx, lineIdx + 1)

        val ob = OpenBlock(desc, contentStartLine = lineIdx, lastLineIndex = lineIdx)
        ob.starterTag = this::class.simpleName
        return ob
    }
}
