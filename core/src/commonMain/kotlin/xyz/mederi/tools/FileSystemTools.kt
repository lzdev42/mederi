package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.validate
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import xyz.mederi.tools.diff.FileChangeStatus
import xyz.mederi.tools.diff.PatchChange
import xyz.mederi.tools.diff.TurnDiffTracker
import xyz.mederi.tools.patch.PatchApplyException
import xyz.mederi.tools.patch.PatchApplier
import xyz.mederi.tools.patch.PatchHunk
import xyz.mederi.tools.patch.PatchParseException
import xyz.mederi.tools.patch.PatchParser

import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.relativeTo
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * AGENTS.md 子树懒发现回调。
 *
 * 文件工具（read_file / list_directory）成功访问某路径后调用 [onAccessed]；
 * 返回本次**新发现**（调用方 registry 未登记过）的 AGENTS.md，无新发现返回 null。
 * 由 TurnExecutor 组合 AgentsFileLoader + 会话级 registry 提供。
 */
fun interface AgentsSubtreeDiscovery {
    fun onAccessed(accessedPath: String): xyz.mederi.project.AgentsFileLoader.AgentsFile?
}

/**
 * apply_patch dry-run 验证产物：单个文件的变更。
 */
private sealed interface VerifiedChange {
    data class Add(val content: String) : VerifiedChange
    data class Delete(val content: String) : VerifiedChange
    data class Update(val newContent: String, val moveTarget: File?) : VerifiedChange
}

/**
 * 文件系统工具集。
 *
 * 所有文件操作都限制在 [allowedDirectories] 范围内。
 * 路径校验：解析后的绝对路径必须在某个允许目录内，否则拒绝操作。
 *
 * @param allowedDirectories 项目目录列表（绝对路径），工具操作的作用域。
 * @param diffTracker 变更追踪器。
 * @param agentsDiscovery AGENTS.md 子树懒发现回调；read/list 成功后触发，新发现的内容追加到返回文本。
 */
