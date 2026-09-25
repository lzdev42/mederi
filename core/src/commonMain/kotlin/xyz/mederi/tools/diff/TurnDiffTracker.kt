package xyz.mederi.tools.diff

import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import xyz.mederi.debug.DebugLog

/**
 * 跟踪单次 turn 内的文件变更，并生成 unified diff。
 *
 * 参考 Codex TurnDiffTracker：写工具执行后主动汇报变更，turn 结束时可选做目录快照兜底。
 *
 * @param sessionId 当前 Session ID。
 * @param projectDirectories 项目目录列表（绝对路径）。
 */
class TurnDiffTracker(
    private val sessionId: String,
    private val projectDirectories: List<String>
) {
    private val baselineByPath = mutableMapOf<String, String>()
    private val currentByPath = mutableMapOf<String, String>()

    companion object {
        private const val MAX_FILE_SIZE = 512 * 1024 // 512 KB
        private val skipDirs = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules",
            "target", "out", ".next", ".nuxt", "dist", "__pycache__",
            ".venv", "venv", ".cache"
        )
    }

    /**
     * 记录 apply_patch 工具产生的变更。
     */
    @Synchronized
    fun trackPatch(changes: List<PatchChange>) {
        for (change in changes) {
            val path = normalizePath(change.path)
            when (change.status) {
                FileChangeStatus.ADDED -> applyAdd(path, change.after ?: "")
                FileChangeStatus.DELETED -> applyDelete(path, change.before ?: "")
                FileChangeStatus.MODIFIED -> applyUpdate(path, change.before ?: "", change.after ?: "")
            }
        }
    }

    /**
     * 记录 write_file / edit_file 工具产生的变更。
     *
     * @param path 文件路径（相对或绝对）。
     * @param oldContent 变更前内容（新增文件传 null）。
     * @param newContent 变更后内容。
     */
    @Synchronized
    fun recordWrite(path: String, oldContent: String?, newContent: String) {
        val normalized = normalizePath(path)
        if (!currentByPath.containsKey(normalized) && !baselineByPath.containsKey(normalized)) {
            oldContent?.let { baselineByPath[normalized] = it }
        }
        currentByPath[normalized] = newContent
    }

    /**
     * 记录文件删除。
     */
    @Synchronized
    fun recordDelete(path: String, oldContent: String) {
        val normalized = normalizePath(path)
        if (!currentByPath.containsKey(normalized) && !baselineByPath.containsKey(normalized)) {
            baselineByPath[normalized] = oldContent
        }
        currentByPath.remove(normalized)
    }

    /**
     * 刷新已追踪文件的磁盘内容（兜底：execute_command 可能偷偷改了文件）。
     *
     * 直接按已知路径读文件，不遍历整棵目录树。
     */
    @Synchronized
    fun captureSnapshot() {
        val knownPaths = baselineByPath.keys + currentByPath.keys
        DebugLog.section("DiffTracker", "captureSnapshot")
        DebugLog.data("DiffTracker", "sessionId", sessionId)
        DebugLog.data("DiffTracker", "tracked paths", knownPaths.size)
        if (knownPaths.isEmpty()) {
            DebugLog.event("DiffTracker", "no tracked files, skipping snapshot")
            return
        }

        var refreshed = 0
        for (relative in knownPaths) {
            for (dir in projectDirectories) {
                val candidate = Path(dir).resolve(relative)
                if (!candidate.isRegularFile()) continue
                val content = runCatching { candidate.readText() }.getOrNull() ?: break
                currentByPath[relative] = content
                refreshed++
                break
            }
        }
        DebugLog.event("DiffTracker", "snapshot done: refreshed=$refreshed, total tracked=${baselineByPath.size + currentByPath.size}")
    }

    /**
     * 合并另一来源（子代理 turn）的文件改动进本 tracker。
     *
     * 首次写入者固定 baseline：同一文件本 tracker 已追踪时保留本侧 baseline、
     * 仅覆盖 current——谁先碰文件谁定"变更前"内容，diff 语义保持正确。
     * （同一文件的并发写已被 FileWriteRegistry 硬拒绝，此处的竞争只是时序性的。）
     */
    @Synchronized
    fun mergeChanges(changes: List<FileChange>) {
        for (change in changes) {
            when (change.status) {
                FileChangeStatus.ADDED -> applyAdd(change.path, change.after ?: "")
                FileChangeStatus.DELETED -> applyDelete(change.path, change.before ?: "")
                FileChangeStatus.MODIFIED -> applyUpdate(change.path, change.before ?: "", change.after ?: "")
            }
        }
        if (changes.isNotEmpty()) {
            DebugLog.event("DiffTracker", "merged ${changes.size} sub-agent file changes into session $sessionId")
        }
    }

    /**
     * 生成最终的 [TurnDiff]。
     */
    @Synchronized
    fun buildDiff(messageId: String?, createdAt: String): TurnDiff {
        val changes = buildFileChanges()
        DebugLog.event("DiffTracker", "buildDiff: ${changes.size} file changes")
        changes.forEach { change ->
            val beforeLines = change.before?.lines()?.size ?: 0
            val afterLines = change.after?.lines()?.size ?: 0
            DebugLog.data("DiffTracker", "  ${change.path}", "status=${change.status}, before=${beforeLines} lines, after=${afterLines} lines")
        }
        val unifiedDiff = buildUnifiedDiff(changes)
        return TurnDiff(
            sessionId = sessionId,
            messageId = messageId,
            changes = changes,
            unifiedDiff = unifiedDiff,
            createdAt = createdAt
        )
    }

    private fun applyAdd(path: String, content: String) {
        if (!currentByPath.containsKey(path) && !baselineByPath.containsKey(path)) {
            // 新增文件无 baseline
        }
        currentByPath[path] = content
    }

    private fun applyDelete(path: String, content: String) {
        if (!currentByPath.containsKey(path) && !baselineByPath.containsKey(path)) {
            baselineByPath[path] = content
        }
        currentByPath.remove(path)
    }

    private fun applyUpdate(path: String, oldContent: String, newContent: String) {
        if (!currentByPath.containsKey(path) && !baselineByPath.containsKey(path)) {
            baselineByPath[path] = oldContent
        }
        currentByPath[path] = newContent
    }

    private fun buildFileChanges(): List<FileChange> {
        val paths = (baselineByPath.keys + currentByPath.keys).sorted()
        return paths.mapNotNull { path ->
            val before = baselineByPath[path]
            val after = currentByPath[path]
            when {
                before == null && after != null -> FileChange(path, FileChangeStatus.ADDED, null, after)
                before != null && after == null -> FileChange(path, FileChangeStatus.DELETED, before, null)
                before != null && after != null && before != after -> FileChange(path, FileChangeStatus.MODIFIED, before, after)
                else -> null
            }
        }
    }

    private fun buildUnifiedDiff(changes: List<FileChange>): String {
        if (changes.isEmpty()) return ""
        return changes.mapNotNull { change ->
            val before = change.before
            val after = change.after
            when (change.status) {
                FileChangeStatus.ADDED -> unifiedDiff(change.path, change.path, null, after)
                FileChangeStatus.DELETED -> unifiedDiff(change.path, change.path, before, null)
                FileChangeStatus.MODIFIED -> unifiedDiff(change.path, change.path, before, after)
            }
        }.filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun normalizePath(path: String): String {
        val file = File(path)
        if (file.isAbsolute) {
            for (dir in projectDirectories) {
                val dirPath = Path(dir).toAbsolutePath().normalize()
                val abs = file.toPath().toAbsolutePath().normalize()
                if (abs.startsWith(dirPath)) {
                    return abs.relativeTo(dirPath).pathString
                }
            }
        }
        return path.replace("\\", "/")
    }

    private fun isTextFile(path: String): Boolean {
        val binaryExtensions = setOf(
            "exe", "dll", "so", "dylib", "bin", "png", "jpg", "jpeg", "gif", "bmp",
            "ico", "webp", "mp3", "mp4", "avi", "mov", "pdf", "zip", "tar", "gz",
            "rar", "7z", "jar", "class", "o", "a", "obj", "pdb"
        )
        val ext = path.substringAfterLast(".", "").lowercase()
        return ext !in binaryExtensions
    }
}

