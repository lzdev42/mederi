package xyz.emuci.diff.model

/**
 * 单个文件的差异数据模型（核心数据契约）。
 * 完全解耦于算法，仅表示差异结构。
 */
data class DiffFile(
    val oldPath: String? = null,
    val newPath: String? = null,
    val language: String? = null,
    val hunks: List<DiffHunk> = emptyList(),
    val stats: DiffStats = DiffStats.calculate(hunks),
) {
    val displayPath: String
        get() = newPath ?: oldPath ?: "unknown"

    val fileName: String
        get() = displayPath.substringAfterLast('/')
}

/**
 * 差异代码段落（Hunk）。
 */
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val header: String? = null,
    val lines: List<DiffLine> = emptyList(),
)

/**
 * 细粒度行差异模型。
 */
sealed class DiffLine {
    abstract val content: String

    data class Unchanged(
        val oldLineNo: Int,
        val newLineNo: Int,
        override val content: String,
    ) : DiffLine()

    data class Added(
        val newLineNo: Int,
        override val content: String,
    ) : DiffLine()

    data class Deleted(
        val oldLineNo: Int,
        override val content: String,
    ) : DiffLine()
}

/**
 * 代码增删行统计。
 */
data class DiffStats(
    val additions: Int,
    val deletions: Int,
) {
    companion object {
        fun calculate(hunks: List<DiffHunk>): DiffStats {
            var add = 0
            var del = 0
            for (hunk in hunks) {
                for (line in hunk.lines) {
                    when (line) {
                        is DiffLine.Added -> add++
                        is DiffLine.Deleted -> del++
                        is DiffLine.Unchanged -> Unit
                    }
                }
            }
            return DiffStats(add, del)
        }
    }
}
