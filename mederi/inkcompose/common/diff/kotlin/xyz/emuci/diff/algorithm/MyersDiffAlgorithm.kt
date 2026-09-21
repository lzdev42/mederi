package xyz.emuci.diff.algorithm

import xyz.emuci.diff.model.DiffFile
import xyz.emuci.diff.model.DiffHunk
import xyz.emuci.diff.model.DiffLine
import xyz.emuci.diff.parser.UnifiedDiffParser

/**
 * 纯 Kotlin 实现的 Myers 差异比对算法。
 * 零外部依赖，跨平台安全。
 */
object MyersDiffAlgorithm {

    private const val MAX_DIFF_CELLS = 4_000_000L

    /**
     * 对比两个文本，产出标准的 DiffFile。
     *
     * @param oldText 原始文本
     * @param newText 变更后文本
     * @param contextRadius 上下文保留行数，默认 3
     * @param filePath 可选文件路径，用于推断语言和展示
     */
    fun computeDiff(
        oldText: String,
        newText: String,
        contextRadius: Int = 3,
        filePath: String? = null,
    ): DiffFile {
        val oldLines = if (oldText.isEmpty()) emptyList() else oldText.lines()
        val newLines = if (newText.isEmpty()) emptyList() else newText.lines()

        if (oldLines.isEmpty() && newLines.isEmpty()) {
            return DiffFile(
                oldPath = filePath,
                newPath = filePath,
                language = UnifiedDiffParser.detectLanguage(filePath),
                hunks = emptyList()
            )
        }

        val allDiffLines = computeDiffLines(oldLines, newLines)
        val hunks = buildHunks(allDiffLines, contextRadius)

        return DiffFile(
            oldPath = filePath,
            newPath = filePath,
            language = UnifiedDiffParser.detectLanguage(filePath),
            hunks = hunks
        )
    }

    /**
     * 计算所有行的差异操作，带准确的旧/新行号。
     */
    fun computeDiffLines(oldLines: List<String>, newLines: List<String>): List<DiffLine> {
        val n = oldLines.size
        val m = newLines.size

        if (n == 0) {
            return newLines.mapIndexed { idx, text -> DiffLine.Added(newLineNo = idx + 1, content = text) }
        }
        if (m == 0) {
            return oldLines.mapIndexed { idx, text -> DiffLine.Deleted(oldLineNo = idx + 1, content = text) }
        }

        // 大文件保护，防止极端 OOM
        if (n.toLong() * m.toLong() > MAX_DIFF_CELLS) {
            val result = ArrayList<DiffLine>(n + m)
            for (i in 0 until n) {
                result.add(DiffLine.Deleted(oldLineNo = i + 1, content = oldLines[i]))
            }
            for (j in 0 until m) {
                result.add(DiffLine.Added(newLineNo = j + 1, content = newLines[j]))
            }
            return result
        }

        val editScript = myersDiff(oldLines, newLines)
        val result = ArrayList<DiffLine>(editScript.size)

        var curOld = 1
        var curNew = 1

        for (op in editScript) {
            when (op.type) {
                OpType.KEEP -> {
                    result.add(DiffLine.Unchanged(oldLineNo = curOld++, newLineNo = curNew++, content = op.text))
                }
                OpType.INSERT -> {
                    result.add(DiffLine.Added(newLineNo = curNew++, content = op.text))
                }
                OpType.DELETE -> {
                    result.add(DiffLine.Deleted(oldLineNo = curOld++, content = op.text))
                }
            }
        }

        return result
    }

    private enum class OpType { KEEP, INSERT, DELETE }
    private data class DiffOp(val type: OpType, val text: String)

