package xyz.mederi.tools

import kotlinx.coroutines.CancellationException
import xyz.mederi.config.MederiIdentity
import xyz.mederi.tools.diff.FileChangeStatus
import xyz.mederi.tools.diff.PatchChange
import xyz.mederi.tools.patch.PatchApplier
import xyz.mederi.tools.patch.PatchHunk
import java.io.File
import java.nio.CharBuffer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.PosixFilePermission

/**
 * apply_patch 的宿主侧事务。所有源/目标一次加锁，先在内存推导整份补丁，再写盘。
 * 文件以同目录临时文件替换，避免半写内容和跟随叶子符号链接；普通 I/O 失败尝试恢复字节与权限。
 * 这不是文件系统级跨文件原子事务：外部进程不遵守 FileWriteRegistry，进程崩溃也无法运行回滚。
 */
internal class PatchFileTransaction(
    private val directories: List<String>,
    private val reportChanges: (List<PatchChange>) -> Unit,
    extraWritablePaths: List<String> = emptyList(),
    /** 确定性故障注入，仅供内部测试；第二个参数表示正在回滚。 */
    private val beforeMutation: (Path, Boolean) -> Unit = { _, _ -> },
) {
    private data class Snapshot(val bytes: ByteArray?, val text: String?, val permissions: Set<PosixFilePermission>?)
    private data class Operation(val hunk: PatchHunk, val source: Path, val destination: Path?)
    private val writableExtras = extraWritablePaths.toList()
    // 初始化也属于验证阶段：错误配置（包括不可 canonicalize 的路径）必须返回工具反馈。
    private var projectRoots: List<Pair<Path, Path>> = emptyList()
    private var roots: List<Pair<Path, Path>> = emptyList()

    fun apply(hunks: List<PatchHunk>): String {
        if (hunks.isEmpty()) return "No files were modified."
        val operations: List<Operation>
        val paths = linkedSetOf<Path>()
        try {
            writableExtras.forEach { path ->
                require(path.isNotBlank() && File(path).isAbsolute) {
                    "Invalid extra writable path '$path': sandbox whitelist entries must be nonblank absolute directory paths"
                }
            }
            projectRoots = directories.map { File(it).absoluteFile.toPath().normalize() to File(it).canonicalFile.toPath() }
            roots = projectRoots + writableExtras.map { File(it).absoluteFile.toPath().normalize() to File(it).canonicalFile.toPath() }
            operations = hunks.map { hunk ->
                val source = resolve(hunk.path)
                val destination = (hunk as? PatchHunk.UpdateFile)?.movePath?.let(::resolve)
                for (path in listOfNotNull(source, destination)) {
                    require(paths.add(path)) { "multiple operations target ${display(path)}; combine changes into one Update File hunk" }
                }
                Operation(hunk, source, destination)
            }
            // ancestor/descendant targets cannot coexist as regular files; reject before creating parents.
            for (path in paths) require(paths.none { it != path && path.startsWith(it) }) {
                "A patch target is also a parent directory of ${display(path)}"
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return verificationError(e)
        }

        val files = paths.map(Path::toFile)
        val acquisition = try { FileWriteRegistry.tryAcquireLease(files) } catch (e: Exception) {
            if (e is CancellationException) throw e
            return verificationError(e)
        }
        val lease = when (acquisition) {
            is FileWriteRegistry.Acquisition.Acquired -> acquisition.lease
            is FileWriteRegistry.Acquisition.Conflict -> return "Error: apply_patch: file is being modified by another tool: ${display(acquisition.file.toPath())}. Retry after that operation completes."
        }
        try {
            val snapshots = linkedMapOf<Path, Snapshot>()
            val desired = linkedMapOf<Path, ByteArray?>()
            val desiredPermissions = linkedMapOf<Path, Set<PosixFilePermission>?>()
            try {
                paths.forEach { path ->
                    checkPath(path)
                    snapshots[path] = snapshot(path)
                }
                operations.forEach { (hunk, source, destination) ->
                    val before = snapshots.getValue(source)
                    when (hunk) {
                        is PatchHunk.AddFile -> desired[source] = encode(hunk.contents)
                        is PatchHunk.DeleteFile -> {
                            require(before.bytes != null) { "Path does not exist: ${display(source)}" }
                            desired[source] = null
                        }
                        is PatchHunk.UpdateFile -> {
                            require(before.text != null) { "Path does not exist: ${display(source)}" }
                            val updated = encode(PatchApplier.deriveNewContents(before.text, display(source), hunk.chunks))
                            if (destination == null) desired[source] = updated else {
                                desired[destination] = updated
                                desiredPermissions[destination] = snapshots.getValue(destination).permissions ?: before.permissions
                                desired[source] = null
                            }
                        }
                    }
                }
                // Recheck all inputs after preparation, before the first mutation.
                paths.forEach { checkUnchanged(it, snapshots.getValue(it)) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return verificationError(e)
            }

            val mutated = mutableListOf<Path>()
            val createdDirectories = mutableListOf<Path>()
            try {
                desired.forEach { (path, bytes) ->
                    val before = snapshots.getValue(path)
                    // 真正的 no-op 不应替换 inode、改 mtime 或扰动 hard link/扩展属性。
                    if (sameBytes(before.bytes, bytes)) return@forEach
                    checkUnchanged(path, before)
                    beforeMutation(path, false)
                    // Register before attempting I/O: a failed filesystem call may have changed the file.
                    mutated.add(path)
                    writeState(path, bytes, desiredPermissions[path] ?: snapshots.getValue(path).permissions, createdDirectories)
                }
            } catch (e: Exception) {
                val rollbackErrors = mutableListOf<String>()
                mutated.asReversed().forEach { path ->
                    try {
                        beforeMutation(path, true)
                        val old = snapshots.getValue(path)
                        writeState(path, old.bytes, old.permissions, createdDirectories)
                    } catch (rollback: Exception) {
                        rollbackErrors.add("${display(path)}: ${rollback.message ?: rollback.javaClass.simpleName}")
                    }
                }
                createdDirectories.asReversed().distinct().forEach { directory ->
                    try { Files.deleteIfExists(directory) } catch (cleanup: Exception) {
                        // Never recursively remove a directory: it may now contain an external writer's file.
                        rollbackErrors.add("directory ${display(directory)}: ${cleanup.message ?: cleanup.javaClass.simpleName}")
                    }
                }
                val remaining = mutableListOf<PatchChange>()
                val unknown = mutableListOf<String>()
                paths.forEach { path ->
                    try { change(path, snapshots.getValue(path), snapshotChecked(path))?.let(remaining::add) }
                    catch (_: Exception) { unknown.add(display(path)) }
                }
                val trackingWarning = report(remaining)
                if (e is CancellationException) throw e
                return buildString {
                    append("Error: apply_patch failed: ${e.message ?: e.javaClass.simpleName}. ")
                    if (remaining.isEmpty() && unknown.isEmpty()) append("All file contents restored; no file changes remain.")
                    else {
                        append("Rollback incomplete. Inspect these files before retrying:")
                        remaining.forEach { append("\n${statusLetter(it.status)} ${display(File(it.path).toPath())}") }
                        unknown.forEach { append("\n? $it (could not inspect)") }
                    }
                    if (rollbackErrors.isNotEmpty()) append("\nRollback errors: ${rollbackErrors.joinToString("; ")}")
                    if (trackingWarning != null) append("\n$trackingWarning")
                }
            }

            val changes = desired.mapNotNull { (path, bytes) ->
                change(path, snapshots.getValue(path), Snapshot(bytes, bytes?.let(::decode), snapshots.getValue(path).permissions))
            }
            val warning = report(changes)
            return buildString {
                if (changes.isEmpty()) append("No files were modified.") else {
                    append("Success. Updated the following files:")
                    changes.forEach { append("\n${statusLetter(it.status)} ${display(File(it.path).toPath())}") }
                }
                if (warning != null) append("\n$warning")
            }
        } finally {
            lease.close()
        }
    }

    private fun verificationError(e: Exception) =
        "Error: apply_patch verification failed: ${e.message ?: e.javaClass.simpleName}. No files were modified."

    private fun resolve(raw: String): Path {
        require(raw.isNotBlank()) { "File path is empty" }
        require(projectRoots.isNotEmpty()) { "No project directories configured" }
        val input = File(raw).toPath()
        val absolute = if (input.isAbsolute) input.normalize() else {
            projectRoots.map { it.first.resolve(input).normalize() }.firstOrNull { Files.exists(it, NOFOLLOW_LINKS) }
                ?: projectRoots.first().first.resolve(input).normalize()
        }
        val (lexicalRoot, canonicalRoot) = roots.firstOrNull { absolute.startsWith(it.first) }
            ?: roots.firstOrNull { absolute.startsWith(it.second) }?.let { it.second to it.second }
            ?: throw IllegalArgumentException("Path '$raw' is outside project directories: $directories")
        val path = canonicalRoot.resolve(lexicalRoot.relativize(absolute)).normalize()
        require(path != canonicalRoot && path.startsWith(canonicalRoot)) { "Path '$raw' must name a file inside a project directory" }
        checkPath(path)
        return path
    }

    /** Reject symlinks at every component below the approved root, including dangling leaf links. */
    private fun checkPath(path: Path) {
        val root = roots.map { it.second }.filter { path.startsWith(it) }.maxByOrNull { it.nameCount }
            ?: throw IllegalArgumentException("Path is outside project directories: $path")
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Project directory is missing or no longer a regular directory: $root" }
        var current = root
        root.relativize(path).forEach { component ->
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "Symbolic links are not allowed in patch paths: ${display(current)}" }
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                require(if (current == path) Files.isRegularFile(current, NOFOLLOW_LINKS) else Files.isDirectory(current, NOFOLLOW_LINKS)) {
                    "Path is not a ${if (current == path) "regular file" else "directory"}: ${display(current)}"
                }
            }
        }
        require(path.toFile().canonicalFile.toPath().startsWith(root)) { "Path escapes project directory: $path" }
    }

    private fun snapshotChecked(path: Path): Snapshot { checkPath(path); return snapshot(path) }
    private fun snapshot(path: Path): Snapshot {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return Snapshot(null, null, null)
        val bytes = Files.readAllBytes(path)
        val permissions = try { Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) } catch (_: UnsupportedOperationException) { null }
        val text = try { decode(bytes) } catch (e: Exception) {
            throw IllegalArgumentException("Cannot read ${display(path)} as UTF-8 text: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return Snapshot(bytes, text, permissions)
    }

    private fun encode(text: String): ByteArray {
        require('\u0000' !in text) { "Binary content (NUL bytes) cannot be written with apply_patch" }
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    private fun decode(bytes: ByteArray): String {
        require(bytes.none { it == 0.toByte() }) { "Binary files (NUL bytes) cannot be edited with apply_patch" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun checkUnchanged(path: Path, before: Snapshot) {
        val actual = snapshotChecked(path)
        require(sameBytes(before.bytes, actual.bytes)) {
            "File changed after patch verification: ${display(path)}; read it again before retrying"
        }
    }

    private fun sameBytes(first: ByteArray?, second: ByteArray?): Boolean =
        if (first == null) second == null else second != null && first.contentEquals(second)

    private fun writeState(path: Path, bytes: ByteArray?, permissions: Set<PosixFilePermission>?, created: MutableList<Path>) {
        checkPath(path)
        if (bytes == null) { Files.deleteIfExists(path); return }
        val missing = mutableListOf<Path>()
        var parent = path.parent
        while (!Files.exists(parent, NOFOLLOW_LINKS)) { missing.add(parent); parent = parent.parent }
        missing.asReversed().forEach { directory ->
            try { Files.createDirectory(directory); created.add(directory) }
            catch (e: java.nio.file.FileAlreadyExistsException) { require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Not a directory: $directory" } }
        }
        checkPath(path)
        val staging = if (Files.getFileStore(path.parent).supportsFileAttributeView("posix")) {
            // Use ordinary file creation permissions (filtered by umask), rather than temp-file 0600.
            Files.createTempFile(path.parent, "${MederiIdentity.projectWorkdirName}-patch-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-rw-rw-")))
        } else Files.createTempFile(path.parent, "${MederiIdentity.projectWorkdirName}-patch-", ".tmp")
        try {
            Files.write(staging, bytes)
            if (permissions != null) Files.setPosixFilePermissions(staging, permissions)
            checkPath(path)
            try { Files.move(staging, path, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(staging, path, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(staging) }
    }

    private fun change(path: Path, before: Snapshot, after: Snapshot): PatchChange? {
        if (before.bytes == null && after.bytes == null) return null
        if (before.bytes != null && after.bytes != null && before.bytes.contentEquals(after.bytes)) return null
        val status = when { before.bytes == null -> FileChangeStatus.ADDED; after.bytes == null -> FileChangeStatus.DELETED; else -> FileChangeStatus.MODIFIED }
        return PatchChange(path.toString(), status, before.text, after.text)
    }

    private fun report(changes: List<PatchChange>): String? = try {
        if (changes.isNotEmpty()) reportChanges(changes)
        null
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        "Warning: disk changes were applied, but change tracking failed: ${e.message ?: e.javaClass.simpleName}. Inspect files before continuing."
    }

    private fun display(path: Path): String {
        val root = projectRoots.map { it.second }.firstOrNull { path.startsWith(it) }
        return (root?.relativize(path) ?: path).toString()
    }
    private fun statusLetter(status: FileChangeStatus) = when (status) {
        FileChangeStatus.ADDED -> "A"; FileChangeStatus.MODIFIED -> "M"; FileChangeStatus.DELETED -> "D"
    }
}
