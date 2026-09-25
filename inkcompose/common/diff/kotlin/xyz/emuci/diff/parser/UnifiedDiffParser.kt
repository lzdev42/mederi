package xyz.emuci.diff.parser

import xyz.emuci.diff.model.DiffFile
import xyz.emuci.diff.model.DiffHunk
import xyz.emuci.diff.model.DiffLine

/**
 * 标准 Git Patch / Unified Diff 格式解析器。
 * 纯 Kotlin 逻辑，零外部依赖。
 */
object UnifiedDiffParser {

    private val HUNK_HEADER_REGEX = Regex("""^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@(.*)$""")

    /**
     * 解析包含单个或多个文件差异的 Unified Diff 文本。
     */
    fun parse(diffText: String): List<DiffFile> {
        if (diffText.isBlank()) return emptyList()

        val lines = diffText.lines()
        val files = mutableListOf<DiffFile>()

        var currentOldPath: String? = null
        var currentNewPath: String? = null
        val currentHunks = mutableListOf<DiffHunk>()

        var currentHunkOldStart = 0
        var currentHunkOldCount = 0
        var currentHunkNewStart = 0
        var currentHunkNewCount = 0
        var currentHunkHeader: String? = null
        var currentHunkLines = mutableListOf<DiffLine>()

        var curOldLineNo = 0
        var curNewLineNo = 0
        var inHunk = false

        fun flushHunk() {
            if (inHunk && currentHunkLines.isNotEmpty()) {
                currentHunks.add(
                    DiffHunk(
                        oldStart = currentHunkOldStart,
                        oldCount = currentHunkOldCount,
                        newStart = currentHunkNewStart,
                        newCount = currentHunkNewCount,
                        header = currentHunkHeader,
                        lines = currentHunkLines.toList()
                    )
                )
                currentHunkLines = mutableListOf()
                inHunk = false
            }
        }

        fun flushFile() {
            flushHunk()
            if (currentHunks.isNotEmpty() || currentOldPath != null || currentNewPath != null) {
                val path = currentNewPath ?: currentOldPath
                files.add(
                    DiffFile(
                        oldPath = currentOldPath,
                        newPath = currentNewPath,
                        language = detectLanguage(path),
                        hunks = currentHunks.toList()
                    )
                )
                currentOldPath = null
                currentNewPath = null
                currentHunks.clear()
            }
        }

        for (line in lines) {
            when {
                line.startsWith("diff --git ") -> {
                    flushFile()
                    val parts = line.removePrefix("diff --git ").trim().split(" ")
                    if (parts.size >= 2) {
                        currentOldPath = cleanPathPrefix(parts[0])
                        currentNewPath = cleanPathPrefix(parts[1])
                    }
                }

                line.startsWith("--- ") -> {
                    if (!inHunk) {
                        currentOldPath = cleanPathPrefix(line.removePrefix("--- ").trim())
                    }
                }

                line.startsWith("+++ ") -> {
                    if (!inHunk) {
                        currentNewPath = cleanPathPrefix(line.removePrefix("+++ ").trim())
                    }
                }

                line.startsWith("@@ ") || line.startsWith("@@\t") || (line.startsWith("@@") && line.indexOf("@@", startIndex = 2) != -1) -> {
                    flushHunk()
                    val match = HUNK_HEADER_REGEX.find(line.trim())
                    if (match != null) {
                        val (oldStartStr, oldCountStr, newStartStr, newCountStr, headerText) = match.destructured
                        currentHunkOldStart = oldStartStr.toIntOrNull() ?: 1
                        currentHunkOldCount = oldCountStr.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
                        currentHunkNewStart = newStartStr.toIntOrNull() ?: 1
                        currentHunkNewCount = newCountStr.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
                        currentHunkHeader = headerText.trim().ifEmpty { null }

                        curOldLineNo = currentHunkOldStart
                        curNewLineNo = currentHunkNewStart
                        inHunk = true
                    }
                }

                inHunk -> {
                    when {
                        line.startsWith("+") -> {
                            val content = line.substring(1)
                            currentHunkLines.add(DiffLine.Added(newLineNo = curNewLineNo, content = content))
                            curNewLineNo++
                        }

                        line.startsWith("-") -> {
                            val content = line.substring(1)
                            currentHunkLines.add(DiffLine.Deleted(oldLineNo = curOldLineNo, content = content))
                            curOldLineNo++
                        }

                        line.startsWith(" ") -> {
                            val content = line.substring(1)
                            currentHunkLines.add(
                                DiffLine.Unchanged(
                                    oldLineNo = curOldLineNo,
                                    newLineNo = curNewLineNo,
                                    content = content
                                )
                            )
                            curOldLineNo++
                            curNewLineNo++
                        }

                        line.startsWith("\\ No newline at end of file") -> {
                            // 忽略 Git 的无换行符警告
                        }

                        else -> {
                            // 兼容一些没有前置空格的未修改行
                            currentHunkLines.add(
                                DiffLine.Unchanged(
                                    oldLineNo = curOldLineNo,
                                    newLineNo = curNewLineNo,
                                    content = line
                                )
                            )
                            curOldLineNo++
                            curNewLineNo++
                        }
                    }
                }
            }
        }

        flushFile()
        return files
    }

    private fun cleanPathPrefix(rawPath: String): String {
        var path = rawPath.removeSurrounding("\"")
        if (path.startsWith("a/") || path.startsWith("b/")) {
            path = path.substring(2)
        }
        if (path == "/dev/null") return ""
        return path
    }

    fun detectLanguage(filePath: String?): String? {
        if (filePath == null) return null
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> "kotlin"
            "xml" -> "xml"
            "java" -> "java"
            "js", "mjs", "cjs" -> "javascript"
            "ts", "mts", "cts" -> "typescript"
            "py" -> "python"
            "rs" -> "rust"
            "go" -> "go"
            "cpp", "cc", "cxx", "h", "hpp" -> "cpp"
            "c" -> "c"
            "json" -> "json"
            "md", "markdown" -> "markdown"
            "html", "htm" -> "html"
            "css" -> "css"
            "scss", "sass" -> "scss"
            "sql" -> "sql"
            "sh", "bash", "zsh" -> "bash"
            "yaml", "yml" -> "yaml"
            "toml" -> "toml"
            "swift" -> "swift"
            "dart" -> "dart"
            "rb" -> "ruby"
            "php" -> "php"
            else -> null
        }
    }
}
