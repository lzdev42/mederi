package xyz.mederi.tools.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import xyz.mederi.workspace.VerifiedWorkspaceRoot

/**
 * 文件工具与 OS 沙盒共享的路径策略。只计算权限，不创建宿主文件或目录。
 * 保护路径必须在合并根之前发现，否则 /tmp 内的项目会被 /tmp 根吞掉。
 */
class SandboxPaths(
    projectDirs: List<String>,
    extraWritablePaths: List<String>,
    tempDirs: List<String> = defaultTempDirs(),
    protectedDirectories: List<String> = emptyList(),
    deniedPaths: List<String> = emptyList(),
    val verifiedWorkspaceRoot: VerifiedWorkspaceRoot? = null,
) {
    /** 显式拒绝比默认/额外可写范围优先；不存在的目标同样保留，不能被悄悄忽略。 */
    val deniedCanonicalPaths: List<Path> = buildList {
        val rawDenied = deniedPaths.map(::canonicalDeniedPath).distinct()
        val mappedDenied = buildList {
            addAll(rawDenied)
            if (verifiedWorkspaceRoot != null) {
                val origProjDirs = (projectDirs.map { File(it).canonicalFile.toPath() } +
                        listOf(verifiedWorkspaceRoot.artifactDirectory.parent)).distinct()
                rawDenied.forEach { denied ->
                    for (origProj in origProjDirs) {
                        if (denied.startsWith(origProj)) {
                            val rel = origProj.relativize(denied)
                            val mapped = verifiedWorkspaceRoot.workingDirectory.resolve(rel).toFile().canonicalFile.toPath()
                            add(mapped)
                        }
                    }
                }
            }
        }.distinct()
        mappedDenied.sortedBy { it.nameCount }.forEach { candidate ->
            if (none { candidate.startsWith(it) }) add(candidate)
        }
    }

    private val configuredRoots: List<Path> = if (verifiedWorkspaceRoot != null) {
        (listOf(verifiedWorkspaceRoot.workingDirectory.toString()) +
                verifiedWorkspaceRoot.verifiedArtifactWritablePaths.map { it.toString() } +
                extraWritablePaths).map(::canonicalRoot).distinct()
    } else {
        (projectDirs + extraWritablePaths).map(::canonicalRoot).distinct()
    }

    private val allRoots = (configuredRoots + tempDirs.map(::canonicalRoot)).distinct()

    val controlRoots: List<Path> = protectedDirectories.map(::canonicalRoot).distinct()

    val protectedNamesPaths: List<Path> = buildSet {
        configuredRoots.forEach { root ->
            // 如果显式白名单指向保护目录内部，也不能绕过其祖先的保护。
            generateSequence(root) { it.parent }.forEach { ancestor ->
                if (ancestor.fileName?.toString() in protectedNames && Files.exists(ancestor, NOFOLLOW_LINKS)) add(ancestor)
            }
            protectedNames.forEach { name ->
                val candidate = root.resolve(name)
                if (Files.exists(candidate, NOFOLLOW_LINKS)) {
                    add(candidate.toAbsolutePath().normalize())
                    add(candidate.toFile().canonicalFile.toPath())
                    if (name == ".git" && Files.isRegularFile(candidate, NOFOLLOW_LINKS)) {
                        val pointer = Files.readString(candidate).trim()
                        require(pointer.startsWith("gitdir:")) { "Invalid Git pointer file: $candidate" }
                        val target = pointer.removePrefix("gitdir:").trim()
                        require(target.isNotEmpty()) { "Empty Git pointer file: $candidate" }
                        val gitDir = candidate.parent.resolve(target).toFile().canonicalFile.toPath()
                        add(gitDir)
                        // linked worktree 的共用对象/配置目录也不能经另一可写根改写。
                        val common = gitDir.resolve("commondir")
                        if (Files.isRegularFile(common, NOFOLLOW_LINKS)) {
                            val commonTarget = Files.readString(common).trim()
                            require(commonTarget.isNotEmpty()) { "Empty Git common directory pointer: $common" }
                            add(gitDir.resolve(commonTarget).toFile().canonicalFile.toPath())
                        }
                    }
                }
            }
        }
    }.sortedBy { it.nameCount }

    val protectedPaths: List<Path> = (controlRoots + protectedNamesPaths).distinct().sortedBy { it.nameCount }

    val writableRoots: List<Path> = buildList {
        allRoots.sortedBy { it.nameCount }.forEach { candidate ->
            if (none { parent -> !Files.isRegularFile(parent, NOFOLLOW_LINKS) && candidate.startsWith(parent) }) add(candidate)
        }
    }

    fun allowsWrite(path: Path): Boolean {
        val canonical = path.toFile().canonicalFile.toPath()
        if (!allowsRead(canonical)) return false

        // 1. protectedNames (.git, .agents, .codex, .aws) 永远禁止写入
        if (protectedNamesPaths.any { canonical == it || canonical.startsWith(it) }) return false

        // 2. 检查 controlRoots (protectedDirectories)
        for (ctrl in controlRoots) {
            if (canonical == ctrl || canonical.startsWith(ctrl)) {
                val isVerifiedCheckout = verifiedWorkspaceRoot != null &&
                        (canonical == verifiedWorkspaceRoot.checkoutRoot || canonical.startsWith(verifiedWorkspaceRoot.checkoutRoot))
                val isVerifiedArtifact = verifiedWorkspaceRoot != null &&
                        verifiedWorkspaceRoot.verifiedArtifactWritablePaths.any { canonical == it || canonical.startsWith(it) }

                if (!isVerifiedCheckout && !isVerifiedArtifact) {
                    return false
                }
            }
        }

        // 3. 必须落在 writableRoots 内
        return writableRoots.any { root ->
            if (Files.isRegularFile(root, NOFOLLOW_LINKS)) canonical == root else canonical.startsWith(root)
        }
    }

    fun allowsRead(path: Path): Boolean {
        val canonical = path.toFile().canonicalFile.toPath()
        return deniedCanonicalPaths.none { canonical == it || canonical.startsWith(it) }
    }

    companion object {
        private val protectedNames = setOf(".git", ".agents", ".codex", ".aws")

        fun defaultTempDirs(): List<String> = listOfNotNull(
            if (!System.getProperty("os.name").lowercase().contains("windows")) "/tmp" else null,
            System.getenv("TMPDIR")?.takeIf { it.isNotBlank() },
            System.getenv("TEMP")?.takeIf { it.isNotBlank() },
            System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() },
        ).distinct()

        private fun canonicalRoot(raw: String): Path {
            require(raw.isNotBlank() && File(raw).isAbsolute) { "Sandbox writable roots must be nonblank absolute paths: $raw" }
            val path = File(raw).canonicalFile.toPath()
            require(path.parent != null && listOf("/proc", "/dev", "/sys").none {
                val forbidden = Path.of(it)
                path.startsWith(forbidden) || forbidden.startsWith(path)
            }) { "Sandbox writable roots cannot cover the filesystem root or kernel/device paths: $raw" }
            return path
        }

        private fun canonicalDeniedPath(raw: String): Path {
            require(raw.isNotBlank() && File(raw).isAbsolute) { "Sandbox denied paths must be nonblank absolute paths: $raw" }
            require(raw.none { it.code < 32 || it.code == 127 }) { "Control characters are not allowed in sandbox denied paths" }
            return File(raw).canonicalFile.toPath()
        }
    }
}
