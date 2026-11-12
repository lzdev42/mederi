package xyz.mederi.workspace

import xyz.mederi.config.MederiIdentity
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

data class IncludeCopyResult(
    val copied: List<CopiedEntry>,
    val skipped: List<SkippedEntry>,
)

data class CopiedEntry(
    val relativePath: String,
    val sourcePath: String,
    val targetPath: String,
    val bytes: Long,
)

data class SkippedEntry(
    val relativePath: String,
    val sourcePath: String,
    val targetPath: String,
    val reason: SkipReason,
)

enum class SkipReason {
    ALREADY_EXISTS,
    SYMLINK_OR_SYMLINK_PARENT,
    NOT_REGULAR_FILE,
    DENIED_BY_POLICY,
    PROTECTED_PATH,
    D09_ARTIFACT_PATH,
}

class WorkspaceIncludeService(
    private val gitDriver: GitWorkspaceDriver = GitWorkspaceDriver()
) {
    /**
     * 计算符合包含规则的候选文件相对路径列表。
     * 基于 Git 两次只读枚举求交集：
     * A = git ls-files --others --ignored --exclude-standard -z
     * B = git ls-files --others --ignored --exclude-from=<frozen-include> -z
     * 候选 = (A ∩ B) + 忽略的 AGENTS.override.md + 选定的 ignored 环境配置
     */
    suspend fun computeCandidates(
        repoRoot: String,
        includeRulesContent: String?,
        selectedEnvironmentFileRel: String? = null,
    ): Set<String> {
        val root = File(repoRoot).canonicalFile

        // 1. 枚举 A：真实 ignored 文件
        val resA = gitDriver.runGit(root.absolutePath, listOf("ls-files", "--others", "--ignored", "--exclude-standard", "-z"))
        val ignoredPaths = if (resA.exitCode == 0) parseNulStrings(resA.stdoutBytes).toSet() else emptySet()

        // 2. 枚举 B：仅针对 include 规则的匹配文件
        val matchedByInclude = if (!includeRulesContent.isNullOrBlank()) {
            val tempIncludeFile = File.createTempFile("worktreeinclude-", ".tmp")
            try {
                tempIncludeFile.writeText(includeRulesContent)
                val resB = gitDriver.runGit(
                    root.absolutePath,
                    listOf("ls-files", "--others", "--ignored", "--exclude-from=${tempIncludeFile.absolutePath}", "-z")
                )
                if (resB.exitCode == 0) {
                    parseNulStrings(resB.stdoutBytes).toSet()
                } else emptySet()
            } finally {
                tempIncludeFile.delete()
            }
        } else {
            emptySet()
        }

        val intersection = ignoredPaths.intersect(matchedByInclude).toMutableSet()

        // 3. 忽略的 AGENTS.override.md 无论是否在 include 均作为独立候选
        for (p in ignoredPaths) {
            val f = File(root, p)
            if (f.name == "AGENTS.override.md") {
                intersection.add(p)
            }
        }
        root.walkTopDown().forEach { f ->
            if (f.isFile && f.name == "AGENTS.override.md") {
                val rel = f.relativeTo(root).path.replace('\\', '/')
                if (!rel.startsWith(".git") && !rel.contains("/.git/")) {
                    intersection.add(rel)
                }
            }
        }

        // 4. 用户明确选择的环境配置文件若为 ignored，即使未列入 .worktreeinclude 也作为显式复制候选
        if (selectedEnvironmentFileRel != null) {
            val cleanEnv = selectedEnvironmentFileRel.trim().trimStart('/', '\\').replace('\\', '/')
            if (ignoredPaths.contains(cleanEnv)) {
                intersection.add(cleanEnv)
            }
        }

        return intersection
    }

    /**
     * 将候选包含文件从 sourceRepoRoot 复制到 targetRepoRoot。
     * 只复制 regular file，不跟随任一级 symlink，目标存在一律不覆盖；
     * 不复制 .git、控制目录、快照及 D09 可变运行产物，受路径黑名单约束。
     */
    suspend fun copyIncludeFiles(
        sourceRepoRoot: String,
        targetRepoRoot: String,
        candidateRelativePaths: Set<String>,
        deniedPaths: List<String> = emptyList(),
    ): IncludeCopyResult {
        val srcRoot = File(sourceRepoRoot).canonicalFile
        val tgtRoot = File(targetRepoRoot).canonicalFile
        val copied = mutableListOf<CopiedEntry>()
        val skipped = mutableListOf<SkippedEntry>()

        val canonDenied = deniedPaths.map { File(it).canonicalFile.toPath() }

        for (rel in candidateRelativePaths.sorted()) {
            val cleanRel = rel.replace('\\', '/')
            val srcFile = File(srcRoot, cleanRel)
            val tgtFile = File(tgtRoot, cleanRel)

            // 安全校验：禁止 traversal 与 .git 注入
            if (cleanRel.startsWith("/") || cleanRel.startsWith("../") || cleanRel.contains("/../") ||
                cleanRel == ".git" || cleanRel.startsWith(".git/") || cleanRel.contains("/.git/")
            ) {
                skipped.add(SkippedEntry(cleanRel, srcFile.path, tgtFile.path, SkipReason.PROTECTED_PATH))
                continue
            }

            // D09 运行产物不复制
            if (isD09Artifact(cleanRel)) {
                skipped.add(SkippedEntry(cleanRel, srcFile.path, tgtFile.path, SkipReason.D09_ARTIFACT_PATH))
                continue
            }

            // 黑名单拒绝
            val srcCanonPath = try {
                srcFile.canonicalFile.toPath()
            } catch (_: Exception) {
                srcFile.toPath()
            }
            if (canonDenied.any { srcCanonPath == it || srcCanonPath.startsWith(it) }) {
                skipped.add(SkippedEntry(cleanRel, srcFile.path, tgtFile.path, SkipReason.DENIED_BY_POLICY))
                continue
            }

            // 检查源路径及其祖先是否为符号链接（不跟随任一级 symlink）
            if (hasSymlinkInPath(srcRoot, srcFile)) {
                skipped.add(SkippedEntry(cleanRel, srcFile.path, tgtFile.path, SkipReason.SYMLINK_OR_SYMLINK_PARENT))
                continue
            }

            // 只复制 regular file
            if (!Files.isRegularFile(srcFile.toPath(), NOFOLLOW_LINKS)) {
                skipped.add(SkippedEntry(cleanRel, srcFile.path, tgtFile.path, SkipReason.NOT_REGULAR_FILE))
                continue
            }

            // 检查目标文件是否已存在（目标存在一律不覆盖）
            if (Files.exists(tgtFile.toPath(), NOFOLLOW_LINKS)) {
                skipped.add(SkippedEntry(cleanRel, srcFile.path, tgtFile.path, SkipReason.ALREADY_EXISTS))
                continue
            }

            // 检查目标祖先路径是否包含符号链接
            if (hasSymlinkInPath(tgtRoot, tgtFile.parentFile ?: tgtRoot)) {
                skipped.add(SkippedEntry(cleanRel, srcFile.path, tgtFile.path, SkipReason.SYMLINK_OR_SYMLINK_PARENT))
                continue
            }

            // 执行复制
            tgtFile.parentFile?.mkdirs()
            Files.copy(srcFile.toPath(), tgtFile.toPath())
            copied.add(
                CopiedEntry(
                    relativePath = cleanRel,
                    sourcePath = srcFile.absolutePath,
                    targetPath = tgtFile.absolutePath,
                    bytes = tgtFile.length()
                )
            )
        }

        return IncludeCopyResult(copied, skipped)
    }

    private fun hasSymlinkInPath(baseDir: File, file: File): Boolean {
        var curr: File? = file
        val basePath = baseDir.canonicalFile.toPath()
        while (curr != null) {
            val p = curr.toPath()
            if (Files.isSymbolicLink(p)) return true
            if (p == basePath) break
            curr = curr.parentFile
        }
        return false
    }

    private fun isD09Artifact(relPath: String): Boolean {
        val norm = relPath.replace('\\', '/')
        val wd = MederiIdentity.projectWorkdirName
        return norm == "$wd/notebook.md" ||
                norm.startsWith("$wd/plans/") ||
                norm.startsWith("$wd/plans-done/") ||
                norm.startsWith("$wd/plans-voided/") ||
                norm.startsWith("$wd/research/")
    }

    private fun parseNulStrings(bytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == 0.toByte()) {
                if (i > start) {
                    list.add(bytes.decodeToString(start, i))
                }
                start = i + 1
            }
        }
        if (start < bytes.size) {
            val rem = bytes.decodeToString(start, bytes.size).trim()
            if (rem.isNotEmpty()) list.add(rem)
        }
        return list
    }
}
