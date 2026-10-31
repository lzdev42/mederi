// Portions adapted from openai/codex (Apache-2.0), commit 23934d31b15b1127c83535f84854e5009f7cd51d.
package xyz.mederi.tools.patch

/**
 * 补丁应用核心算法（对照 Codex `apply-patch/src/seek_sequence.rs` 与 `file_update.rs`）。
 *
 * 保留 LF / CRLF / CR 及混合行尾；上下文原样保留，新行采用源文件第一种行尾。
 */
object PatchApplier {

    /**
     * 在 [lines] 中从 [start] 起查找 [pattern] 的连续出现，返回命中起点（找不到返回 null）。
     *
     * 四级匹配逐级放宽（从严到宽）：
     * 1. 精确匹配；
     * 2. 忽略行尾空白（trimEnd）；
     * 3. 忽略首尾空白（trim）；
     * 4. Unicode 标点归一化（弯引号/各种连字符/特殊空格 → ASCII）后再 trim 比较。
     *
     * [eof] 为 true 时只能命中文件末尾，且不得回退到 [start] 之前。
     *
     * 防御：空 pattern 返回 [start]（no-op）；pattern 比 lines 长返回 null。
     */
    fun seekSequence(lines: List<String>, pattern: List<String>, start: Int, eof: Boolean): Int? {
        if (start < 0) return null
        if (pattern.isEmpty()) return start.takeIf { it <= lines.size }
        if (pattern.size > lines.size) return null

        val searchStart = if (eof) maxOf(start, lines.size - pattern.size) else start
        val lastStart = lines.size - pattern.size
        if (searchStart > lastStart) return null

        // 1. 精确匹配
        for (i in searchStart..lastStart) {
            if (lines.subList(i, i + pattern.size) == pattern) return i
        }
        // 2. 忽略行尾空白
        for (i in searchStart..lastStart) {
            var ok = true
            for (p in pattern.indices) {
                if (lines[i + p].trimEnd() != pattern[p].trimEnd()) {
                    ok = false
                    break
                }
            }
            if (ok) return i
        }
        // 3. 忽略首尾空白
        for (i in searchStart..lastStart) {
            var ok = true
            for (p in pattern.indices) {
                if (lines[i + p].trim() != pattern[p].trim()) {
                    ok = false
                    break
                }
            }
            if (ok) return i
        }
        // 4. Unicode 标点归一化
        for (i in searchStart..lastStart) {
            var ok = true
            for (p in pattern.indices) {
                if (normalise(lines[i + p]) != normalise(pattern[p])) {
                    ok = false
                    break
                }
            }
            if (ok) return i
        }
        return null
    }

    /** 把常见 Unicode 标点/空格归一化为 ASCII 等价物（映射表照抄 Codex）。 */
    private fun normalise(s: String): String = buildString {
        for (c in s.trim()) {
            append(
                when (c) {
                    // 各种 dash/hyphen → '-'
                    '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-'
                    // 花式单引号 → '\''
                    '\u2018', '\u2019', '\u201A', '\u201B' -> '\''
                    // 花式双引号 → '"'
                    '\u201C', '\u201D', '\u201E', '\u201F' -> '"'
                    // NBSP 及各种特殊空格 → 普通空格
                    '\u00A0', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006',
                    '\u2007', '\u2008', '\u2009', '\u200A', '\u202F', '\u205F', '\u3000' -> ' '
                    else -> c
                },
            )
        }
    }

    /** 单处替换：(起始行号, 删除行数, 新行)。 */
    data class Replacement(val startIndex: Int, val oldLength: Int, val newLines: List<String>)