class FileSystemTools(
    private val allowedDirectories: List<String>,
    private val diffTracker: TurnDiffTracker? = null,
    private val agentsDiscovery: AgentsSubtreeDiscovery? = null,
    /**
     * 文件写入回调：write/edit/apply_patch 成功写入后上报路径。
     * 执行器子代理经 SubagentRunnerImpl 挂回调收集 touched files（flush 到 Subtask.executorTouchedFiles）。
     */
    private val onFileTouched: ((String) -> Unit)? = null
) {

    // ==================== 参数定义 ====================

    @Serializable
    data class ReadFileArgs(
        @LLMDescription("File path, absolute or relative to the project root.")
        val path: String = "",
        @LLMDescription("Line number to start reading from (1-based). Default 1.")
        val offset: Int = 1,
        @LLMDescription("Max lines to return; 0 = no limit.")
        @kotlinx.serialization.SerialName("max_lines")
        val maxLines: Int = 2000
    )

    @Serializable
    data class WriteFileArgs(
        @LLMDescription("File path, relative to the project root (e.g. src/Main.kt). Absolute paths only inside the project directory.")
        val path: String = "",
        @LLMDescription("Full content to write to the file.")
        val content: String = ""
    )

    @Serializable
    data class EditFileArgs(
        @LLMDescription("File path, relative to the project root (e.g. src/Main.kt). Absolute paths only inside the project directory.")
        val path: String = "",
        @LLMDescription("Exact original text to replace. Must match the file exactly and be UNIQUE in it — multiple matches are rejected; provide more surrounding context.")
        val original: String = "",
        @LLMDescription("Replacement text.")
        val replacement: String = "",
        @LLMDescription("true = replace every occurrence of original; false (default) = require uniqueness and replace once.")
        @kotlinx.serialization.SerialName("replace_all")
        val replaceAll: Boolean = false
    )

    @Serializable
    data class ListDirectoryArgs(
        @LLMDescription("Directory path, absolute or relative to the project root. Empty = project root.")
        val path: String = ""
    )

    @Serializable
    data class ApplyPatchArgs(
        @LLMDescription(
            "完整的补丁文本（原样文本）。格式：\n" +
                "*** Begin Patch\n" +
                "*** Add File: <path>      （新增文件，后跟若干以 + 开头的内容行）\n" +
                "*** Delete File: <path>   （删除文件）\n" +
                "*** Update File: <path>   （修改文件，后跟变更块）\n" +
                "*** Move to: <path>       （可选，仅 Update File 后、第一个变更块前：移动/重命名）\n" +
                "@@ <可选上下文行>          （开启一个变更块，@@ 后可带单行定位上下文）\n" +
                " <上下文行>               （以单个空格开头的行是上下文行，原样保留）\n" +
                "-<要删除的行>             （以 - 开头：删除行）\n" +
                "+<要新增的行>             （以 + 开头：新增行）\n" +
                "*** End of File           （可选：本变更块必须命中文件末尾，用于末尾追加）\n" +
                "*** End Patch"
        )
        val patch: String = ""
    )

    // ==================== 工具实现 ====================

    inner class ReadFileTool : SimpleTool<ReadFileArgs>(
        argsType = typeToken<ReadFileArgs>(),
        name = "read_file",
        description = "Read a local file and return its text content, starting at offset (1-based) up to max_lines lines. Each line is prefixed by its 1-based line number as `<line>: <content>`. The prefix is for reference and is not part of file content. The result reports the returned line range and the next offset when more remain."
    ) {
        override suspend fun execute(args: ReadFileArgs): String {
            val file = resolveForRead(args.path, mustExist = true, mustBeFile = true)
            val content = file.readText()
            // 空文件特判："" 的 lines() 在 Kotlin 返回 [" "]（非空），会误报 total=1
            val allLines = if (content.isEmpty()) emptyList() else content.lines()
            val total = allLines.size

            // offset 1-based（对齐 opencode `page.offset || 1`，0 或负都当 1）。
            val effectiveOffset = if (args.offset <= 0) 1 else args.offset
            val limit = if (args.maxLines <= 0) Int.MAX_VALUE else args.maxLines
            val start = (effectiveOffset - 1).coerceAtMost(total)
            val end = (start.toLong() + limit).coerceAtMost(total.toLong()).toInt()
            val slice = allLines.subList(start, end)

            val hasMore = end < total
            // header 照抄 opencode read.ts toModelContent 文案（path 用 displayPath）。
            val header = when {
                slice.isEmpty() && total == 0 -> "Read file ${displayPath(file)}, 0 lines"
                slice.isEmpty() -> "Offset $effectiveOffset is out of range for this file ($total lines)"
                else -> "Read file ${displayPath(file)}, lines $effectiveOffset-${effectiveOffset + slice.size - 1}"
            }
            // 行体每行加 1-based 行号前缀 `${lineNumber}: ${content}`（照抄 opencode）。
            val body = if (slice.isEmpty()) "" else "\n" + slice.mapIndexed { i, line ->
                "${effectiveOffset + i}: $line"
            }.joinToString("\n")
            // truncated 提示照抄 opencode：next = effectiveOffset + slice.size（1-based 续读）。
            val truncatedNote = if (hasMore) {
                "\n[Output truncated. Continue reading with offset: ${effectiveOffset + slice.size}]"
            } else ""

            val result = header + body + truncatedNote
            return appendDiscoveredAgents(result, file)
        }
    }

    inner class WriteFileTool : SimpleTool<WriteFileArgs>(
        argsType = typeToken<WriteFileArgs>(),
        name = "write_file",
        description = "Write content to a file, creating it (and parent directories) if absent, overwriting it if present."
    ) {
        override suspend fun execute(args: WriteFileArgs): String {
            val file = resolveForWrite(args.path, mustExist = false, mustBeFile = true)
            val conflict = FileWriteRegistry.tryAcquire(listOf(file))
            if (conflict != null) {
                return "Error: ${displayPath(file)} is currently being modified by another concurrent tool call. " +
                    "Retry after the other edit completes, or send this write in a separate message."
            }
            try {
                file.parentFile?.mkdirs()
                val oldContent = file.takeIf { it.exists() }?.readText()
                file.writeText(args.content)
                diffTracker?.recordWrite(displayPath(file), oldContent, args.content)
                onFileTouched?.invoke(displayPath(file))
                return "Written ${args.content.length} chars to ${displayPath(file)}"
            } finally {
                FileWriteRegistry.release(listOf(file))
            }
        }
    }

    inner class EditFileTool : SimpleTool<EditFileArgs>(
        argsType = typeToken<EditFileArgs>(),
        name = "edit_file",
        description = "Replace original text in an existing file. original must be UNIQUE in the file — multiple matches are rejected (provide more context). replace_all=true replaces every occurrence."
    ) {
        override suspend fun execute(args: EditFileArgs): String {
            validate(args.original.isNotEmpty()) { "original must not be empty" }
            val file = resolveForWrite(args.path, mustExist = true, mustBeFile = true)
            val conflict = FileWriteRegistry.tryAcquire(listOf(file))
            if (conflict != null) {
                return "Error: ${displayPath(file)} is currently being modified by another concurrent tool call. " +
                    "Retry after the other edit completes, or send this edit in a separate message."
            }
            try {
                // no-op 拦截（照抄 opencode：oldString == newString 直接拒绝，不写入）
                if (args.original == args.replacement) {
                    return "Error: original and replacement are identical — no changes to apply."
                }

                val content = file.readText()

                // CRLF 归一化（照抄 opencode edit.ts）：检测文件原行尾，把 original/replacement
                // 的行尾统一成文件原风格，避免 LF/CRLF 不一致导致匹配失败。
                val crlf = "\r\n"
                val ending = if (content.contains(crlf)) crlf else "\n"
                val oldString = args.original.replace(crlf, "\n").replace("\n", ending)
                val newString = args.replacement.replace(crlf, "\n").replace("\n", ending)

                // 三级匹配降级（照抄 opencode edit.ts）：
                // 1) exact 精确匹配；2) exact 0 命中 → unicode 归一化后精确匹配；
                // 3) 仍 0 命中 → 行级容差匹配（逐行 trimEnd + 归一化比较）。
                val exact = findOccurrences(content, oldString)
                val unicode = if (exact.isNotEmpty()) emptyList() else
                    findOccurrences(normalizeForMatch(content), normalizeForMatch(oldString))
                val lineLevel = if (exact.isNotEmpty() || unicode.isNotEmpty()) emptyList() else
                    findLineOccurrences(content, oldString)
                val matches = when {
                    exact.isNotEmpty() -> exact
                    unicode.isNotEmpty() -> unicode
                    else -> lineLevel
                }
                val matchType = when {
                    exact.isNotEmpty() -> "exact"
                    unicode.isNotEmpty() -> "fuzzy"
                    lineLevel.isNotEmpty() -> "line-level"
                    else -> null
                }

                val replacements = matches.size
                if (replacements == 0) {
                    // 三级全败（照抄 opencode 通用错误文案）
                    return "Could not find oldString in ${displayPath(file)}. It must match exactly, " +
                        "including whitespace and indentation."
                }
                if (replacements > 1 && !args.replaceAll) {
                    return "Found $replacements matches for oldString in ${displayPath(file)}, but expected " +
                        "exactly one. Add more surrounding context to make original unique, or set replace_all=true."
                }

                // 反向 splice 替换（照抄 opencode：从后往前替换，保证后续 match 的 offset 不失效）
                val toApply = if (args.replaceAll) matches else matches.take(1)
                val updated = StringBuilder(content)
                for ((start, end) in toApply.sortedByDescending { it.first }) {
                    updated.replace(start, end, newString)
                }

                file.writeText(updated.toString())
                diffTracker?.recordWrite(displayPath(file), content, updated.toString())
                onFileTouched?.invoke(displayPath(file))
                val replaced = if (args.replaceAll) replacements else 1
                val fallbackNote = if (matchType != null && matchType != "exact") " (matched via $matchType fallback)" else ""
                return "Edited ${displayPath(file)}: replaced $replaced occurrence(s)" + fallbackNote
            } finally {
                FileWriteRegistry.release(listOf(file))
            }
        }
    }

    /**
     * unicode 归一化（照抄 opencode edit.ts normalizeForMatch）：智能引号→直引号、
     * 各类破折号→连字符、各种 unicode 空格→普通空格。用于精确匹配失败后的容差匹配。
     */
    private fun normalizeForMatch(value: String): String = value
        .replace("‘", "'").replace("’", "'").replace("‚", "'").replace("‛", "'")
        .replace("“", "\"").replace("”", "\"").replace("„", "\"").replace("‟", "\"")
        .replace("‐", "-").replace("‑", "-").replace("‒", "-").replace("–", "-")
        .replace("—", "-").replace("―", "-").replace("−", "-")
        .replace("\u00A0", " ").replace("\u2002", " ").replace("\u2003", " ")
        .replace("\u2004", " ").replace("\u2005", " ").replace("\u2006", " ")
        .replace("\u2007", " ").replace("\u2008", " ").replace("\u2009", " ")
        .replace("\u200A", " ").replace("\u202F", " ").replace("\u205F", " ")
        .replace("\u3000", " ")

    /**
     * 非重叠精确出现次数与位置（照抄 opencode edit.ts findOccurrences）。
     * 返回 (start, end) 列表，end = start + search.length。
     */
    private fun findOccurrences(content: String, search: String): List<Pair<Int, Int>> {
        if (search.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<Int, Int>>()
        var offset = 0
        while (true) {
            val idx = content.indexOf(search, offset)
            if (idx < 0) break
            result.add(idx to idx + search.length)
            offset = idx + search.length
        }
        return result
    }

    /**
     * 行级容差匹配（照抄 opencode edit.ts findLineOccurrences）：
     * 按 \n 把 search 切成期望行，逐行比较（trimEnd + normalizeForMatch），
     * 命中的行区间合并重叠后返回。trailing newline 对齐（search 末尾 \n 要求最后一行有换行）。
     */
    private fun findLineOccurrences(content: String, search: String): List<Pair<Int, Int>> {
        val trailingNewline = search.endsWith("\n")
        val expected = search.split("\n").let { if (trailingNewline && it.last().isEmpty()) it.dropLast(1) else it }
        if (expected.isEmpty()) return emptyList()

        // 按 [^\n]*(?:\n|$) 切行，记录每行的 start/end/text/contentEnd/newline
        data class Line(val start: Int, val end: Int, val text: String, val contentEnd: Int, val newline: Boolean)
        val lines = mutableListOf<Line>()
        val regex = Regex("[^\\n]*(?:\\n|$)")
        for (m in regex.findAll(content)) {
            val raw = m.value
            if (raw.isEmpty()) continue
            val newline = raw.endsWith("\n")
            val text = if (newline) raw.dropLast(1) else raw
            val contentEnd = m.range.first + text.length - (if (text.endsWith("\r")) 1 else 0)
            lines.add(Line(m.range.first, m.range.last + 1, text, contentEnd, newline))
        }

        val candidates = mutableListOf<Pair<Int, Int>>()
        for (i in lines.indices) {
            val actual = lines.subList(i, minOf(i + expected.size, lines.size))
            if (actual.size != expected.size) continue
            val allMatch = actual.indices.all { j ->
                normalizeForMatch(actual[j].text.trimEnd()) == normalizeForMatch(expected[j].trimEnd())
            }
            if (!allMatch) continue
            val last = actual.last()
            if (trailingNewline && !last.newline) continue
            candidates.add(lines[i].start to if (trailingNewline) last.end else last.contentEnd)
        }
        // 合并重叠区间（照抄 opencode reduce）
        val merged = mutableListOf<Pair<Int, Int>>()
        for (c in candidates) {
            if (merged.any { it.second > c.first && it.first < c.second }) continue
            merged.add(c)
        }
        return merged
    }

    inner class ListDirectoryTool : SimpleTool<ListDirectoryArgs>(
        argsType = typeToken<ListDirectoryArgs>(),
        name = "list_directory",
        description = "List files and subdirectories in a directory. Empty path = project root."
    ) {
        override suspend fun execute(args: ListDirectoryArgs): String {
            val dirPath = if (args.path.isBlank()) allowedDirectories.first() else args.path
            val dir = resolveForRead(dirPath, mustExist = true, mustBeFile = false)
            val entries = dir.listFiles()?.sortedBy { it.name } ?: return "Directory is empty or inaccessible"
            if (entries.isEmpty()) return "Directory is empty"
            val listing = entries.joinToString("\n") { entry ->
                val prefix = if (entry.isDirectory) "[DIR]  " else "[FILE] "
                "$prefix${entry.name}${if (entry.isFile && entry.length() > 0) " (${entry.length()} bytes)" else ""}"
            }
            return appendDiscoveredAgents(listing, dir)
        }
    }

    /**
     * apply_patch 工具：一份补丁文本一次性对多个文件执行新增/删除/更新（含移动）。
     *
     * 执行分三阶段（对照 Codex apply_patch）：
     * 1. 解析：[PatchParser] 状态机解析为 hunk 列表；
     * 2. dry-run 验证：校验所有路径（含 move 目标）在允许目录内、检测重复路径、
     *    为每个 Update hunk 基于原文件计算出完整新内容——任何失败则磁盘零改动；
     * 3. 应用：逐 hunk 落盘（Add 建父目录写文件；Delete 校验非目录后删除；
     *    Update 直接写或 Move 先写目标再删源），输出 git 风格 A/M/D 摘要。
     */
    inner class ApplyPatchTool : SimpleTool<ApplyPatchArgs>(
        argsType = typeToken<ApplyPatchArgs>(),
        name = "apply_patch",
        description =
            "应用一份补丁，一次性对多个文件执行新增/修改/删除/移动操作。适合同时修改多个文件、一个文件的多个位置、" +
                "或新增+修改+删除混合操作；单文件单处小改动用 edit_file 即可。应用前会整体校验，任何一处匹配失败则全部不落盘。"
    ) {
        override suspend fun execute(args: ApplyPatchArgs): String {
            if (args.patch.isBlank()) {
                return "Error: patch is empty. The patch must start with '*** Begin Patch' and end with '*** End Patch'."
            }

            // 阶段 1：解析
            val hunks = try {
                PatchParser.parse(args.patch)
            } catch (e: PatchParseException) {
                return "apply_patch verification failed: ${e.message}"
            }
            if (hunks.isEmpty()) return "No files were modified."

            // 阶段 2：dry-run 验证（磁盘零改动）
            val verified = try {
                verifyHunks(hunks)
            } catch (e: Exception) {
                return "apply_patch verification failed: ${e.message}"
            }

            // 阶段 3：应用
            return try {
                val result = applyHunks(hunks, verified)
                if (onFileTouched != null && !result.startsWith("apply_patch failed")) {
                    verified.keys.forEach { onFileTouched.invoke(it) }
                }
                result
            } catch (e: Exception) {
                "apply_patch failed: ${e.message}"
            }
        }

        /** dry-run 验证所有 hunk：路径校验、重复路径检测、新内容计算。 */
        private fun verifyHunks(hunks: List<PatchHunk>): Map<String, VerifiedChange> {
            val changes = LinkedHashMap<String, VerifiedChange>()
            for (hunk in hunks) {
                when (hunk) {
                    is PatchHunk.AddFile -> {
                        val file = resolveForWrite(hunk.path, mustExist = false, mustBeFile = true)
                        checkDuplicate(changes, file)
                        changes[keyOf(file)] = VerifiedChange.Add(hunk.contents)
                    }

                    is PatchHunk.DeleteFile -> {
                        val file = resolveForWrite(hunk.path, mustExist = true, mustBeFile = true)
                        checkDuplicate(changes, file)
                        val content = try {
                            file.readText()
                        } catch (e: IOException) {
                            throw PatchApplyException("Failed to read ${displayPath(file)}")
                        }
                        changes[keyOf(file)] = VerifiedChange.Delete(content)
                    }

                    is PatchHunk.UpdateFile -> {
                        val file = resolveForWrite(hunk.path, mustExist = true, mustBeFile = true)
                        checkDuplicate(changes, file)
                        val moveTarget = hunk.movePath?.let {
                            resolveForWrite(it, mustExist = false, mustBeFile = true)
                        }
                        val newContent = try {
                            PatchApplier.deriveNewContents(file.readText(), displayPath(file), hunk.chunks)
                        } catch (e: IOException) {
                            throw PatchApplyException("Failed to read file to update ${displayPath(file)}")
                        }
                        changes[keyOf(file)] = VerifiedChange.Update(newContent, moveTarget)
                    }
                }
            }
            return changes
        }

        /** 应用已验证的 hunk，返回 git 风格 A/M/D 摘要。 */
        private fun applyHunks(hunks: List<PatchHunk>, verified: Map<String, VerifiedChange>): String {
            val added = mutableListOf<String>()
            val modified = mutableListOf<String>()
            val deleted = mutableListOf<String>()
            val patchChanges = mutableListOf<PatchChange>()

            for (hunk in hunks) {
                when (hunk) {
                    is PatchHunk.AddFile -> {
                        val file = resolveForWrite(hunk.path, mustExist = false, mustBeFile = true)
                        writeFileWithParentRetry(file, hunk.contents)
                        val display = displayPath(file)
                        added.add(display)
                        patchChanges.add(PatchChange(display, FileChangeStatus.ADDED, null, hunk.contents))
                    }

                    is PatchHunk.DeleteFile -> {
                        val file = resolveForWrite(hunk.path, mustExist = true, mustBeFile = true)
                        val content = file.readText()
                        val display = displayPath(file)
                        if (file.isDirectory) {
                            throw PatchApplyException("Failed to delete file $display: it is a directory")
                        }
                        if (!file.delete()) {
                            throw PatchApplyException("Failed to delete file $display")
                        }
                        deleted.add(display)
                        patchChanges.add(PatchChange(display, FileChangeStatus.DELETED, content, null))
                    }

                    is PatchHunk.UpdateFile -> {
                        val src = resolveForWrite(hunk.path, mustExist = true, mustBeFile = true)
                        val oldContent = src.readText()
                        val change = verified[keyOf(src)] as VerifiedChange.Update
                        val displaySrc = displayPath(src)
                        if (hunk.movePath != null) {
                            val dest = change.moveTarget
                                ?: throw PatchApplyException("Missing resolved move target for $displaySrc")
                            writeFileWithParentRetry(dest, change.newContent)
                            if (!src.delete()) {
                                throw PatchApplyException(
                                    "Failed to remove original $displaySrc " +
                                        "(target ${displayPath(dest)} was already written)",
                                )
                            }
                            val displayDest = displayPath(dest)
                            modified.add(displayDest)
                            patchChanges.add(PatchChange(displayDest, FileChangeStatus.MODIFIED, oldContent, change.newContent))
                        } else {
                            src.writeText(change.newContent)
                            modified.add(displaySrc)
                            patchChanges.add(PatchChange(displaySrc, FileChangeStatus.MODIFIED, oldContent, change.newContent))
                        }
                    }
                }
            }

            diffTracker?.trackPatch(patchChanges)

            return buildString {
                appendLine("Success. Updated the following files:")
                added.forEach { appendLine("A $it") }
                modified.forEach { appendLine("M $it") }
                deleted.forEach { appendLine("D $it") }
            }.trimEnd('\n')
        }

        private fun checkDuplicate(changes: Map<String, VerifiedChange>, file: File) {
            if (changes.containsKey(keyOf(file))) {
                throw PatchApplyException("multiple operations target ${displayPath(file)}")
            }
        }

        private fun keyOf(file: File): String = file.toPath().absolute().normalize().toString()

        /** 写文件，失败时尝试创建父目录后重试（对照 Codex write_file_with_missing_parent_retry）。 */
        private fun writeFileWithParentRetry(file: File, content: String) {
            try {
                file.writeText(content)
            } catch (e: IOException) {
                file.parentFile?.mkdirs()
                file.writeText(content)
            }
        }
    }

    // ==================== 路径安全校验 ====================

    /**
     * AGENTS.md 子树懒发现：read/list 成功访问后，若访问路径上（项目目录内、向上）
     * 有本会话未注入过的 AGENTS.md，将其内容追加到工具返回文本末尾。
     * 访问对象自身就是 AGENTS.md 时跳过（内容已在返回文本里，追加即重复）。
     */
    private fun appendDiscoveredAgents(result: String, accessed: File): String {
        if (accessed.isFile && accessed.name.equals(xyz.mederi.project.AgentsFileLoader.FILE_NAME, ignoreCase = true)) {
            return result
        }
        val discovered = agentsDiscovery?.onAccessed(
            accessed.toPath().absolute().normalize().toString()
        ) ?: return result
        return result + "\n\n--- AGENTS.md (${discovered.relativePath}) ---\n${discovered.content}"
    }

    /**
     * 读路径解析：**全盘可读**，不做目录包含校验。
     * 相对路径仍优先在项目目录下解析（找不到时相对主目录）。
     *
     * @param rawPath 用户输入的路径（绝对或相对）。
     * @param mustExist 是否必须已存在。
     * @param mustBeFile true=必须是文件，false=必须是目录。
     * @return 解析后的 File。
     * @throws IllegalArgumentException 存在性/类型校验失败。
     */
    private fun resolveForRead(rawPath: String, mustExist: Boolean, mustBeFile: Boolean): File {
        val absolute = resolvePath(rawPath).absolute()
        return validateFile(absolute.toFile(), rawPath, mustExist, mustBeFile)
    }

    /**
     * 写路径解析：**必须在项目目录内**（containment 白名单，代码强制不变量）。
     *
     * @throws IllegalArgumentException 路径不在允许目录内或校验失败。
     */
    private fun resolveForWrite(rawPath: String, mustExist: Boolean, mustBeFile: Boolean): File {
        val resolved = resolvePath(rawPath)
        val absolute = resolved.absolute()

        // 校验：绝对路径必须在某个允许目录内
        val inside = allowedDirectories.any { dir ->
            val dirPath = Path(dir).absolute().normalize()
            absolute.normalize().startsWith(dirPath)
        }
        if (!inside) {
            throw IllegalArgumentException(
                "Path '$rawPath' is outside project directories: $allowedDirectories. " +
                    "Writes are restricted to project directories (code-enforced). " +
                    "Ask the user to add the directory to the project or the sandbox whitelist if this write is legitimate."
            )
        }

        return validateFile(absolute.toFile(), rawPath, mustExist, mustBeFile)
    }

    /** 存在性/类型校验（读写的公共尾部）。 */
    private fun validateFile(file: File, rawPath: String, mustExist: Boolean, mustBeFile: Boolean): File {
        if (mustExist && !file.exists()) {
            throw IllegalArgumentException("Path does not exist: ${displayPath(file)}")
        }
        if (mustExist && mustBeFile && !file.isFile) {
            throw IllegalArgumentException("Path is not a file: ${displayPath(file)}")
        }
        if (mustExist && !mustBeFile && !file.isDirectory) {
            throw IllegalArgumentException("Path is not a directory: ${displayPath(file)}")
        }
        return file
    }

    /**
     * 解析相对路径：依次尝试在各个允许目录下解析，取第一个存在的。
     * 如果是绝对路径直接返回。
     */
    private fun resolvePath(rawPath: String): Path {
        val p = Path(rawPath)
        if (p.isAbsolute) return p
        // 相对路径：在允许目录下查找
        for (dir in allowedDirectories) {
            val candidate = Path(dir).resolve(rawPath)
            if (candidate.toFile().exists()) return candidate
        }
        // 都不存在时，相对于第一个允许目录
        return Path(allowedDirectories.first()).resolve(rawPath)
    }

    /**
     * 生成给用户看的路径：优先显示相对于允许目录的相对路径。
     */
    private fun displayPath(file: File): String {
        val abs = file.toPath().absolute().normalize()
        for (dir in allowedDirectories) {
            val dirPath = Path(dir).absolute().normalize()
            if (abs.startsWith(dirPath)) {
                return abs.relativeTo(dirPath).toString()
            }
        }
        return abs.toString()
    }
}
