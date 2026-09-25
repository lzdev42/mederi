package xyz.mederi.tools.patch

/**
 * apply_patch 补丁解析器。
 *
 * 将一段自由格式的补丁文本解析为 [PatchHunk] 列表（对照 Codex `apply-patch/src/parser.rs` 与 `streaming_parser.rs`）。
 *
 * 补丁格式：
 * ```text
 * *** Begin Patch
 * *** Add File: path/to/new.txt
 * +line1
 * *** Delete File: path/to/old.txt
 * *** Update File: path/to/a.txt
 * *** Move to: path/to/b.txt      （可选：移动/重命名）
 * @@ def foo():                    （可选：单行定位上下文）
 *  context line                   （上下文行，以空格开头）
 * -removed line                    （删除行）
 * +added line                      （新增行）
 * *** End of File                  （可选：本 chunk 必须命中文件末尾）
 * *** End Patch
 * ```
 */
object PatchParser {

    private const val BEGIN_PATCH_MARKER = "*** Begin Patch"
    private const val END_PATCH_MARKER = "*** End Patch"
    private const val ADD_FILE_MARKER = "*** Add File: "
    private const val DELETE_FILE_MARKER = "*** Delete File: "
    private const val UPDATE_FILE_MARKER = "*** Update File: "
    private const val MOVE_TO_MARKER = "*** Move to: "
    private const val EOF_MARKER = "*** End of File"
    private const val CHANGE_CONTEXT_MARKER = "@@ "
    private const val EMPTY_CHANGE_CONTEXT_MARKER = "@@"

    private const val NOT_A_HUNK_HEADER_MSG =
        "'%s' is not a valid hunk header. Valid hunk headers: '*** Add File: {path}', '*** Delete File: {path}', '*** Update File: {path}'"
    private const val UNEXPECTED_UPDATE_LINE_MSG =
        "Unexpected line found in update hunk: '%s'. Every line should start with ' ' (context line), '+' (added line), or '-' (removed line)"
    private const val EXPECTED_CONTEXT_MARKER_MSG =
        "Expected update hunk to start with a @@ context marker, got: '%s'"

    /** 解析补丁文本为 hunk 列表。失败抛 [PatchParseException]（带行号）。 */
    fun parse(patch: String): List<PatchHunk> {
        val lines = patch.trim().lines()
        val patchLines = checkBoundaries(lines)
        return PatchLineParser(patchLines).parseAll()
    }

    /**
     * 校验补丁首尾标记。先按严格模式校验；失败则尝试剥离 heredoc 包裹
     * （`<<EOF` / `<<'EOF'` / `<<"EOF"` … `EOF`，兼容模型偶尔的 heredoc 输出）后重试。
     */
    private fun checkBoundaries(lines: List<String>): List<String> {
        if (hasValidBoundaries(lines)) return lines
        if (lines.size >= 4) {
            val first = lines.first()
            val last = lines.last()
            if ((first == "<<EOF" || first == "<<'EOF'" || first == "<<\"EOF\"") && last.endsWith("EOF")) {
                val inner = lines.subList(1, lines.size - 1)
                if (hasValidBoundaries(inner)) return inner
            }
        }
        throw boundariesError(lines)
    }

    private fun hasValidBoundaries(lines: List<String>): Boolean {
        if (lines.isEmpty()) return false
        return lines.first().trim() == BEGIN_PATCH_MARKER && lines.last().trim() == END_PATCH_MARKER
    }

    private fun boundariesError(lines: List<String>): PatchParseException {
        if (lines.isEmpty() || lines.first().trim() != BEGIN_PATCH_MARKER) {
            throw PatchParseException.InvalidPatch("The first line of the patch must be '*** Begin Patch'")
        }
        throw PatchParseException.InvalidPatch("The last line of the patch must be '*** End Patch'")
    }

