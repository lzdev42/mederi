package xyz.mederi.workspace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.api.exception.MederiWorkspaceException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Serializable
enum class SnapshotEntryKind {
    FILE,
    SYMLINK,
    DIRECTORY
}

@Serializable
enum class SnapshotEntryClass {
    TRACKED,
    UNTRACKED,
    IGNORED,
    ARTIFACT_EXCLUDED
}

@Serializable
data class SnapshotEntry(
    val repoRelativePath: String,
    val exists: Boolean = true,
    val kind: SnapshotEntryKind = SnapshotEntryKind.FILE,
    val unixMode: Int? = null,
    val size: Long = 0L,
    val sha256: String? = null,
    val linkTarget: String? = null,
    val entryClass: SnapshotEntryClass = SnapshotEntryClass.TRACKED
)

@Serializable
data class SnapshotIndexBlob(
    val gitOid: String,
    val gitMode: String,
    val sha256: String
)

@Serializable
data class SnapshotManifest(
    val schemaVersion: Int = 1,
    val snapshotId: String,
    val workspaceId: String,
    val checkoutRoot: String,
    val projectRelativePath: String,
    val gitObjectFormat: String = "sha1",
    val headOid: String,
    val headRef: String? = null,
    val baseCommit: String? = null,
    val indexSha256: String? = null,
    /** R12: `git ls-files --stage -z` 原始字节的 SHA-256——暂存区内容的语义指纹。 */
    val indexStageSha256: String? = null,
    val entries: List<SnapshotEntry>,
    val indexBlobs: List<SnapshotIndexBlob>,
    val reason: String,
    val createdAt: String,
    val contentFingerprint: String
)

enum class SnapshotCaptureType {
    TRANSFER,
    PREIMAGE
}

/**
 * 无损快照与恢复服务。
 */
