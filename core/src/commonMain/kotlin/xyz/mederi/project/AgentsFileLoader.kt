package xyz.mederi.project

import java.io.File
import java.io.IOException
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * AGENTS.md 与 AGENTS.override.md 加载器（代码级自动读取，非 AI 工具）。
 *
 * 两个职责（对照 opencode v2 的路径链模型）：
 * 1. 指令链：从项目目录**向上**探测 .git 得 git 根（无 .git 则项目目录自身），
 *    读取链上（git 根 → 项目目录，含两端）所有指令文件；再**向下**扫描项目目录的
 *    直接子目录（一层），读取其中的指令文件。整体浅 → 深排列。
 *    每一级目录 `AGENTS.override.md` 优先于同目录 `AGENTS.md`，只加载其中一个。
 * 2. 子树懒发现：工具（read_file / list_directory）访问某个路径后，
 *    从该路径向上到项目目录（不含）找最近的指令文件（override 优先），
 *    黑名单过滤先于去重。
 *
 * @param maxFileBytes 单个指令文件的读取大小上限（字节）。
 * @param isPathDenied 路径黑名单判断。
 */
class AgentsFileLoader(
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val isPathDenied: (java.nio.file.Path) -> Boolean = { false },
) {

    /**
     * 一个已读取的指令文件（AGENTS.md 或 AGENTS.override.md）。
     *
     * @param path 绝对路径（规范化后）。
     * @param relativePath 相对项目目录的路径（可能含 `..`，用于提示词中展示）。
     * @param content 文件内容（超限已截断）。
     */
    data class AgentsFile(val path: String, val relativePath: String, val content: String)

    /**
     * 加载指令链：git 根（无 .git 则项目目录）→ 项目目录 → 项目目录直接子目录
     * 的所有指令文件，浅 → 深。每一级 override 优先于普通 AGENTS.md。
     *
     * 链上没有指令文件时返回空列表。
     */
    fun loadInstructionChain(projectDirectory: String): List<AgentsFile> {
        val projectDir = normalizeDir(projectDirectory) ?: return emptyList()
        val chainStart = findGitRoot(projectDir)

        // ── 向上链：项目目录 → git 根（含），再反转为浅 → 深。──
        val dirs = generateSequence(projectDir) { it.parentFile }
            .takeWhile { it != chainStart.parentFile }
            .toList()
            .asReversed()

        val result = dirs.mapNotNull { dir ->
            findAndReadAgentsFile(dir, projectDir.toPath())
        }.toMutableList()

        // ── 向下层：项目目录的直接子目录（一层）里的指令文件。──
        projectDir.listFiles()
            ?.filter { it.isDirectory }
            ?.filter { dir -> !dir.name.startsWith(".") && dir.name !in GENERATED_DIR_NAMES }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                findAndReadAgentsFile(dir, projectDir.toPath())?.let(result::add)
            }

        return result
    }

    /**
     * 子树懒发现：从工具访问路径向上到项目目录（不含）找最近的指令文件（AGENTS.override.md 优先于 AGENTS.md）。
     * 黑名单过滤先于去重。
     */
    fun discoverSubtree(projectDirectory: String, accessedPath: String): AgentsFile? {
        val projectDir = Path(projectDirectory).absolute().normalize()
        val accessed = Path(accessedPath).absolute().normalize()
        if (accessed == projectDir) return null
        if (!accessed.startsWith(projectDir)) return null

        var current = accessed.parent ?: return null
        while (current != projectDir) {
            val overrideCandidate = current.resolve(OVERRIDE_FILE_NAME)
            val normalCandidate = current.resolve(FILE_NAME)
            val candidate = when {
                overrideCandidate.isRegularFile() -> overrideCandidate
                normalCandidate.isRegularFile() -> normalCandidate
                else -> null
            }
            if (candidate != null) {
                val canon = candidate.toAbsolutePath().normalize()
                if (!isPathDenied(canon)) {
                    return readAgentsFile(candidate.toFile(), projectDir)
                }
                // 若被黑名单过滤，继续向上寻找上一级的有效指令文件
            }
            current = current.parent ?: return null
        }
        return null
    }

    // ==================== 内部实现 ====================

    private fun findAndReadAgentsFile(dir: File, projectDirPath: java.nio.file.Path): AgentsFile? {
        val overrideFile = File(dir, OVERRIDE_FILE_NAME)
        val regularFile = File(dir, FILE_NAME)

        val target = when {
            overrideFile.isFileSync() -> overrideFile
            regularFile.isFileSync() -> regularFile
            else -> null
        } ?: return null

        val canon = target.toPath().toAbsolutePath().normalize()
        if (isPathDenied(canon)) return null

        return readAgentsFile(target, projectDirPath)
    }

    /** 找 git 根：从 [start] 向上找第一个含 .git 的目录；找不到则 [start] 自身。 */
    private fun findGitRoot(start: File): File {
        var current: File? = start
        while (current != null) {
            if (File(current, GIT_DIR).exists()) return current
            current = current.parentFile
        }
        return start
    }

    /** 读取单个指令文件：超限截断，读取失败返回 null。 */
    private fun readAgentsFile(file: File, projectDir: java.nio.file.Path): AgentsFile? {
        if (!file.isFileSync()) return null
        return try {
            val text = file.readText()
            val content = if (file.length() > maxFileBytes) {
                text.take(maxFileBytes.toInt()) +
                    "\n... (truncated, file is ${file.length()} bytes, limit is $maxFileBytes)"
            } else {
                text
            }
            val path = file.toPath().absolute().normalize()
            AgentsFile(
                path = path.toString(),
                relativePath = path.relativeTo(projectDir).toString(),
                content = content
            )
        } catch (_: IOException) {
            null
        } catch (_: java.nio.file.InvalidPathException) {
            null
        }
    }

    private fun normalizeDir(directory: String): File? {
        val dir = Path(directory).absolute().normalize().toFile()
        return dir.takeIf { it.isDirectory }
    }

    /** java.io.File.isFile 会跟随符号链接，此处与 [isRegularFile] 语义对齐。 */
    private fun File.isFileSync(): Boolean {
        return try {
            toPath().isRegularFile()
        } catch (_: IOException) {
            false
        }
    }

    companion object {
        const val FILE_NAME = "AGENTS.md"
        const val OVERRIDE_FILE_NAME = "AGENTS.override.md"
        const val GIT_DIR = ".git"
        const val DEFAULT_MAX_FILE_BYTES: Long = 32 * 1024

        /** 向下扫描直接子目录时跳过的生成/依赖目录名（隐藏目录按 `.` 前缀另跳）。 */
        internal val GENERATED_DIR_NAMES = setOf(
            "build", "node_modules", "dist", "out", "coverage",
            "target", "vendor", "Pods", "__pycache__", "venv", ".venv"
        )
    }
}
