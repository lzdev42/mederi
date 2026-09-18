package xyz.mederi.project

import java.io.File
import java.io.IOException
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * AGENTS.md 加载器（代码级自动读取，非 AI 工具）。
 *
 * 两个职责（对照 opencode v2 的路径链模型）：
 * 1. 指令链：从项目目录**向上**探测 .git 得 git 根（无 .git 则项目目录自身），
 *    读取链上（git 根 → 项目目录，含两端）所有 AGENTS.md；再**向下**扫描项目目录的
 *    直接子目录（一层），读取其中的 AGENTS.md——AGENTS.md 不保证在项目根/git 根，
 *    可能在下一级子目录（如 mederi/AGENTS.md）、或项目目录下有多个平级子目录各有
 *    一份（如 A/{B,C}/AGENTS.md）。整体浅 → 深排列（浅层是一般规则，深层细化覆盖）。
 *    - 向上链：项目绑定嵌套子目录时（如 git 根下一层）不漏掉上层 AGENTS.md。
 *    - 向下层：只扫**直接子目录一层**，跳过隐藏目录（.git/.gradle/.idea/.mederi 等）
 *      与生成/依赖目录（build/node_modules/dist/out/coverage 等）——防止第三方依赖
 *      里海量 AGENTS.md 一次性撑爆上下文；更深的子树交给懒发现（见 2）。
 * 2. 子树懒发现：工具（read_file / list_directory）访问某个路径后，
 *    从该路径向上到项目目录（不含，项目目录自身已在指令链里）找最近的 AGENTS.md，
 *    找到则追加进该工具调用的返回文本——深层子目录的 AGENTS.md
 *    只有被实际触达的路径才会注入，避免一次性全读爆上下文。
 *
 * 读取带大小上限（默认 64KB），超限截断，防止异常大文件撑爆系统提示词。
 * 读取失败（IO 错误、非常规文件）静默跳过——AGENTS.md 是增强项，不是硬依赖。
 *
 * @param maxFileBytes 单个 AGENTS.md 的读取大小上限（字节）。
 */
class AgentsFileLoader(
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES
) {

    /**
     * 一个已读取的 AGENTS.md。
     *
     * @param path 绝对路径（规范化后）。
     * @param relativePath 相对项目目录的路径（可能含 `..`，用于提示词中展示）。
     * @param content 文件内容（超限已截断）。
     */
    data class AgentsFile(val path: String, val relativePath: String, val content: String)

    /**
     * 加载指令链：git 根（无 .git 则项目目录）→ 项目目录 → 项目目录直接子目录
     * 的所有 AGENTS.md，浅 → 深。
     *
     * 链上没有 AGENTS.md 时返回空列表。
     */
    fun loadInstructionChain(projectDirectory: String): List<AgentsFile> {
        val projectDir = normalizeDir(projectDirectory) ?: return emptyList()
        val chainStart = findGitRoot(projectDir)

        // ── 向上链：项目目录 → git 根（含），再反转为浅 → 深。──
        // git 根为文件系统根时 parentFile 为 null，generateSequence 自然终止。
        val dirs = generateSequence(projectDir) { it.parentFile }
            .takeWhile { it != chainStart.parentFile }
            .toList()
            .asReversed()

        val result = dirs.mapNotNull { dir ->
            readAgentsFile(File(dir, FILE_NAME), projectDir.toPath())
        }.toMutableList()

        // ── 向下层：项目目录的直接子目录（一层）里的 AGENTS.md。──
        // AGENTS.md 不保证在项目根/git 根，可能在下一级子目录；平级多目录各有一份
        // 也全部纳入。只扫一层、跳过隐藏/生成目录：更深的子树由 discoverSubtree
        // 懒发现按需注入，避免全量递归把 node_modules 等依赖里的 AGENTS.md 读爆。
        projectDir.listFiles()
            ?.filter { it.isDirectory }
            ?.filter { dir -> !dir.name.startsWith(".") && dir.name !in GENERATED_DIR_NAMES }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                readAgentsFile(File(dir, FILE_NAME), projectDir.toPath())?.let(result::add)
            }

        return result
    }

    /**
     * 子树懒发现：从工具访问路径向上到项目目录（不含）找最近的 AGENTS.md。
     *
     * - 访问路径在项目目录外、或就是项目目录本身、或其直接父级就是项目目录 → null
     *   （项目目录自身的 AGENTS.md 已由指令链注入，不重复）。
     * - 只向上找最近的一个：访问更深路径时，更深层的 AGENTS.md 会在后续触达时再发现。
     */
    fun discoverSubtree(projectDirectory: String, accessedPath: String): AgentsFile? {
        val projectDir = Path(projectDirectory).absolute().normalize()
        val accessed = Path(accessedPath).absolute().normalize()
        if (accessed == projectDir) return null
        if (!accessed.startsWith(projectDir)) return null

        var current = accessed.parent ?: return null
        while (current != projectDir) {
            val candidate = current.resolve(FILE_NAME)
            if (candidate.isRegularFile()) {
                return readAgentsFile(candidate.toFile(), projectDir)
            }
            current = current.parent ?: return null
        }
        return null
    }

    // ==================== 内部实现 ====================

    /** 找 git 根：从 [start] 向上找第一个含 .git 的目录；找不到则 [start] 自身。 */
    private fun findGitRoot(start: File): File {
        var current: File? = start
        while (current != null) {
            if (File(current, GIT_DIR).exists()) return current
            current = current.parentFile
        }
        return start
    }

    /** 读取单个 AGENTS.md：超限截断，读取失败返回 null。 */
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
        const val GIT_DIR = ".git"
        const val DEFAULT_MAX_FILE_BYTES: Long = 32 * 1024

        /** 向下扫描直接子目录时跳过的生成/依赖目录名（隐藏目录按 `.` 前缀另跳）。 */
        internal val GENERATED_DIR_NAMES = setOf(
            "build", "node_modules", "dist", "out", "coverage"
        )
    }
}
