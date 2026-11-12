package xyz.mederi.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.util.MederiProductNames

/**
 * 按工具名探测目标摘要：
 * - 文件工具（read/write/edit/list）→ 路径（会话目录内显示相对路径，目录外保留绝对路径；读取文件时追加 #L$start-$end 行号）；
 * - 命令工具（bash）→ 命令原文；
 * - apply_patch → 补丁内涉及的文件清单（Add/Update/Delete/Move 去重取前 3）；
 * - 计划子任务工具（verify_subtask / generate_spec / update_verification）→ 序号 + 子任务标题
 *   （从计划投影按 planId + subtaskIndex 查出；verify_subtask 只在已记录判定时追加 PASS/FAIL）；
 * - todo 工具 → 返回 null（UI 仅显示“更新待办清单”，不显示内容）；
 * - 其余 → 回退到 path/file/command 等常见键，最后兜底第一个参数值（拦截任何以 [ 或 { 开头的复合 JSON 文本）。
 */
private val NON_TARGET_KEYS = setOf(
    "evidence", "entry", "content", "text", "body", "spec",
    "prompt", "diff", "patch", "description", "reason", "summary",
    "output", "stdout", "stderr", "gaptype", "gap_type"
)

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
        name == "bash" ->
            input["command"]?.takeIf { it.isNotBlank() } ?: input["cmd"]?.takeIf { it.isNotBlank() }
        name == "ask_user" ->
            extractAskUserSummary(input)
        name == "verify_subtask" ->
            planSubtaskTarget(input, snapshot, includeRecordedVerdict = true)
        name == "generate_spec" || name == "update_verification" ->
            planSubtaskTarget(input, snapshot, includeRecordedVerdict = false)
        name == "write_log" ->
            "${MederiProductNames.projectWorkdirName}/notebook.md"
        name == "write_plan" || name == "create_plan" ->
            input["title"]?.takeIf { it.isNotBlank() }
        searchQuery != null ->
            searchQuery
        firstFileValue != null -> {
            val display = toDisplayPath(firstFileValue, baseDir)
            val pathDisplay = if (display == "." || display.isBlank()) "directory" else display
            if (lowerName.contains("read") || lowerName.contains("view")) {
                val lineSuffix = extractReadLineRangeSuffix(input, oneBasedOffset = lowerName == "read_file")
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
                ?: input.entries.firstOrNull { (k, v) ->
                    k.lowercase() !in NON_TARGET_KEYS && v.isNotBlank() && !v.contains("\n") && v.length <= 80
                }?.value
    }

    // 全局防御：任何复合 JSON 结构（数组或对象）绝不作为单行摘要塞入标题
    return candidate?.trim()?.takeIf { cand ->
        val s = cand.trimStart()
        !s.startsWith("[") && !s.startsWith("{")
    }
}

/**
 * 计划子任务的目标摘要（verify_subtask / generate_spec / update_verification 共用）：
 * 优先输出「序号 + 标题」，序号 1-based——与计划卡 step_label（"步骤 N"）同一口径，
 * 因为光给 "#4" 用户看不出是哪个子任务。
 *
 * 标题从会话快照的计划投影（[ConversationSnapshot.planApprovals] / pendingPlanApproval）按
 * planId + subtaskIndex 查出：投影既由 PLAN_PROGRESS 事件实时刷新，也会在重开会话时由 Core 水合。
 *
 * [includeRecordedVerdict] 为 true 时追加**已记录的验证判定**（COMPLETED→PASS / FAILED→FAIL）；
 * 入参里的 status 是模型在验证**之前**的声明值，不能当结果显示，因此一律不读。
 * 计划或子任务查不到时回退为纯序号，绝不编造标题或判定。
 */
private fun planSubtaskTarget(
    input: Map<String, String>,
    snapshot: ConversationSnapshot?,
    includeRecordedVerdict: Boolean,
): String? {
    val index = (input["subtaskIndex"] ?: input["subtask_index"])?.trim()?.toIntOrNull() ?: return null
    val planId = input["planId"]?.trim()?.takeIf { it.isNotBlank() }
    val task = planId?.let { id ->
        snapshot?.let { snap ->
            val plan = snap.planApprovals.firstOrNull { it.id == id }
                ?: snap.pendingPlanApproval?.takeIf { it.id == id }
            plan?.subtasks?.firstOrNull { it.index == index }
        }
    }
    val taskName = task?.name?.trim()?.takeIf { it.isNotBlank() }
    val verdict = if (includeRecordedVerdict) recordedVerifyVerdict(task?.status) else null
    return buildString {
        append(index + 1)
        if (taskName != null) append("「").append(taskName).append("」")
        if (verdict != null) append("(").append(verdict).append(")")
    }
}

/**
 * 计划投影里的子任务状态 → 已记录的验证判定。
 * 只有 COMPLETED / FAILED 代表验证已跑完；PENDING / IN_PROGRESS 尚无判定，返回 null（不猜）。
 */
private fun recordedVerifyVerdict(status: String?): String? = when (status?.trim()?.uppercase()) {
    "COMPLETED" -> "PASS"
    "FAILED" -> "FAIL"
    else -> null
}

private fun extractReadLineRangeSuffix(input: Map<String, String>, oneBasedOffset: Boolean): String? {
    // 1. 显式 1-based 行号键（部分工具 / MCP）
    val startLine = (input["startLine"] ?: input["start_line"] ?: input["StartLine"])?.toIntOrNull()
    val endLine = (input["endLine"] ?: input["end_line"] ?: input["EndLine"])?.toIntOrNull()
    if (startLine != null && startLine > 0) {
        return if (endLine != null && endLine >= startLine) "#L$startLine-$endLine" else "#L$startLine"
    }

    // 2. read_file 的 offset 已是 1-based；其他兼容工具沿用原先的 0-based 展示约定。
    val offset = input["offset"]?.toIntOrNull()
    val maxLines = (input["max_lines"] ?: input["maxLines"] ?: input["limit"])?.toIntOrNull()

    // 只有指定了 offset > 0 或限制了行数（< 2000 默认大值）才显示行号后缀；全量读文件不加后缀
    if (offset != null || (maxLines != null && maxLines in 1..1999)) {
        val start1Based = if (oneBasedOffset) (offset ?: 1).coerceAtLeast(1)
            else (offset ?: 0).coerceAtLeast(0) + 1
        return if (maxLines != null && maxLines > 0) {
            val end1Based = start1Based + maxLines - 1
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
    val firstPrompt = try {
        val elem = Json.parseToJsonElement(rawQuestions)
        if (elem is JsonArray) {
            val first = elem.firstOrNull() as? JsonObject
            val prompt = first?.get("prompt")
            if (prompt is JsonPrimitive && prompt.isString) prompt.content else null
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
    return firstPrompt?.takeIf { it.isNotBlank() } ?: rawQuestions.take(50)
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