    /** 逐行状态机解析（对照 Codex `StreamingPatchParser`，一次性解析非流式）。 */
    private class PatchLineParser(private val lines: List<String>) {

        private enum class Mode { NOT_STARTED, STARTED_PATCH, ADD_FILE, DELETE_FILE, UPDATE_FILE, ENDED_PATCH }

        private var mode = Mode.NOT_STARTED
        private var lineNumber = 0
        private var updateHunkLineNumber = 0
        private val hunks = mutableListOf<MutableHunk>()

        fun parseAll(): List<PatchHunk> {
            for (line in lines) {
                lineNumber += 1
                processLine(line)
            }
            if (mode != Mode.ENDED_PATCH) {
                throw PatchParseException.InvalidPatch("The last line of the patch must be '*** End Patch'")
            }
            return hunks.map { it.toHunk() }
        }

        private fun processLine(line: String) {
            val trimmed = line.trim()
            when (mode) {
                Mode.NOT_STARTED -> {
                    if (trimmed == BEGIN_PATCH_MARKER) {
                        mode = Mode.STARTED_PATCH
                    } else {
                        throw PatchParseException.InvalidPatch("The first line of the patch must be '*** Begin Patch'")
                    }
                }
                Mode.STARTED_PATCH -> {
                    if (!handleHunkHeadersAndEndPatch(trimmed)) {
                        throw PatchParseException.InvalidHunk(NOT_A_HUNK_HEADER_MSG.format(trimmed), lineNumber)
                    }
                }
                Mode.ADD_FILE -> {
                    if (handleHunkHeadersAndEndPatch(trimmed)) return
                    if (line.startsWith("+")) {
                        (hunks.last() as MutableHunk.AddFile).contents.append(line.substring(1)).append('\n')
                    } else {
                        throw PatchParseException.InvalidHunk(NOT_A_HUNK_HEADER_MSG.format(trimmed), lineNumber)
                    }
                }
                Mode.DELETE_FILE -> {
                    if (!handleHunkHeadersAndEndPatch(trimmed)) {
                        throw PatchParseException.InvalidHunk(NOT_A_HUNK_HEADER_MSG.format(trimmed), lineNumber)
                    }
                }
                Mode.UPDATE_FILE -> processUpdateLine(line)
                Mode.ENDED_PATCH -> {
                    if (trimmed.isNotEmpty()) {
                        throw PatchParseException.InvalidPatch("The last line of the patch must be '*** End Patch'")
                    }
                }
            }
        }

        /** 处理 hunk 头（Add/Delete/Update）与 End Patch 标记。返回 true 表示该行已被消费。 */
        private fun handleHunkHeadersAndEndPatch(trimmed: String): Boolean {
            if (trimmed == END_PATCH_MARKER) {
                ensureUpdateHunkNotEmpty(trimmed)
                mode = Mode.ENDED_PATCH
                return true
            }
            if (trimmed.startsWith(ADD_FILE_MARKER)) {
                ensureUpdateHunkNotEmpty(trimmed)
                hunks.add(MutableHunk.AddFile(trimmed.removePrefix(ADD_FILE_MARKER)))
                mode = Mode.ADD_FILE
                return true
            }
            if (trimmed.startsWith(DELETE_FILE_MARKER)) {
                ensureUpdateHunkNotEmpty(trimmed)
                hunks.add(MutableHunk.DeleteFile(trimmed.removePrefix(DELETE_FILE_MARKER)))
                mode = Mode.DELETE_FILE
                return true
            }
            if (trimmed.startsWith(UPDATE_FILE_MARKER)) {
                ensureUpdateHunkNotEmpty(trimmed)
                hunks.add(MutableHunk.UpdateFile(trimmed.removePrefix(UPDATE_FILE_MARKER)))
                mode = Mode.UPDATE_FILE
                updateHunkLineNumber = lineNumber
                return true
            }
            return false
        }