/**
 * 会话级 TurnDiffTracker 注册表：把子代理的文件改动归并进父会话当前活跃 turn。
 *
 * 修复"turn 结束显示的改动文件不含子代理改动"：子代理 TurnExecutor 的 diffStore 为 null，
 * 其 TurnDiff 在子 turn 结束时即丢弃。主 turn 开始时注册自己的 tracker（[register]），
 * 子代理 turn 结束时按 parentSessionId 查到父 tracker 并把 changes 合并进去（[mergeInto]）——
 * 主 turn 的 buildDiff 因此包含子代理改动（turnDiffSummary / diffStore 双双覆盖）。
 *
 * 归属语义：主 turn 结束后才完成的子代理改动，归入父会话下一个活跃 turn 的 diff
 * （此时该文件改动落在哪个 turn 就算哪个 turn 的）；无活跃 turn 则丢弃并记日志。
 */
object ParentDiffRegistry {
    private val trackers = java.util.concurrent.ConcurrentHashMap<String, TurnDiffTracker>()

    /** 主 turn 开始时注册；同会话新 turn 覆盖旧注册（旧 turn 必然已 unregister，兜底而已）。 */
    fun register(sessionId: String, tracker: TurnDiffTracker) {
        trackers[sessionId] = tracker
    }

    /** turn 结束（含异常/取消路径）注销，防止泄漏与误归并。 */
    fun unregister(sessionId: String) {
        trackers.remove(sessionId)
    }

    /** 子代理 turn 结束时调用：把其文件改动合并进父会话当前活跃 turn 的 tracker。 */
    fun mergeInto(parentSessionId: String, changes: List<FileChange>) {
        val parent = trackers[parentSessionId]
        if (parent == null) {
            if (changes.isNotEmpty()) {
                DebugLog.event(
                    "ParentDiffRegistry",
                    "parent turn not active, drop ${changes.size} sub-agent file changes (parentSessionId=$parentSessionId)"
                )
            }
            return
        }
        parent.mergeChanges(changes)
    }
}