open class WorkspaceSnapshotService(
    val snapshotsRoot: File,
    val gitDriver: GitWorkspaceDriver = GitWorkspaceDriver(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
) {
    init {
        snapshotsRoot.mkdirs()
    }

    /** Computes the complete backup scope independently of Git status and index flags. */
    open suspend fun computeFingerprint(checkoutRoot: String): String {
        val root = File(checkoutRoot)
        val head = gitDriver.runGit(root.path, listOf("rev-parse", "--verify", "HEAD^{commit}"))
        if (head.exitCode != 0 || head.stdoutString.isBlank()) {
            throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED, "Cannot read checkout HEAD: ${head.stderr}")
        }
        val headRef = readHeadRef(root)
        val index = resolveIndexFile(root)
        val indexSha = if (Files.notExists(index.toPath(), NOFOLLOW_LINKS)) null else fileSha256(index)
        val entries = walkNoFollow(root).map { file ->
            captureFileEntry(root, file.relativeTo(root).invariantSeparatorsPath, null, SnapshotEntryClass.IGNORED)
        }
        return fingerprint(head.stdoutString.trim(), headRef, indexSha, entries)
    }

    private suspend fun readHeadRef(root: File): String? {
        val ref = gitDriver.runGit(root.path, listOf("symbolic-ref", "-q", "HEAD"))
        return when (ref.exitCode) {
            0 -> ref.stdoutString.trim().takeIf { it.isNotEmpty() }
                ?: throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED, "Empty checkout HEAD ref")
            1 -> null // Git returns 1 for a detached HEAD.
            else -> throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED, "Cannot read checkout HEAD ref: ${ref.stderr}")
        }
    }

    private suspend fun resolveIndexFile(root: File): File {
        val index = gitDriver.runGit(root.path, listOf("rev-parse", "--git-path", "index"))
        val path = index.stdoutString.trim()
        if (index.exitCode != 0 || path.isEmpty()) {
            throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED, "Cannot locate checkout index: ${index.stderr}")
        }
        return File(path).let { if (it.isAbsolute) it else File(root, it.path) }
    }

    private fun fingerprint(
        headOid: String,
        headRef: String?,
        indexSha: String?,
        entries: Sequence<SnapshotEntry>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String?) {
            digest.update((value ?: "").toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
        }
        add("mederi-preimage-v2")
        add(headOid)
        add(headRef)
        add(indexSha)
        // Existing journals store the v2 fingerprint with host separators.
        for (entry in entries.filter { it.exists }.sortedBy { it.repoRelativePath.replace('/', File.separatorChar) }) {
            add(entry.repoRelativePath.replace('/', File.separatorChar))
            add(entry.kind.name)
            add(entry.sha256)
            add(entry.linkTarget)
            add(entry.unixMode?.toString())
        }
        return toHex(digest.digest())
    }

    /**
     * 捕获工作区完整快照。
     */
    suspend fun captureSnapshot(
        checkoutRoot: String,
        workspaceId: String,
        reason: String,
        captureType: SnapshotCaptureType = SnapshotCaptureType.TRANSFER,
        ignoredIncludePaths: Set<String> = emptySet(),
        snapshotId: String = "snap_${UUID.randomUUID().toString().take(12)}"
    ): SnapshotManifest {
        val root = File(checkoutRoot).canonicalFile
        val partialDir = File(snapshotsRoot, "$snapshotId.partial")
        partialDir.mkdirs()
        val blobsDir = File(partialDir, "blobs")
        blobsDir.mkdirs()

        try {
            // 1. 探测 HEAD
            val headRes = gitDriver.runGit(root.absolutePath, listOf("rev-parse", "--verify", "HEAD^{commit}"))
            if (headRes.exitCode != 0) {
                throw MederiWorkspaceException(
                    code = WorkspaceErrorCode.NO_BASE_COMMIT,
                    message = "Cannot snapshot repository without HEAD commit"
                )
            }
            val headOid = headRes.stdoutString.trim()

            val headRef = readHeadRef(root)

            // 2. 创建 git bundle
            val bundleFile = File(partialDir, "head.bundle")
            val bundleRes = gitDriver.runGit(
                root.absolutePath,
                listOf("bundle", "create", bundleFile.absolutePath, "HEAD")
            )
            if (bundleRes.exitCode != 0) {
                throw MederiWorkspaceException(
                    code = WorkspaceErrorCode.SNAPSHOT_INCOMPLETE,
                    message = "git bundle create failed: ${bundleRes.stderr}"
                )
            }

            val verifyRes = gitDriver.runGit(
                root.absolutePath,
                listOf("bundle", "verify", bundleFile.absolutePath)
            )
            if (verifyRes.exitCode != 0) {
                throw MederiWorkspaceException(
                    code = WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                    message = "git bundle verify failed: ${verifyRes.stderr}"
                )
            }
            val bundledHead = gitDriver.runGit(root.path, listOf("bundle", "list-heads", bundleFile.absolutePath))
            if (bundledHead.exitCode != 0 ||
                bundledHead.stdoutString.lineSequence().none { it.substringBefore(' ') == headOid }
            ) {
                throw MederiWorkspaceException(WorkspaceErrorCode.EXTERNAL_CHANGE, "Checkout HEAD changed during bundle capture")
            }

            // 3. 提取暂存区原字节与 stage0 blobs
            val idxFile = resolveIndexFile(root)
            var indexSha: String? = null
            // R12: 暂存区语义指纹（stage 条目内容），不受 git 自身 index 文件重写（stat 刷新）影响
            var indexStageSha256: String? = null
            val indexBlobs = mutableListOf<SnapshotIndexBlob>()

            if (!Files.notExists(idxFile.toPath(), NOFOLLOW_LINKS)) {
                val targetIndexBin = File(partialDir, "index.bin")
                idxFile.copyTo(targetIndexBin, overwrite = true)
                indexSha = fileSha256(targetIndexBin)

                val stageRes = gitDriver.runGit(root.absolutePath, listOf("ls-files", "--stage", "-z"))
                if (stageRes.exitCode != 0) {
                    throw MederiWorkspaceException(WorkspaceErrorCode.SNAPSHOT_INCOMPLETE, "Cannot read index entries: ${stageRes.stderr}")
                }
                indexStageSha256 = sha256Hex(stageRes.stdoutBytes)
                for (item in parseStageZ(stageRes.stdoutBytes)) {
                    if (item.stage != 0 || item.mode == "160000") {
                        throw MederiWorkspaceException(
                            WorkspaceErrorCode.UNSUPPORTED_REPOSITORY_STATE,
                            "Cannot snapshot index entry ${item.path} (mode=${item.mode}, stage=${item.stage})"
                        )
                    }
                    val catRes = gitDriver.runGit(root.absolutePath, listOf("cat-file", "blob", item.oid))
                    if (catRes.exitCode != 0) {
                        throw MederiWorkspaceException(WorkspaceErrorCode.SNAPSHOT_INCOMPLETE, "Cannot read index blob ${item.path}: ${catRes.stderr}")
                    }
                    val sha = sha256Hex(catRes.stdoutBytes)
                    val blobFile = File(blobsDir, sha)
                    if (!blobFile.exists()) blobFile.writeBytes(catRes.stdoutBytes)
                    indexBlobs.add(SnapshotIndexBlob(gitOid = item.oid, gitMode = item.mode, sha256 = sha))
                }
            }

            // 4. 收集条目与文件 blob
            val entries = mutableListOf<SnapshotEntry>()
            val visitedPaths = mutableSetOf<String>()

            // A: Tracked 与未暂存改动
            val statusRes = gitDriver.runGit(
                root.absolutePath,
                listOf("status", "--porcelain=v2", "-z", "--untracked-files=all")
            )
            if (statusRes.exitCode != 0) {
                throw MederiWorkspaceException(WorkspaceErrorCode.SNAPSHOT_INCOMPLETE, "Cannot read checkout status: ${statusRes.stderr}")
            }
            val statusEntries = gitDriver.parsePorcelainV2(statusRes.stdoutBytes)
            val statusByPath = statusEntries.associateBy { it.path }

            val trackedRes = gitDriver.runGit(root.absolutePath, listOf("ls-files", "-z"))
            if (trackedRes.exitCode != 0) {
                throw MederiWorkspaceException(WorkspaceErrorCode.SNAPSHOT_INCOMPLETE, "Cannot list tracked files: ${trackedRes.stderr}")
            }
            val trackedPaths = parseNulStrings(trackedRes.stdoutBytes).toSet()

            for (status in statusEntries) {
                if (status.origPath != null && !isD09ArtifactPath(status.origPath) &&
                    Files.notExists(File(root, status.origPath).toPath(), NOFOLLOW_LINKS) &&
                    visitedPaths.add(status.origPath)
                ) {
                    entries.add(
                        SnapshotEntry(
                            repoRelativePath = status.origPath,
                            exists = false,
                            entryClass = SnapshotEntryClass.TRACKED
                        )
                    )
                }
            }

            for (p in (trackedPaths + statusEntries.map { it.path }).distinct()) {
                if (p.isBlank() || isD09ArtifactPath(p) || !visitedPaths.add(p)) continue
                val status = statusByPath[p]
                val file = File(root, p)
                val missing = Files.notExists(file.toPath(), NOFOLLOW_LINKS)

                if (missing && (p in trackedPaths || status?.stagedStatus == 'D' || status?.unstagedStatus == 'D')) {
                    // Git can omit missing tracked files from status due to index flags.
                    entries.add(
                        SnapshotEntry(
                            repoRelativePath = p,
                            exists = false,
                            entryClass = SnapshotEntryClass.TRACKED
                        )
                    )
                } else if (!missing) {
                    val entryClass = when {
                        status?.isUntracked == true || (!trackedPaths.contains(p) && status?.isIgnored != true) -> SnapshotEntryClass.UNTRACKED
                        status?.isIgnored == true -> SnapshotEntryClass.IGNORED
                        else -> SnapshotEntryClass.TRACKED
                    }
                    val entry = captureFileEntry(root, p, blobsDir, entryClass)
                    entries.add(entry)
                }
            }

            // PREIMAGE and its verification walk the same scope, including ignored files and directories.
            if (captureType == SnapshotCaptureType.PREIMAGE) {
                walkNoFollow(root).forEach { file ->
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    if (visitedPaths.add(path)) {
                        entries.add(captureFileEntry(root, path, blobsDir, SnapshotEntryClass.IGNORED))
                    }
                }
            } else {
                // TRANSFER 模式捕获 include 的 ignored 文件
                for (p in ignoredIncludePaths) {
                    val f = File(root, p)
                    if (f.exists() && !visitedPaths.contains(p) && !isD09ArtifactPath(p)) {
                        val entry = captureFileEntry(root, p, blobsDir, SnapshotEntryClass.IGNORED)
                        entries.add(entry)
                        visitedPaths.add(p)
                    }
                }
            }

            val prefixRes = gitDriver.runGit(root.absolutePath, listOf("rev-parse", "--show-prefix"))
            if (prefixRes.exitCode != 0) {
                throw MederiWorkspaceException(WorkspaceErrorCode.SNAPSHOT_INCOMPLETE, "Cannot read checkout prefix: ${prefixRes.stderr}")
            }
            val projectRel = prefixRes.stdoutString.trim().trimEnd('/')

            val contentFingerprint = computeFingerprint(checkoutRoot)
            if (captureType == SnapshotCaptureType.PREIMAGE &&
                contentFingerprint != fingerprint(headOid, headRef, indexSha, entries.asSequence())
            ) {
                throw MederiWorkspaceException(
                    WorkspaceErrorCode.EXTERNAL_CHANGE,
                    "Checkout changed during snapshot capture: ${root.path}"
                )
            }
            val manifest = SnapshotManifest(
                schemaVersion = 1,
                snapshotId = snapshotId,
                workspaceId = workspaceId,
                checkoutRoot = root.absolutePath,
                projectRelativePath = projectRel,
                headOid = headOid,
                headRef = headRef,
                indexSha256 = indexSha,
                indexStageSha256 = indexStageSha256,
                entries = entries.sortedBy { it.repoRelativePath },
                indexBlobs = indexBlobs,
                reason = reason,
                createdAt = Instant.now().toString(),
                contentFingerprint = contentFingerprint
            )

            // 写入 manifest.json
            val manifestFile = File(partialDir, "manifest.json")
            manifestFile.writeText(json.encodeToString(SnapshotManifest.serializer(), manifest))

            // 原子重命名为正式快照
            val finalSnapshotDir = File(snapshotsRoot, snapshotId)
            if (finalSnapshotDir.exists()) finalSnapshotDir.deleteRecursively()
            val renamed = partialDir.renameTo(finalSnapshotDir)
            if (!renamed) {
                partialDir.copyRecursively(finalSnapshotDir, overwrite = true)
                partialDir.deleteRecursively()
            }

            return manifest
        } catch (e: Exception) {
            partialDir.deleteRecursively()
            throw e
        }
    }

    private fun captureFileEntry(
        root: File,
        relPath: String,
        blobsDir: File?,
        entryClass: SnapshotEntryClass
    ): SnapshotEntry {
        val file = File(root, relPath)
        val nioPath = file.toPath()

        if (!noSymlinkAncestor(file, root)) {
            throw MederiWorkspaceException(WorkspaceErrorCode.EXTERNAL_CHANGE, "Snapshot path has a symbolic-link parent: $relPath")
        }
        val attrs = Files.readAttributes(nioPath, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (attrs.isSymbolicLink) {
            val linkTarget = Files.readSymbolicLink(nioPath).toString()
            return SnapshotEntry(
                repoRelativePath = relPath,
                exists = true,
                kind = SnapshotEntryKind.SYMLINK,
                linkTarget = linkTarget,
                entryClass = entryClass
            )
        }

        if (attrs.isDirectory) {
            return SnapshotEntry(repoRelativePath = relPath, kind = SnapshotEntryKind.DIRECTORY, entryClass = entryClass)
        }
        if (!attrs.isRegularFile) {
            throw MederiWorkspaceException(WorkspaceErrorCode.UNSUPPORTED_FILE_TYPE, "Cannot snapshot $relPath")
        }

        val sha = if (blobsDir == null) {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(nioPath, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            toHex(digest.digest())
        } else {
            val bytes = Files.newInputStream(nioPath, NOFOLLOW_LINKS).use { it.readBytes() }
            sha256Hex(bytes).also { hash ->
                val blob = File(blobsDir, hash)
                if (!blob.exists()) blob.writeBytes(bytes)
            }
        }
        val mode = if (file.canExecute()) 493 /* 0755 */ else 420 /* 0644 */
        return SnapshotEntry(
            repoRelativePath = relPath,
            exists = true,
            kind = SnapshotEntryKind.FILE,
            unixMode = mode,
            size = attrs.size(),
            sha256 = sha,
            entryClass = entryClass
        )
    }

    /**
     * 将快照恢复到干净的目标工作区。
     */
    suspend fun restoreToCleanCheckout(
        snapshotId: String,
        targetCheckoutRoot: String
    ) {
        val snapshotDir = File(snapshotsRoot, snapshotId)
        val manifestFile = File(snapshotDir, "manifest.json")
        if (!manifestFile.exists()) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                message = "Missing snapshot manifest for $snapshotId"
            )
        }

        val manifest = json.decodeFromString(SnapshotManifest.serializer(), manifestFile.readText())
        validateManifest(manifest, snapshotDir)

        val targetRoot = File(targetCheckoutRoot).canonicalFile
        targetRoot.mkdirs()

        // 1. 初始化 Git 仓库并解包 bundle
        val bundleFile = File(snapshotDir, "head.bundle")
        if (!bundleFile.exists()) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                message = "Missing head.bundle in snapshot $snapshotId"
            )
        }

        val initRes = gitDriver.runGit(targetRoot.absolutePath, listOf("init"))
        if (initRes.exitCode != 0) {
            throw MederiWorkspaceException(code = WorkspaceErrorCode.IO_ERROR, message = "git init failed")
        }

        // 解包 bundle
        val fetchRes = gitDriver.runGit(
            targetRoot.absolutePath,
            listOf("bundle", "unbundle", bundleFile.absolutePath)
        )
        if (fetchRes.exitCode != 0) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                message = "git bundle unbundle failed: ${fetchRes.stderr}"
            )
        }

        // 检出 detached commit
        val checkoutRes = gitDriver.runGit(
            targetRoot.absolutePath,
            listOf("checkout", "--detach", manifest.headOid)
        )
        if (checkoutRes.exitCode != 0) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.IO_ERROR,
                message = "git checkout --detach failed: ${checkoutRes.stderr}"
            )
        }

        // 2-4. 物化快照内容到目标工作区
        materializeSnapshotIntoCheckout(snapshotDir, manifest, targetRoot)
    }

    /**
     * RF-06：将快照恢复到原仓库的 linked worktree（不重建独立仓库）。
     * 通过 `git worktree add --detach` 在原仓库 common-dir 注册目标 checkout，
     * 再把快照文件/index/blob 物化进去；恢复后的 checkout 与原仓库共享对象库，
     * 可提交原仓库可解析的对象，并支持再次归档/恢复。正式受管恢复必须走本方法；
     * [restoreToCleanCheckout] 仅用于自包含快照验证/明确导出。
     */
    suspend fun restoreToLinkedWorktree(
        snapshotId: String,
        targetCheckoutRoot: String,
        gitCommonDir: String
    ) {
        val snapshotDir = File(snapshotsRoot, snapshotId)
        val manifestFile = File(snapshotDir, "manifest.json")
        if (!manifestFile.exists()) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                message = "Missing snapshot manifest for $snapshotId"
            )
        }

        val manifest = json.decodeFromString(SnapshotManifest.serializer(), manifestFile.readText())
        validateManifest(manifest, snapshotDir)

        val targetPath = File(targetCheckoutRoot).toPath().toAbsolutePath().normalize()
        val targetRoot = targetPath.toFile()
        if (targetRoot.canonicalFile.toPath() != targetPath ||
            (Files.exists(targetPath, NOFOLLOW_LINKS) &&
                (!Files.isDirectory(targetPath, NOFOLLOW_LINKS) || targetRoot.list()?.isEmpty() != true))
        ) {
            throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED, "Restore target must be an empty directory: $targetCheckoutRoot")
        }
        targetRoot.parentFile?.mkdirs()

        // 1. 在原仓库 common-dir 注册 detached linked worktree（不做 git init / bundle unbundle）
        val commonDirFile = File(gitCommonDir)
        if (!commonDirFile.exists()) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.WORKSPACE_MISSING,
                message = "Cannot restore $snapshotId: original git common directory $gitCommonDir does not exist"
            )
        }
        val commit = gitDriver.runGit(gitCommonDir, listOf("cat-file", "-e", "${manifest.headOid}^{commit}"))
        if (commit.exitCode != 0) {
            val unbundle = gitDriver.runGit(gitCommonDir, listOf("bundle", "unbundle", File(snapshotDir, "head.bundle").absolutePath))
            if (unbundle.exitCode != 0) {
                throw MederiWorkspaceException(WorkspaceErrorCode.SNAPSHOT_CORRUPT, "Cannot recover snapshot commit: ${unbundle.stderr}")
            }
        }
        val addRes = gitDriver.runGit(
            gitCommonDir,
            listOf("worktree", "add", "--detach", targetRoot.absolutePath, manifest.headOid)
        )
        if (addRes.exitCode != 0) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.RECOVERY_REQUIRED,
                message = "git worktree add failed for $targetCheckoutRoot: ${addRes.stderr}"
            )
        }

        // 2-4. 物化快照内容（覆盖 worktree add 检出的 HEAD 状态，快照可能含未提交变更）
        materializeSnapshotIntoCheckout(snapshotDir, manifest, targetRoot)
    }

    /**
     * 将快照内容物化到已存在且可用的 git 仓库工作区：
     * 写入暂存区 blobs (hash-object -w --stdin)、物化条目（文件/目录/软链接/墓碑）、原子恢复 index.bin。
     * 目标必须已经是 git 仓库（独立 init 或 linked worktree），本方法不改变仓库注册/元数据。
     */
    internal suspend fun materializeSnapshotIntoCheckout(
        snapshotDir: File,
        manifest: SnapshotManifest,
        targetRoot: File
    ) {
        // 2. 写入暂存区专用 blobs (hash-object -w --stdin)
        val blobsDir = File(snapshotDir, "blobs")
        for (blob in manifest.indexBlobs) {
            val blobFile = File(blobsDir, blob.sha256)
            if (!blobFile.exists()) {
                throw MederiWorkspaceException(
                    code = WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                    message = "Missing blob ${blob.sha256}"
                )
            }
            val bytes = blobFile.readBytes()
            val pb = ProcessBuilder("git", "hash-object", "-w", "--stdin")
            pb.directory(targetRoot)
            val p = pb.start()
            p.outputStream.write(bytes)
            p.outputStream.flush()
            p.outputStream.close()
            val out = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            if (code != 0 || out != blob.gitOid) {
                throw MederiWorkspaceException(
                    code = WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                    message = "hash-object failed for blob ${blob.gitOid}"
                )
            }
        }

        // 3. 物化条目 (文件、目录、软链接、模式、墓碑删除)
        // T07: 统一安全落盘原语 (safeMaterializeFile/safeMaterializeSymlink/safeDeleteEntry)：
        // 每个落盘路径逐组件检查祖先不为符号链接，且目标本身不为符号链接，杜绝写穿到链接目标（工作区外）。
        val orderedEntries = manifest.entries.filterNot { it.exists }.sortedByDescending { snapshotPath(it.repoRelativePath).count { ch -> ch == '/' } } +
            manifest.entries.filter { it.exists }.sortedBy { snapshotPath(it.repoRelativePath).count { ch -> ch == '/' } }
        for (entry in orderedEntries) {
            val dest = File(targetRoot, entry.repoRelativePath)
            if (!noSymlinkAncestor(dest, targetRoot)) {
                throw MederiWorkspaceException(
                    WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                    "Refusing to materialize '${entry.repoRelativePath}': a parent path is a symbolic link"
                )
            }
            if (!entry.exists) {
                safeDeleteEntry(dest, targetRoot)
            } else when (entry.kind) {
                SnapshotEntryKind.DIRECTORY -> {
                    if (Files.exists(dest.toPath(), NOFOLLOW_LINKS) && !Files.isDirectory(dest.toPath(), NOFOLLOW_LINKS)) {
                        safeDeleteEntry(dest, targetRoot)
                    }
                    Files.createDirectories(dest.toPath())
                }
                SnapshotEntryKind.SYMLINK -> safeMaterializeSymlink(dest, entry.linkTarget!!, targetRoot)
                SnapshotEntryKind.FILE -> {
                    // T07 (R05 推广): 目标本身若为 symlink（如 HEAD 检出的 symlink）先删链接本身再写文件，
                    // 否则 writeBytes 会写穿到链接目标（工作区外文件）；目标为目录（类型互换）先删目录。
                    val blobFile = File(blobsDir, entry.sha256!!)
                    safeMaterializeFile(dest, blobFile.readBytes(), entry.unixMode, targetRoot)
                }
            }
        }

        // 4. 原子恢复 index.bin
        val indexBin = File(snapshotDir, "index.bin")
        if (indexBin.exists()) {
            val targetIndexRes = gitDriver.runGit(targetRoot.absolutePath, listOf("rev-parse", "--git-path", "index"))
            if (targetIndexRes.exitCode == 0) {
                val idxPath = targetIndexRes.stdoutString.trim()
                val targetIndex = File(idxPath).let { if (it.isAbsolute) it else File(targetRoot, it.path) }
                targetIndex.parentFile?.mkdirs()
                indexBin.copyTo(targetIndex, overwrite = true)
            }
        }
    }

    /**
     * 加载指定快照的 Manifest。
     */
    fun loadManifest(snapshotId: String): SnapshotManifest {
        val manifestFile = File(snapshotsRoot, "$snapshotId/manifest.json")
        if (!manifestFile.exists()) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                message = "Missing snapshot manifest for $snapshotId"
            )
        }
        return json.decodeFromString(SnapshotManifest.serializer(), manifestFile.readText())
    }

    /**
     * 原位置回滚：仅针对 journal 记录的路径回滚，外部新增文件严禁删除。
     */
    suspend fun rollbackCheckout(
        checkoutRoot: String,
        preimageSnapshotId: String,
        journalRecords: List<WorkspacePhaseRecord>
    ) {
        val snapshotDir = File(snapshotsRoot, preimageSnapshotId)
        val manifestFile = File(snapshotDir, "manifest.json")
        if (!manifestFile.exists()) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.RECOVERY_REQUIRED,
                message = "Missing preimage snapshot manifest"
            )
        }
        val manifest = json.decodeFromString(SnapshotManifest.serializer(), manifestFile.readText())
        val preimageByPath = manifest.entries.associateBy { snapshotPath(it.repoRelativePath) }
        val root = File(checkoutRoot).canonicalFile
        val blobsDir = File(snapshotDir, "blobs")

        // 收集受影响路径：同一路径出现在多个阶段记录时以后一个阶段的 postimage 为准
        // （如 MATERIALIZE 后 SETUP 又改写了同一文件——最近的记录才是该路径的最终后像）。
        val affectedPaths = journalRecords
            .flatMap { it.affectedPaths }
            .fold(mutableMapOf<String, PhasePathRecord>()) { acc, record ->
                val path = snapshotPath(record.repoRelativePath)
                acc[path] = if (path == record.repoRelativePath) record else record.copy(repoRelativePath = path)
                acc
            }
            .values
            .toList()

        // 第一遍：全量核对每个受影响路径的当前 type/hash/exists 与 journal 记录的 postimage 是否一致。
        // 任一未知变化（用户/IDE 在操作后改了同路径文件）→ RECOVERY_REQUIRED，零覆盖，不动任何文件。
        data class CurrentState(val exists: Boolean, val type: String?, val hash: String?)
        val currentStates = HashMap<String, CurrentState>()
        for (record in affectedPaths) {
            val p = record.repoRelativePath
            val path = File(root, p)
            if (!noSymlinkAncestor(path, root)) {
                throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED, "Unsafe rollback path: $p")
            }
            val nioPath = path.toPath()
            val isSym = Files.isSymbolicLink(nioPath)
            val exists = isSym || Files.exists(nioPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            val type = when {
                !exists -> null
                isSym -> "symlink"
                Files.isDirectory(nioPath, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> "directory"
                else -> "file"
            }
            val hash = when (type) {
                "file" -> fileSha256(File(root, p))
                "symlink" -> sha256Hex(Files.readSymbolicLink(nioPath).toString().toByteArray())
                else -> null
            }
            currentStates[p] = CurrentState(exists, type, hash)

            if (exists != record.postExists) {
                throw MederiWorkspaceException(
                    WorkspaceErrorCode.RECOVERY_REQUIRED,
                    "Rollback aborted: '$p' existence changed since operation (postimage=${record.postExists}, current=$exists)"
                )
            }
            if (record.postExists) {
                if (record.postType != null && !record.postType.equals(type, ignoreCase = true)) {
                    throw MederiWorkspaceException(
                        WorkspaceErrorCode.RECOVERY_REQUIRED,
                        "Rollback aborted: '$p' type changed since operation (postimage=${record.postType}, current=$type)"
                    )
                }
                if (record.postHash != null && record.postHash != hash) {
                    throw MederiWorkspaceException(
                        WorkspaceErrorCode.RECOVERY_REQUIRED,
                        "Rollback aborted: '$p' content changed since operation (postimage mismatch)"
                    )
                }
            }
        }

        // 第二遍：postimage 全部匹配，安全应用。仅处理本操作路径：
        // - 操作创建（preimage 不存在）且当前仍匹配 postimage → 删除该路径；
        // - preimage 存在 → 恢复原内容。
        // T07: 统一安全落盘原语；祖先链与目标本身的符号链接检查均在原语内部完成，杜绝写穿。
        for (record in affectedPaths) {
            val path = record.repoRelativePath
            val before = preimageByPath[path]
            if (currentStates[path]?.type != "directory" || (before?.exists == true && before.kind == SnapshotEntryKind.DIRECTORY)) continue
            Files.newDirectoryStream(File(root, path).toPath()).use { children ->
                for (child in children) {
                    val childPath = child.toFile().relativeTo(root).invariantSeparatorsPath
                    if (childPath !in currentStates || preimageByPath[childPath]?.exists == true) {
                        throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED, "Rollback found an unowned directory entry: $childPath")
                    }
                }
            }
        }
        val removals = affectedPaths.filter { preimageByPath[it.repoRelativePath]?.exists != true }
            .sortedByDescending { it.repoRelativePath.count { ch -> ch == '/' } }
        val restorations = affectedPaths.filter { preimageByPath[it.repoRelativePath]?.exists == true }
            .sortedBy { it.repoRelativePath.count { ch -> ch == '/' } }
        for (record in removals + restorations) {
            val p = record.repoRelativePath
            val currentFile = File(root, p)
            val preimage = preimageByPath[p]

            if (preimage == null || !preimage.exists) {
                safeDeleteEntry(currentFile, root)
            } else when (preimage.kind) {
                SnapshotEntryKind.SYMLINK -> safeMaterializeSymlink(currentFile, preimage.linkTarget!!, root)
                // T07 核心修复: current 若为 symlink（postimage 匹配），先删链接本身再写文件，
                // 绝不 writeBytes 写穿到链接目标；current 为目录（DIR→FILE 类型互换）先删目录再写。
                SnapshotEntryKind.FILE -> {
                    val blobFile = File(blobsDir, preimage.sha256!!)
                    safeMaterializeFile(currentFile, blobFile.readBytes(), preimage.unixMode, root)
                }
                // T07 新增: preimage 为 DIRECTORY 的类型互换（FILE/SYMLINK→DIR）——先删非目录条目再建目录。
                SnapshotEntryKind.DIRECTORY -> {
                    if (Files.isSymbolicLink(currentFile.toPath()) || (currentFile.exists() && !currentFile.isDirectory)) {
                        safeDeleteEntry(currentFile, root)
                    }
                    Files.createDirectories(currentFile.toPath())
                }
            }
        }

        // 恢复 index
        val indexBin = File(snapshotDir, "index.bin")
        if (indexBin.exists()) {
            val targetIndexRes = gitDriver.runGit(root.absolutePath, listOf("rev-parse", "--git-path", "index"))
            if (targetIndexRes.exitCode == 0) {
                val idxPath = targetIndexRes.stdoutString.trim()
                val targetIndex = File(idxPath).let { if (it.isAbsolute) it else File(root, it.path) }
                indexBin.copyTo(targetIndex, overwrite = true)
            }
        }
    }

    /**
     * 不跟随符号链接的文件树遍历：符号链接（无论指向目录还是文件）作为叶子返回，不递归其 target。
     * 用于 PREIMAGE 残留捕获，避免把链接目标目录内的内容当作工作区文件记录。
     */
    private fun walkNoFollow(root: File): Sequence<File> {
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) {
            throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_MISSING, "Cannot enumerate checkout ${root.path}")
        }
        return sequence {
            suspend fun SequenceScope<File>.visit(dir: File) {
                val children = Files.newDirectoryStream(dir.toPath()).use { it.toList() }
                for (path in children) {
                    val file = path.toFile()
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    if (file.name == ".git" || isD09ArtifactPath(relative)) continue
                    yield(file)
                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (attrs.isDirectory) visit(file)
                }
            }
            visit(root)
        }
    }

    /**
     * 物化前逐组件祖先检查：任一祖先是符号链接则拒绝写盘，防止写穿到链接目标（工作区外）。
     * 返回 true 表示安全可写；false 表示存在符号链接祖先，调用方应抛 SNAPSHOT_CORRUPT/RECOVERY_REQUIRED。
     */
    internal fun noSymlinkAncestor(dest: File, root: File): Boolean {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val destination = dest.toPath().toAbsolutePath().normalize()
        if (destination == rootPath || !destination.startsWith(rootPath) || Files.isSymbolicLink(rootPath)) return false
        var current = dest.parentFile
        while (current != null && current.toPath().toAbsolutePath().normalize() != rootPath) {
            if (Files.isSymbolicLink(current.toPath())) return false
            current = current.parentFile
        }
        return true
    }

    /**
     * T07: 统一安全落盘原语（materialize/rollback 共用）：
     * 1) 祖先路径任一为符号链接 → 拒绝写入（防止经父级链接逃逸到工作区外）；
     * 2) 目标本身为符号链接 → 先删链接本身（T07 核心: writeBytes 绝不写穿到链接目标）；
     * 3) 目标为目录（DIR→FILE 类型互换）→ 先删目录再写文件；
     * 4) 写入后按 preimage unixMode 恢复可执行位。
     */
    internal fun safeMaterializeFile(dest: File, bytes: ByteArray, unixMode: Int?, root: File) {
        if (!noSymlinkAncestor(dest, root)) {
            throw MederiWorkspaceException(
                WorkspaceErrorCode.RECOVERY_REQUIRED,
                "Refusing to write '${dest.name}': a parent path is a symbolic link"
            )
        }
        dest.parentFile?.mkdirs()
        if (Files.isDirectory(dest.toPath(), NOFOLLOW_LINKS)) safeDeleteEntry(dest, root)
        val staging = Files.createTempFile(dest.parentFile.toPath(), ".mederi-write-", ".tmp")
        try {
            Files.write(staging, bytes)
            if (unixMode != null) staging.toFile().setExecutable((unixMode and 73) != 0, false)
            if (!noSymlinkAncestor(dest, root)) {
                throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED, "Destination parent changed during write")
            }
            Files.move(staging, dest.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    /**
     * T07: 统一安全建链原语：祖先链检查后，目标上任何已存在条目（文件/目录/链接）先删除，再建符号链接。
     */
    internal fun safeMaterializeSymlink(dest: File, linkTarget: String, root: File) {
        if (!noSymlinkAncestor(dest, root)) {
            throw MederiWorkspaceException(
                WorkspaceErrorCode.RECOVERY_REQUIRED,
                "Refusing to write '${dest.name}': a parent path is a symbolic link"
            )
        }
        dest.parentFile?.mkdirs()
        safeDeleteEntry(dest, root)
        Files.createSymbolicLink(dest.toPath(), Paths.get(linkTarget))
    }

    /**
     * 只删除单个条目。目录有未处理的子项时保留并要求恢复，不递归覆盖未知内容。
     */
    internal fun safeDeleteEntry(dest: File, root: File) {
        if (!noSymlinkAncestor(dest, root)) {
            throw MederiWorkspaceException(
                WorkspaceErrorCode.RECOVERY_REQUIRED,
                "Refusing to delete '${dest.name}': a parent path is a symbolic link"
            )
        }
        try {
            Files.deleteIfExists(dest.toPath())
        } catch (e: java.nio.file.DirectoryNotEmptyException) {
            throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED, "Refusing to delete a non-empty directory: ${dest.name}")
        }
    }

    private fun validateManifest(manifest: SnapshotManifest, snapshotDir: File) {
        if (manifest.schemaVersion != 1) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                message = "Unsupported schema version ${manifest.schemaVersion}"
            )
        }

        val blobsDir = File(snapshotDir, "blobs")
        val seen = mutableMapOf<String, SnapshotEntry>()
        for (entry in manifest.entries) {
            val p = snapshotPath(entry.repoRelativePath)
            if (p.isEmpty() || p.contains("\u0000") || File(p).toPath().root != null || p.split('/').any { it == ".." }) {
                throw MederiValidationException("Path traversal or illegal path detected in snapshot manifest: $p")
            }
            if (p.split('/').any { it.equals(".git", ignoreCase = File.separatorChar == '\\') }) {
                throw MederiValidationException("Illegal .git entry in snapshot manifest: $p")
            }
            // 拒绝重复路径与类型不一致
            val prior = seen[p]
            if (prior != null) {
                if (prior.kind != entry.kind || prior.exists != entry.exists) {
                    throw MederiWorkspaceException(
                        WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                        "Conflicting manifest entries for path: $p"
                    )
                }
            }
            seen[p] = entry
            // 拒绝父文件 / 父符号链接 与子路径共存：子路径无法在不写穿或覆盖父实体的前提下物化
            var parent = p
            while (parent.contains("/")) {
                parent = parent.substringBeforeLast('/')
                val parentEntry = seen[parent]
                if (parentEntry != null && parentEntry.exists && parentEntry.kind != SnapshotEntryKind.DIRECTORY) {
                    throw MederiWorkspaceException(
                        WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                        "Manifest declares path '$p' under non-directory parent '$parent' (kind=${parentEntry.kind})"
                    )
                }
            }
            if (entry.exists && entry.kind == SnapshotEntryKind.FILE) {
                val sha = entry.sha256
                    ?: throw MederiWorkspaceException(WorkspaceErrorCode.SNAPSHOT_CORRUPT, "Missing sha256 on $p")
                val blobFile = File(blobsDir, sha)
                if (!blobFile.exists() || fileSha256(blobFile) != sha) {
                    throw MederiWorkspaceException(
                        code = WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                        message = "Corrupt blob or digest mismatch for $p"
                    )
                }
            }
        }
        // 二次全量父级校验：覆盖条目未按路径排序（父在后、子在前）的情况。
        for (entry in manifest.entries) {
            val p = snapshotPath(entry.repoRelativePath)
            var parent = p
            while (parent.contains("/")) {
                parent = parent.substringBeforeLast('/')
                val parentEntry = seen[parent]
                if (parentEntry != null && parentEntry.exists && parentEntry.kind != SnapshotEntryKind.DIRECTORY) {
                    throw MederiWorkspaceException(
                        WorkspaceErrorCode.SNAPSHOT_CORRUPT,
                        "Manifest declares path '$p' under non-directory parent '$parent' (kind=${parentEntry.kind})"
                    )
                }
            }
        }
    }

    // Windows snapshots written before Git-style path normalization may contain native separators.
    private fun snapshotPath(path: String): String = path.replace(File.separatorChar, '/')

    companion object {
        fun isD09ArtifactPath(relPath: String): Boolean {
            val norm = relPath.replace('\\', '/')
            return norm == ".mederi/notebook.md" ||
                    norm.startsWith(".mederi/plans/") ||
                    norm.startsWith(".mederi/plans-done/") ||
                    norm.startsWith(".mederi/plans-voided/") ||
                    norm.startsWith(".mederi/research/")
        }
    }

    private fun parseStageZ(bytes: ByteArray): List<StageItem> {
        val items = mutableListOf<StageItem>()
        var offset = 0
        while (offset < bytes.size) {
            val nul = indexOfNul(bytes, offset)
            if (nul < 0) break
            val line = String(bytes, offset, nul - offset, StandardCharsets.UTF_8)
            offset = nul + 1

            // Format: <mode> <oid> <stage>\t<path>
            val tabIdx = line.indexOf('\t')
            if (tabIdx >= 0) {
                val meta = line.substring(0, tabIdx).trim().split(" ")
                val path = line.substring(tabIdx + 1)
                if (meta.size >= 3) {
                    items.add(
                        StageItem(
                            mode = meta[0],
                            oid = meta[1],
                            stage = meta[2].toIntOrNull() ?: 0,
                            path = path
                        )
                    )
                }
            }
        }
        return items
    }

    private fun parseNulStrings(bytes: ByteArray): List<String> {
        val res = mutableListOf<String>()
        var offset = 0
        while (offset < bytes.size) {
            val nul = indexOfNul(bytes, offset)
            if (nul < 0) break
            val s = String(bytes, offset, nul - offset, StandardCharsets.UTF_8)
            offset = nul + 1
            if (s.isNotBlank()) res.add(s)
        }
        return res
    }

    private fun indexOfNul(bytes: ByteArray, start: Int): Int {
        for (i in start until bytes.size) {
            if (bytes[i] == 0.toByte()) return i
        }
        return -1
    }

    fun fileSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            while (true) {
                val r = stream.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return toHex(md.digest())
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return toHex(md.digest(bytes))
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private data class StageItem(
        val mode: String,
        val oid: String,
        val stage: Int,
        val path: String
    )
}