        /** 在开启新 hunk 或结束 patch 前，校验上一个 Update hunk 完整（有 chunk 且非空 chunk）。 */
        private fun ensureUpdateHunkNotEmpty(line: String) {
            val last = hunks.lastOrNull() as? MutableHunk.UpdateFile ?: return
            if (mode != Mode.UPDATE_FILE) return
            if (last.chunks.isEmpty()) {
                throw PatchParseException.InvalidHunk(
                    "Update file hunk for path '${last.path}' is empty",
                    updateHunkLineNumber,
                )
            }
            val lastChunk = last.chunks.last()
            if (lastChunk.oldLines.isEmpty() && lastChunk.newLines.isEmpty()) {
                if (line == END_PATCH_MARKER) {
                    throw PatchParseException.InvalidHunk("Update hunk does not contain any lines", lineNumber)
                }
                throw PatchParseException.InvalidHunk(UNEXPECTED_UPDATE_LINE_MSG.format(line), lineNumber)
            }
        }

        /** 处理 Update hunk 内的行：Move to / @@ chunk / EOF 标记 / context / + / - 行。 */
        private fun processUpdateLine(line: String) {
            val updateLine = line.trimEnd()
            if (handleHunkHeadersAndEndPatch(updateLine)) return

            val hunk = hunks.last() as MutableHunk.UpdateFile
            val lastChunk = hunk.chunks.lastOrNull()

            // 上一 chunk 已标记 End of File：只允许空行或新 @@ chunk
            if (lastChunk != null && lastChunk.isEndOfFile) {
                if (updateLine.isEmpty()) return
                if (updateLine != EMPTY_CHANGE_CONTEXT_MARKER && !updateLine.startsWith(CHANGE_CONTEXT_MARKER)) {
                    throw PatchParseException.InvalidHunk(EXPECTED_CONTEXT_MARKER_MSG.format(line), lineNumber)
                }
            }

            // Move to 仅允许出现在第一个 chunk 之前
            if (hunk.chunks.isEmpty() && hunk.movePath == null && updateLine.startsWith(MOVE_TO_MARKER)) {
                hunk.movePath = updateLine.removePrefix(MOVE_TO_MARKER)
                return
            }

            val isContextMarker = updateLine == EMPTY_CHANGE_CONTEXT_MARKER || updateLine.startsWith(CHANGE_CONTEXT_MARKER)

            // @@ 开启新 chunk，但上一个 chunk 是空的 → 报错（空 chunk 非法）
            if (isContextMarker && lastChunk != null && lastChunk.oldLines.isEmpty() && lastChunk.newLines.isEmpty()) {
                throw PatchParseException.InvalidHunk(UNEXPECTED_UPDATE_LINE_MSG.format(line), lineNumber)
            }

            if (updateLine == EMPTY_CHANGE_CONTEXT_MARKER) {
                hunk.chunks.add(MutableChunk())
                return
            }
            if (updateLine.startsWith(CHANGE_CONTEXT_MARKER)) {
                hunk.chunks.add(MutableChunk(changeContext = updateLine.removePrefix(CHANGE_CONTEXT_MARKER)))
                return
            }

            if (updateLine == EOF_MARKER) {
                if (lastChunk != null && lastChunk.oldLines.isEmpty() && lastChunk.newLines.isEmpty()) {
                    throw PatchParseException.InvalidHunk("Update hunk does not contain any lines", lineNumber)
                }
                lastChunk?.isEndOfFile = true
                return
            }

            if (line.isEmpty()) {
                currentChunk(hunk).pushContextLine("")
                return
            }
            if (line.startsWith(" ")) {
                currentChunk(hunk).pushContextLine(line.substring(1))
                return
            }
            if (line.startsWith("+")) {
                currentChunk(hunk).newLines.add(line.substring(1))
                return
            }
            if (line.startsWith("-")) {
                currentChunk(hunk).oldLines.add(line.substring(1))
                return
            }

            if (lastChunk != null && (lastChunk.oldLines.isNotEmpty() || lastChunk.newLines.isNotEmpty())) {
                throw PatchParseException.InvalidHunk(EXPECTED_CONTEXT_MARKER_MSG.format(line), lineNumber)
            }
            throw PatchParseException.InvalidHunk(UNEXPECTED_UPDATE_LINE_MSG.format(line), lineNumber)
        }

