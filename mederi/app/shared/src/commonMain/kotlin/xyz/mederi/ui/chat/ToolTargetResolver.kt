package xyz.mederi.ui

import xyz.mederi.core.contract.dto.ConversationSnapshot

/**
 * 按工具名探测目标摘要：
 * - 文件工具（read/write/edit/list）→ 路径（会话目录内显示相对路径，目录外保留绝对路径；读取文件时追加 #L$start-$end 行号）；
 * - 命令工具（execute_command/bash）→ 命令原文；
 * - apply_patch → 补丁内涉及的文件清单（Add/Update/Delete/Move 去重取前 3）；
 * - todo 工具 → 返回 null（UI 仅显示“更新待办清单”，不显示内容）；
 * - 其余 → 回退到 path/file/command 等常见键，最后兜底第一个参数值（拦截任何以 [ 或 { 开头的复合 JSON 文本）。
 */
internal fun probeToolTarget(name: String, input: Map<String, String>, snapshot: ConversationSnapshot?): String? {
    val lowerName = name.lowercase()
    // todo 工具不显示参数内容（用户要求仅显示“更新待办清单”）
    if (lowerName.contains("todo")) return null

    val baseDir = snapshot?.conversation?.directory?.takeIf { it.isNotBlank() }
    val fileOrDirKeys = listOf(
        "path", "file", "targetFile", "filePath", "file_path",
        "dir", "directory", "dir_path", "directory_path", "DirectoryPath", "SearchDirectory"
    )
    val firstFileValue = fileOrDirKeys.firstNotNullOfOrNull { input[it]?.takeIf { v -> v.isNotBlank() } }
    val isDirTool = lowerName.contains("list") || lowerName.contains("dir") || lowerName.contains("tree")
    val isSearchTool = lowerName.contains("search") || lowerName.contains("grep") || lowerName.contains("find")
    val searchQuery = if (isSearchTool) {
        input["query"] ?: input["pattern"] ?: input["regex"] ?: input["Query"] ?: input["Pattern"]
    } else null

    val candidate = when {
        name in SUBAGENT_TOOL_NAMES ->
            input["task"]?.lines()?.firstOrNull { it.isNotBlank() } ?: input["briefing"]?.lines()?.firstOrNull { it.isNotBlank() }
        name == "apply_patch" ->
            input["patch"]?.let { extractPatchFiles(it, snapshot) }?.takeIf { it.isNotBlank() }
        name == "execute_command" || name == "bash" ->
            input["command"]?.takeIf { it.isNotBlank() } ?: input["cmd"]?.takeIf { it.isNotBlank() }
        name == "ask_user" ->
            extractAskUserSummary(input)
        searchQuery != null ->
            searchQuery
        firstFileValue != null -> {
            val display = toDisplayPath(firstFileValue, baseDir)
            val pathDisplay = if (display == "." || display.isBlank()) "directory" else display
            if (lowerName.contains("read") || lowerName.contains("view")) {
                val lineSuffix = extractReadLineRangeSuffix(input)
                if (lineSuffix != null) "$pathDisplay$lineSuffix" else pathDisplay
            } else {
                pathDisplay
            }
        }
        isDirTool ->
            "directory"
        else ->
            input["command"]?.takeIf { it.isNotBlank() }
                ?: input["cmd"]?.takeIf { it.isNotBlank() }
                ?: input["patch"]?.let { extractPatchFiles(it, snapshot) }?.takeIf { it.isNotBlank() }
                ?: input.values.firstOrNull { it.isNotBlank() }
    }

    // 全局防御：任何复合 JSON 结构（数组或对象）绝不作为单行摘要塞入标题
    return candidate?.trim()?.takeIf { cand ->
        val s = cand.trimStart()
        !s.startsWith("[") && !s.startsWith("{")
    }
}

private fun extractReadLineRangeSuffix(input: Map<String, String>): String? {
    // 1. 显式 1-based 行号键（部分工具 / MCP）
    val startLine = (input["startLine"] ?: input["start_line"] ?: input["StartLine"])?.toIntOrNull()
    val endLine = (input["endLine"] ?: input["end_line"] ?: input["EndLine"])?.toIntOrNull()
    if (startLine != null && startLine > 0) {
        return if (endLine != null && endLine >= startLine) "#L$startLine-$endLine" else "#L$startLine"
    }

    // 2. read_file 标准参数：offset（0-based）与 max_lines（行数）
    val offset = input["offset"]?.toIntOrNull()
    val maxLines = (input["max_lines"] ?: input["maxLines"] ?: input["limit"])?.toIntOrNull()

    // 只有指定了 offset > 0 或限制了行数（< 2000 默认大值）才显示行号后缀；全量读文件不加后缀
    if (offset != null || (maxLines != null && maxLines in 1..1999)) {
        val off = (offset ?: 0).coerceAtLeast(0)
        val start1Based = off + 1
        return if (maxLines != null && maxLines > 0) {
            val end1Based = off + maxLines
            "#L$start1Based-$end1Based"
        } else {
            "#L$start1Based+"
        }
    }
    return null
}

private fun extractAskUserSummary(input: Map<String, String>): String? {
    input["prompt"]?.takeIf { it.isNotBlank() }?.let { return it }
    val rawQuestions = input["questions"] ?: return null
    val match = Regex("\"prompt\"\\s*:\\s*\"([^\"]+)\"").find(rawQuestions)
    return match?.groupValues?.get(1) ?: rawQuestions.take(50)
}

/**
 * 路径显示规则：会话所属目录内 → 相对路径；目录外 → 保留绝对路径（用户能看出 AI 动了哪个位置）。
 */
private fun toDisplayPath(raw: String, baseDir: String?): String {
    val path = raw.trim()
    if (path.isBlank()) return path
    val base = baseDir?.trim()?.trimEnd('/')
    if (base != null && isAbsolutePath(path)) {
        if (path.startsWith("$base/")) return path.removePrefix("$base/")
        if (path == base) return path
    }
    return path
}

private fun isAbsolutePath(path: String): Boolean =
    path.startsWith("/") || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(path)

/** 从 apply_patch 补丁文本中提取涉及的文件路径（Add/Update/Delete/Move，去重后取前 3）。 */
private fun extractPatchFiles(patch: String, snapshot: ConversationSnapshot?): String? {
    if (patch.isBlank()) return null
    val baseDir = snapshot?.conversation?.directory?.takeIf { it.isNotBlank() }
    val paths = patch.lineSequence()
        .mapNotNull { line ->
            Regex("^\\*\\*\\* (?:Add File|Update File|Delete File|Move to): (.+)$")
                .find(line.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
        .distinct()
        .toList()
    if (paths.isEmpty()) return null
    return paths.take(3).joinToString(", ") { toDisplayPath(it, baseDir) } +
        if (paths.size > 3) ", …" else ""
}