    /**
     * 标准 Myers 差异算法。
     */
    private fun myersDiff(a: List<String>, b: List<String>): List<DiffOp> {
        val n = a.size
        val m = b.size
        val max = n + m
        val v = IntArray(2 * max + 1)
        val trace = ArrayList<IntArray>()

        var foundD = -1
        for (d in 0..max) {
            // KMP 兼容：IntArray.clone() 仅 JVM 可用（wasmJs 无此方法），用 copyOf()
            trace.add(v.copyOf())
            var k = -d
            while (k <= d) {
                val kIdx = k + max
                var x = if (k == -d || (k != d && v[kIdx - 1] < v[kIdx + 1])) {
                    v[kIdx + 1]
                } else {
                    v[kIdx - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[kIdx] = x
                if (x >= n && y >= m) {
                    foundD = d
                    break
                }
                k += 2
            }
            if (foundD != -1) break
        }

        // 回溯构造编辑脚本
        val ops = ArrayList<DiffOp>()
        var x = n
        var y = m

        for (d in trace.indices.reversed()) {
            val vSnapshot = trace[d]
            val k = x - y
            val kIdx = k + max

            val prevK = if (k == -d || (k != d && vSnapshot[kIdx - 1] < vSnapshot[kIdx + 1])) {
                k + 1
            } else {
                k - 1
            }
            val prevX = vSnapshot[prevK + max]
            val prevY = prevX - prevK

            while (x > prevX && y > prevY) {
                ops.add(DiffOp(OpType.KEEP, a[x - 1]))
                x--
                y--
            }

            if (d > 0) {
                if (x == prevX) {
                    ops.add(DiffOp(OpType.INSERT, b[y - 1]))
                    y--
                } else {
                    ops.add(DiffOp(OpType.DELETE, a[x - 1]))
                    x--
                }
            }
        }

        ops.reverse()
        return ops
    }

    /**
     * 将带有增删的行列表按 contextRadius 聚合成 Hunk。
     */
    private fun buildHunks(lines: List<DiffLine>, contextRadius: Int): List<DiffHunk> {
        val changeIndices = mutableListOf<Int>()
        for (i in lines.indices) {
            if (lines[i] !is DiffLine.Unchanged) {
                changeIndices.add(i)
            }
        }

        if (changeIndices.isEmpty()) return emptyList()

        // 按间隔是否超过 2 * contextRadius 划分 Hunk 范围
        val hunkRanges = mutableListOf<IntRange>()
        var rangeStart = maxOf(0, changeIndices[0] - contextRadius)
        var rangeEnd = minOf(lines.lastIndex, changeIndices[0] + contextRadius)

        for (i in 1 until changeIndices.size) {
            val changeIdx = changeIndices[i]
            val nextStart = maxOf(0, changeIdx - contextRadius)
            val nextEnd = minOf(lines.lastIndex, changeIdx + contextRadius)

            if (nextStart <= rangeEnd + 1) {
                // 重合或相邻，合并
                rangeEnd = maxOf(rangeEnd, nextEnd)
            } else {
                hunkRanges.add(rangeStart..rangeEnd)
                rangeStart = nextStart
                rangeEnd = nextEnd
            }
        }
        hunkRanges.add(rangeStart..rangeEnd)

        return hunkRanges.map { range ->
            val subLines = lines.subList(range.first, range.last + 1)
            val oldStart = subLines.firstNotNullOfOrNull {
                when (it) {
                    is DiffLine.Unchanged -> it.oldLineNo
                    is DiffLine.Deleted -> it.oldLineNo
                    is DiffLine.Added -> null
                }
            } ?: 1
            val newStart = subLines.firstNotNullOfOrNull {
                when (it) {
                    is DiffLine.Unchanged -> it.newLineNo
                    is DiffLine.Added -> it.newLineNo
                    is DiffLine.Deleted -> null
                }
            } ?: 1

            val oldCount = subLines.count { it is DiffLine.Unchanged || it is DiffLine.Deleted }
            val newCount = subLines.count { it is DiffLine.Unchanged || it is DiffLine.Added }

            DiffHunk(
                oldStart = oldStart,
                oldCount = oldCount,
                newStart = newStart,
                newCount = newCount,
                header = "@@ -$oldStart,$oldCount +$newStart,$newCount @@",
                lines = subLines
            )
        }
    }
}