    /**
     * 依据 [chunks] 计算把 [originalLines] 变换为新内容所需的替换列表。
     *
     * 逐 chunk 顺序定位（游标只增不减）：
     * - [UpdateChunk.changeContext] 非空时先用单行 pattern 定位，游标推进到命中行之后；
     * - oldLines 为空（纯新增）→ 在文件末尾插入；
     * - 否则用 [seekSequence] 定位 oldLines 的连续出现；找不到且 oldLines 末位是空串
     *   （末尾换行哨兵）时截断末位重试。
     *
     * @throws PatchApplyException 定位失败（含上下文/oldLines 找不到）。
     */
    fun computeReplacements(originalLines: List<String>, path: String, chunks: List<UpdateChunk>): List<Replacement> {
        val replacements = mutableListOf<Replacement>()
        var lineIndex = 0

        for (chunk in chunks) {
            chunk.changeContext?.let { ctx ->
                val idx = seekSequence(originalLines, listOf(ctx), lineIndex, eof = false)
                if (idx != null) {
                    lineIndex = idx + 1
                } else {
                    throw PatchApplyException("Failed to find context '$ctx' in $path")
                }
            }

            if (chunk.oldLines.isEmpty()) {
                // Source lines store terminators separately; an actual final blank line is data.
                replacements.add(Replacement(originalLines.size, 0, chunk.newLines))
                continue
            }

            var pattern = chunk.oldLines
            var newSlice = chunk.newLines
            var found = seekSequence(originalLines, pattern, lineIndex, chunk.isEndOfFile)

            // 末位空串代表末尾换行哨兵，直接匹配失败时截断重试
            if (found == null && pattern.isNotEmpty() && pattern.last().isEmpty()) {
                pattern = pattern.dropLast(1)
                if (newSlice.isNotEmpty() && newSlice.last().isEmpty()) {
                    newSlice = newSlice.dropLast(1)
                }
                found = seekSequence(originalLines, pattern, lineIndex, chunk.isEndOfFile)
            }

            if (found != null) {
                // Context was parsed explicitly, not inferred by comparing equal text.
                // A fuzzy match must never rewrite those lines or their line endings.
                var oldStart = 0
                var newStart = 0
                for ((oldContext, newContext) in chunk.contextLineIndices) {
                    if (oldContext >= pattern.size || newContext >= newSlice.size) break
                    if (oldStart != oldContext || newStart != newContext) {
                        replacements.add(Replacement(found + oldStart, oldContext - oldStart,
                            newSlice.subList(newStart, newContext)))
                    }
                    oldStart = oldContext + 1
                    newStart = newContext + 1
                }
                if (oldStart != pattern.size || newStart != newSlice.size) {
                    replacements.add(Replacement(found + oldStart, pattern.size - oldStart,
                        newSlice.subList(newStart, newSlice.size)))
                }
                lineIndex = found + pattern.size
            } else {
                throw PatchApplyException(
                    "Failed to find expected lines in $path:\n${chunk.oldLines.joinToString("\n")}",
                )
            }
        }

        return replacements.sortedBy { it.startIndex }
    }

    /** 把替换列表应用到行列表（倒序应用，避免前面的替换使后面的位移）。 */
    fun applyReplacements(lines: MutableList<String>, replacements: List<Replacement>): MutableList<String> {
        for (r in replacements.asReversed()) {
            repeat(r.oldLength) {
                if (r.startIndex < lines.size) lines.removeAt(r.startIndex)
            }
            r.newLines.forEachIndexed { offset, newLine ->
                lines.add(r.startIndex + offset, newLine)
            }
        }
        return lines
    }

    /** Derive the complete updated UTF-8 text without filesystem effects. */
    fun deriveNewContents(originalContents: String, path: String, chunks: List<UpdateChunk>): String {
        val source = SourceFile.parse(originalContents)
        val replacements = computeReplacements(source.lines.map { it.text }, path, chunks)
        return source.apply(replacements)
    }

    private data class SourceLine(val text: String, val ending: String?)

    /** Retain terminators per source line; a trailing newline is not an extra logical line. */
    private class SourceFile(val lines: List<SourceLine>, private val preferredEnding: String) {
        fun apply(replacements: List<Replacement>): String = buildString {
            var sourceIndex = 0
            for (replacement in replacements) {
                if (replacement.startIndex < sourceIndex ||
                    replacement.startIndex > lines.size ||
                    replacement.oldLength < 0 ||
                    replacement.startIndex + replacement.oldLength > lines.size) {
                    throw PatchApplyException("Overlapping or invalid update chunks")
                }
                for (index in sourceIndex until replacement.startIndex) {
                    append(lines[index].text).append(lines[index].ending ?: preferredEnding)
                }
                for (text in replacement.newLines) append(text).append(preferredEnding)
                sourceIndex = replacement.startIndex + replacement.oldLength
            }
            for (index in sourceIndex until lines.size) {
                append(lines[index].text).append(lines[index].ending ?: preferredEnding)
            }
        }

        companion object {
            fun parse(contents: String): SourceFile {
                val lines = mutableListOf<SourceLine>()
                var preferredEnding: String? = null
                var lineStart = 0
                var cursor = 0
                while (cursor < contents.length) {
                    val ending = when (contents[cursor]) {
                        '\r' -> if (contents.getOrNull(cursor + 1) == '\n') "\r\n" else "\r"
                        '\n' -> "\n"
                        else -> { cursor++; continue }
                    }
                    if (preferredEnding == null) preferredEnding = ending
                    lines.add(SourceLine(contents.substring(lineStart, cursor), ending))
                    cursor += ending.length
                    lineStart = cursor
                }
                if (lineStart < contents.length) lines.add(SourceLine(contents.substring(lineStart), null))
                return SourceFile(lines, preferredEnding ?: "\n")
            }
        }
    }
}

/** 补丁应用失败（校验或写盘阶段）。 */
class PatchApplyException(message: String) : Exception(message)
