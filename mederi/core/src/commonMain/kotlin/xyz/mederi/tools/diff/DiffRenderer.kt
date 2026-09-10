package xyz.mederi.tools.diff

import xyz.mederi.debug.DebugLog

/**
 * Generate a unified diff between two texts.
 *
 * Uses a simple line-level LCS algorithm. O(n*m), suitable for source files.
 * When n*m exceeds [MAX_LCS_CELLS], falls back to a simple replace-all diff to avoid OOM.
 */

private const val MAX_LCS_CELLS = 5_000_000L

internal fun unifiedDiff(
    oldPath: String,
    newPath: String,
    before: String?,
    after: String?,
    contextRadius: Int = 3
): String {
    val oldLines = before?.lines() ?: emptyList()
    val newLines = after?.lines() ?: emptyList()
    if (oldLines == newLines) return ""
    val ops = computeDiffOps(oldPath, oldLines, newLines)
    if (ops.all { it is DiffOp.Equal }) return ""
    return renderUnifiedDiff(oldPath, newPath, oldLines, newLines, ops, contextRadius)
}

internal fun countChanges(before: String?, after: String?): Pair<Int, Int> {
    val oldLines = before?.lines() ?: emptyList()
    val newLines = after?.lines() ?: emptyList()
    val ops = computeDiffOps("countChanges", oldLines, newLines)
    val additions = ops.filterIsInstance<DiffOp.Insert>().sumOf { it.length }
    val deletions = ops.filterIsInstance<DiffOp.Delete>().sumOf { it.length }
    return additions to deletions
}

private sealed class DiffOp {
    data class Equal(val length: Int) : DiffOp()
    data class Delete(val length: Int) : DiffOp()
    data class Insert(val length: Int) : DiffOp()
}

private fun computeDiffOps(label: String, oldLines: List<String>, newLines: List<String>): List<DiffOp> {
    val n = oldLines.size
    val m = newLines.size
    if (n == 0 && m == 0) return emptyList()
    if (n == 0) return listOf(DiffOp.Insert(m))
    if (m == 0) return listOf(DiffOp.Delete(n))

    val cells = n.toLong() * m.toLong()
    if (cells > MAX_LCS_CELLS) {
        DebugLog.event("Diff", "computeDiffOps SKIP LCS for '$label': n=$n, m=$m, cells=$cells > $MAX_LCS_CELLS -> fallback to replace-all")
        return buildList {
            add(DiffOp.Delete(n))
            add(DiffOp.Insert(m))
        }
    }

    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in 1..n) {
        for (j in 1..m) {
            dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) {
                dp[i - 1][j - 1] + 1
            } else {
                maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }

    val ops = mutableListOf<DiffOp>()
    var i = n
    var j = m
    while (i > 0 || j > 0) {
        when {
            i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1] -> {
                var len = 0
                while (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
                    i--
                    j--
                    len++
                }
                ops.add(DiffOp.Equal(len))
            }
            j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                var len = 0
                while (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                    j--
                    len++
                }
                ops.add(DiffOp.Insert(len))
            }
            else -> {
                var len = 0
                while (i > 0 && (j == 0 || dp[i - 1][j] > dp[i][j - 1])) {
                    i--
                    len++
                }
                ops.add(DiffOp.Delete(len))
            }
        }
    }
    return ops.asReversed()
}

private fun renderUnifiedDiff(
    oldPath: String,
    newPath: String,
    oldLines: List<String>,
    newLines: List<String>,
    ops: List<DiffOp>,
    contextRadius: Int
): String {
    val result = StringBuilder()
    result.appendLine("diff --git a/$oldPath b/$newPath")
    when {
        oldLines.isEmpty() -> result.appendLine("new file mode 100644")
        newLines.isEmpty() -> result.appendLine("deleted file mode 100644")
    }

    val hunks = groupIntoHunks(ops, contextRadius)
    for (hunk in hunks) {
        val oldCount = hunk.oldEnd - hunk.oldStart
        val newCount = hunk.newEnd - hunk.newStart
        val oldStart = if (oldCount == 0) hunk.oldStart else hunk.oldStart + 1
        val newStart = if (newCount == 0) hunk.newStart else hunk.newStart + 1
        val oldRange = if (oldCount == 0) "$oldStart,0" else "$oldStart,$oldCount"
        val newRange = if (newCount == 0) "$newStart,0" else "$newStart,$newCount"
        result.appendLine("@@ -$oldRange +$newRange @@")

        var oldIdx = hunk.oldStart
        var newIdx = hunk.newStart
        for (op in hunk.ops) {
            when (op) {
                is DiffOp.Equal -> {
                    for (k in 0 until op.length) {
                        result.appendLine(" ${oldLines[oldIdx + k]}")
                    }
                    oldIdx += op.length
                    newIdx += op.length
                }
                is DiffOp.Delete -> {
                    for (k in 0 until op.length) {
                        result.appendLine("-${oldLines[oldIdx + k]}")
                    }
                    oldIdx += op.length
                }
                is DiffOp.Insert -> {
                    for (k in 0 until op.length) {
                        result.appendLine("+${newLines[newIdx + k]}")
                    }
                    newIdx += op.length
                }
            }
        }
    }
    return result.toString().trimEnd('\n')
}

private data class Hunk(
    val oldStart: Int,
    val oldEnd: Int,
    val newStart: Int,
    val newEnd: Int,
    val ops: List<DiffOp>
)

private fun groupIntoHunks(ops: List<DiffOp>, contextRadius: Int): List<Hunk> {
    if (ops.isEmpty()) return emptyList()

    val changeIndices = mutableListOf<Int>()
    for ((index, op) in ops.withIndex()) {
        if (op !is DiffOp.Equal) changeIndices.add(index)
    }
    if (changeIndices.isEmpty()) return emptyList()

    val ranges = mutableListOf<Pair<Int, Int>>()
    var start = changeIndices.first()
    var end = changeIndices.first()
    for (k in 1 until changeIndices.size) {
        val idx = changeIndices[k]
        if (idx - end <= contextRadius * 2) {
            end = idx
        } else {
            ranges.add(start to end)
            start = idx
            end = idx
        }
    }
    ranges.add(start to end)

    val hunks = mutableListOf<Hunk>()
    for ((rangeStart, rangeEnd) in ranges) {
        val opStart = maxOf(0, rangeStart - contextRadius)
        val opEnd = minOf(ops.size - 1, rangeEnd + contextRadius)
        val hunkOps = ops.subList(opStart, opEnd + 1)

        var oldStart = 0
        var newStart = 0
        for (k in 0 until opStart) {
            when (val op = ops[k]) {
                is DiffOp.Equal -> {
                    oldStart += op.length
                    newStart += op.length
                }
                is DiffOp.Delete -> oldStart += op.length
                is DiffOp.Insert -> newStart += op.length
            }
        }

        var oldEnd = oldStart
        var newEnd = newStart
        for (op in hunkOps) {
            when (op) {
                is DiffOp.Equal -> {
                    oldEnd += op.length
                    newEnd += op.length
                }
                is DiffOp.Delete -> oldEnd += op.length
                is DiffOp.Insert -> newEnd += op.length
            }
        }
        hunks.add(Hunk(oldStart, oldEnd, newStart, newEnd, hunkOps))
    }
    return hunks
}
