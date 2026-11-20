package xyz.mederi.plan

import xyz.mederi.tools.diff.ChangedLineRange
import xyz.mederi.tools.diff.changedLineRanges

/** Builds the reader's Markdown footer from two submitted proposal snapshots. */
internal fun renderPlanRevisionMarkdown(previous: String, current: String, reason: String): String {
    val before = ProposalLines(previous)
    val after = ProposalLines(current)
    val changes = mutableListOf<ChangedLineRange>()
    for (range in changedLineRanges(before.lines, after.lines)) {
        val oldBlock = before.withBlockContext(range.before)
        val newBlock = after.withBlockContext(range.after)
        val expanded = ChangedLineRange(
            if (newBlock != range.after && oldBlock.isEmpty()) before.withBlockContext(oldBlock, includeBlockEnd = true)
            else oldBlock,
            if (oldBlock != range.before && newBlock.isEmpty()) after.withBlockContext(newBlock, includeBlockEnd = true)
            else newBlock,
        )
        if (before.lines.slice(expanded.before).all(String::isBlank) &&
            after.lines.slice(expanded.after).all(String::isBlank)) continue
        val last = changes.lastOrNull()
        // Multiple edits inside one table or fence should display that block just once.
        if (last != null && (expanded.before.first <= last.before.last || expanded.after.first <= last.after.last)) {
            changes[changes.lastIndex] = ChangedLineRange(
                minOf(last.before.first, expanded.before.first)..maxOf(last.before.last, expanded.before.last),
                minOf(last.after.first, expanded.after.first)..maxOf(last.after.last, expanded.after.last),
            )
        } else {
            changes.add(expanded)
        }
    }

    return buildString {
        appendLine("## 修订记录")
        if (reason.isNotBlank()) {
            appendLine()
            appendLine("**修订原因**　${escapeMarkdown(reason.lines().joinToString(" ") { it.trim() }.trim())}")
        }
        val sections = changes.groupBy { change ->
            if (change.after.isEmpty()) before.sectionAt(change.before.first) else after.sectionAt(change.after.first)
        }
        for ((section, sectionChanges) in sections) {
            appendLine()
            appendLine("### ${escapeMarkdown(section)}")
            for (change in sectionChanges) {
                val removed = before.lines.slice(change.before).dropWhile(String::isBlank).dropLastWhile(String::isBlank)
                val added = after.lines.slice(change.after).dropWhile(String::isBlank).dropLastWhile(String::isBlank)
                val oldLine = removed.singleOrNull()?.takeIf(::isInlineChange)
                val newLine = added.singleOrNull()?.takeIf(::isInlineChange)
                when {
                    oldLine != null && newLine != null ->
                        appendLine("- ~~${escapeMarkdown(oldLine.trim())}~~ → **${escapeMarkdown(newLine.trim())}**")
                    oldLine != null && added.isEmpty() ->
                        appendLine("- **删除**　~~${escapeMarkdown(oldLine.trim())}~~")
                    newLine != null && removed.isEmpty() ->
                        appendLine("- **新增**　${escapeMarkdown(newLine.trim())}")
                    else -> {
                        appendQuotedChange(if (added.isEmpty()) "删除" else "修改前", removed)
                        appendQuotedChange(if (removed.isEmpty()) "新增" else "修改后", added)
                    }
                }
            }
        }
        if (changes.isEmpty()) {
            appendLine()
            appendLine("方案正文未变化。")
        }
    }.trimEnd()
}

private val headingPattern = Regex("""^ {0,3}(#{1,6})[ \t]+(.+?)\s*#*\s*$""")
private val fencePattern = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
private val tableCellSeparatorPattern = Regex(""":?-+:?""")

private class ProposalLines(markdown: String) {
    val lines = markdown.lines()
    private val headings = mutableListOf<Pair<Int, String>>()
    private val structuredBlocks = mutableListOf<IntRange>()

    init {
        val headingPath = mutableListOf<Pair<Int, String>>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val fence = fencePattern.matchEntire(line)?.groupValues?.get(1)
            if (fence != null) {
                val closing = lines.subList(index + 1, lines.size).indexOfFirst { candidate ->
                    val marker = candidate.trim()
                    marker.length >= fence.length && marker.all { it == fence.first() }
                }
                val end = if (closing == -1) lines.size else index + closing + 2
                structuredBlocks.add(index until end)
                index = end
                continue
            }
            if (line.contains('|') && lines.getOrNull(index + 1)?.let(::isTableSeparator) == true) {
                val start = index
                index += 2
                while (index < lines.size && lines[index].contains('|')) index++
                structuredBlocks.add(start until index)
                continue
            }
            headingPattern.matchEntire(line)?.let { heading ->
                val depth = heading.groupValues[1].length
                while (headingPath.lastOrNull()?.first?.let { it >= depth } == true) {
                    headingPath.removeAt(headingPath.lastIndex)
                }
                headingPath.add(depth to heading.groupValues[2])
                val path = headingPath.filter { it.first > 1 }.ifEmpty { headingPath }
                headings.add(index to path.joinToString(" / ") { it.second })
            }
            index++
        }
    }

    fun sectionAt(index: Int): String = headings.lastOrNull { it.first <= index }?.second ?: "计划正文"

    fun withBlockContext(range: IntRange, includeBlockEnd: Boolean = false): IntRange {
        var start = range.first
        var end = range.last
        for (block in structuredBlocks) {
            val intersects = if (range.isEmpty()) {
                start > block.first && (start <= block.last || (includeBlockEnd && start == block.last + 1))
            } else {
                start <= block.last && end >= block.first
            }
            if (intersects) {
                start = minOf(start, block.first)
                end = maxOf(end, block.last)
            }
        }
        return start..end
    }
}

private fun isTableSeparator(line: String): Boolean =
    line.contains('|') && line.trim().trim('|').split('|')
        .all { tableCellSeparatorPattern.matches(it.trim()) }

private fun isInlineChange(line: String): Boolean =
    !line.startsWith("    ") && !line.startsWith('\t') && !line.contains('|') &&
        !line.trimStart().startsWith('>') && !headingPattern.matches(line) && !fencePattern.matches(line)

private fun escapeMarkdown(text: String): String = buildString {
    for (char in text) {
        if (char in "\\`*_{}[]()#+-.!|><~&$") append('\\')
        append(char)
    }
}

private fun StringBuilder.appendQuotedChange(label: String, lines: List<String>) {
    if (lines.isEmpty()) return
    appendLine()
    appendLine("**$label**")
    appendLine()
    for (line in lines) {
        appendLine(if (line.isEmpty()) ">" else "> $line")
    }
    appendLine()
}
