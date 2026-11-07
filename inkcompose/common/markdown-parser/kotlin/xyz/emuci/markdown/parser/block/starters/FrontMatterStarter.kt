package xyz.emuci.markdown.parser.block.starters

import xyz.emuci.markdown.parser.LineRange
import xyz.emuci.markdown.parser.ast.FrontMatter
import xyz.emuci.markdown.parser.block.OpenBlock
import xyz.emuci.markdown.parser.core.LineCursor

/**
 * 前置元数据块开启器：`---` (YAML) 或 `+++` (TOML)。
 * 仅在文档第一行生效。
 *
 * 无状态设计：不依赖 SourceText，关闭标记由 [BlockParser] 的 addLineToTip 检测。
 * [BlockParser] 确认解析范围内有闭合标记才接纳此块，否则从第一行按普通 Markdown 解析。
 */
class FrontMatterStarter : BlockStarter {
    override val priority: Int = 10
    override val canInterruptParagraph: Boolean = false

    override fun tryStart(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
        if (lineIdx != 0) return null
        val rest = cursor.rest().trim()
        val format = when {
            rest == "---" -> "yaml"
            rest == "+++" -> "toml"
            else -> return null
        }

        val block = FrontMatter(format = format)
        block.lineRange = LineRange(lineIdx, lineIdx + 1)
        val ob = OpenBlock(block, contentStartLine = lineIdx, lastLineIndex = lineIdx)
        ob.starterTag = this::class.simpleName
        return ob
    }
}