        private fun currentChunk(hunk: MutableHunk.UpdateFile): MutableChunk {
            if (hunk.chunks.isEmpty()) hunk.chunks.add(MutableChunk())
            return hunk.chunks.last()
        }
    }

    /** 解析中间态（可变），产出不可变 [PatchHunk]。 */
    private sealed interface MutableHunk {
        fun toHunk(): PatchHunk

        data class AddFile(val path: String, val contents: StringBuilder = StringBuilder()) : MutableHunk {
            override fun toHunk() = PatchHunk.AddFile(path, contents.toString())
        }

        data class DeleteFile(val path: String) : MutableHunk {
            override fun toHunk() = PatchHunk.DeleteFile(path)
        }

        data class UpdateFile(
            val path: String,
            var movePath: String? = null,
            val chunks: MutableList<MutableChunk> = mutableListOf(),
        ) : MutableHunk {
            override fun toHunk() = PatchHunk.UpdateFile(path, movePath, chunks.map { it.toChunk() })
        }
    }

    /** chunk 解析中间态。context 行同时进 old/new（匹配用 old，替换用 new）。 */
    private class MutableChunk(
        var changeContext: String? = null,
        val oldLines: MutableList<String> = mutableListOf(),
        val newLines: MutableList<String> = mutableListOf(),
        var isEndOfFile: Boolean = false,
    ) {
        fun pushContextLine(line: String) {
            oldLines.add(line)
            newLines.add(line)
        }

        fun toChunk() = UpdateChunk(changeContext, oldLines.toList(), newLines.toList(), isEndOfFile)
    }
}

/** 补丁解析异常。 */
sealed class PatchParseException(message: String) : Exception(message) {
    /** 补丁整体结构错误（首尾标记等）。 */
    class InvalidPatch(message: String) : PatchParseException("invalid patch: $message")

    /** 某个 hunk 内容错误，带行号。 */
    class InvalidHunk(message: String, val lineNumber: Int) :
        PatchParseException("invalid hunk at line $lineNumber, $message")
}

/** 补丁 hunk：一次 patch 中对单个文件的操作。 */
sealed class PatchHunk {
    abstract val path: String

    /** 受影响的路径（move 场景为移动目标），用于摘要展示。 */
    abstract fun affectedPath(): String

    /** 新增文件：path + 完整内容。 */
    data class AddFile(override val path: String, val contents: String) : PatchHunk() {
        override fun affectedPath() = path
    }

    /** 删除文件。 */
    data class DeleteFile(override val path: String) : PatchHunk() {
        override fun affectedPath() = path
    }

    /** 修改文件（可选移动），含多个按文件顺序排列的 chunk。 */
    data class UpdateFile(
        override val path: String,
        val movePath: String? = null,
        val chunks: List<UpdateChunk>,
    ) : PatchHunk() {
        override fun affectedPath() = movePath ?: path
    }
}

/** Update hunk 内的一个变更块。 */
data class UpdateChunk(
    /** 单行定位上下文（`@@ <ctx>`），通常是类/函数定义行。 */
    val changeContext: String? = null,
    /** 要被替换的连续行（含 `-` 行与上下文行）。 */
    val oldLines: List<String> = emptyList(),
    /** 替换后的行（含 `+` 行与上下文行）。 */
    val newLines: List<String> = emptyList(),
    /** true 表示 oldLines 必须命中文件末尾（用于末尾追加）。 */
    val isEndOfFile: Boolean = false,
)
