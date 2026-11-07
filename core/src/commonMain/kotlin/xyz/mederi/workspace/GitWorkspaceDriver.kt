package xyz.mederi.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.tools.sandbox.ProcessRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Git 执行输出结果。
 */
data class GitExecutionResult(
    val exitCode: Int,
    val stdoutBytes: ByteArray,
    val stderr: String
) {
    val stdoutString: String
        get() = String(stdoutBytes, StandardCharsets.UTF_8)
}

/**
 * 状态条目。
 */
data class GitStatusEntry(
    val path: String,
    val origPath: String? = null,
    val stagedStatus: Char = ' ',
    val unstagedStatus: Char = ' ',
    val isUntracked: Boolean = false,
    val isIgnored: Boolean = false,
    val isUnmerged: Boolean = false
)

/**
 * 租约管理：执行共享租约与管理独占意图。
 */
class WorkspaceLeaseManager {
    private val commonDirLocks = ConcurrentHashMap<String, Mutex>()
    private val executionLeaseCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val managementIntentFlags = ConcurrentHashMap<String, AtomicBoolean>()

    /**
     * 管理操作内部 permit：由 [WorkspaceLeaseManager.withManagementIntent] 在管理临界区内创建，
     * 证明当前调用拥有管理意图所有权。外部代码无法通过公开 API 构造合法实例（internal 构造），
     * 因此不可能伪造。
     *
     * @property operationId 管理操作 ID（handoff/archive 的 journal operation id）
     * @property gitCommonDir canonical 化的 git common dir
     * @property checkoutRoots canonical 化的受保护 checkout 根目录列表
     * @property createdAt 创建时间戳（ms）
     * @property expiryMs 有效期（默认 120s，超时后 permit 失效）
     */
    class ManagementPermit internal constructor(
        val operationId: String,
        val gitCommonDir: String,
        val checkoutRoots: List<String>,
        val createdAt: Long,
        val expiryMs: Long = 120000L,
    ) {
        @Volatile private var active = true

        internal fun invalidate() { active = false }

        /**
         * 判断此 permit 是否覆盖指定 checkoutRoot 且未过期。
         * 只有由 withManagementIntent 在同一临界区内创建的 permit 才能通过此检查。
         */
        fun covers(checkoutRoot: String): Boolean {
            val canonical = try { File(checkoutRoot).canonicalPath } catch (_: Exception) { File(checkoutRoot).absolutePath }
            return active && checkoutRoots.contains(canonical) && System.currentTimeMillis() < createdAt + expiryMs
        }
    }

    private fun getCommonDirMutex(gitCommonDir: String): Mutex =
        commonDirLocks.computeIfAbsent(canonicalize(gitCommonDir)) { Mutex() }

    private fun getExecutionCounter(checkoutRoot: String): AtomicInteger =
        executionLeaseCounts.computeIfAbsent(canonicalize(checkoutRoot)) { AtomicInteger(0) }

    private fun getManagementFlag(checkoutRoot: String): AtomicBoolean =
        managementIntentFlags.computeIfAbsent(canonicalize(checkoutRoot)) { AtomicBoolean(false) }

    private fun canonicalize(path: String): String =
        try { File(path).canonicalPath } catch (_: Exception) { File(path).absolutePath }

    /**
     * 获取指定工作区的共享执行租约。
     * 若当前工作区正处于独占管理意图中，直接拒绝抛出 WORKSPACE_BUSY。
     */
    fun acquireExecutionLease(checkoutRoot: String): AutoCloseable {
        val root = canonicalize(checkoutRoot)
        val intentFlag = getManagementFlag(root)
        if (intentFlag.get()) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.WORKSPACE_BUSY,
                message = "Workspace $checkoutRoot is currently under exclusive management intent"
            )
        }

        val counter = getExecutionCounter(root)
        counter.incrementAndGet()

        // 双重校验：如果在递增过程中管理意图已确立
        if (intentFlag.get()) {
            counter.decrementAndGet()
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.WORKSPACE_BUSY,
                message = "Workspace $checkoutRoot is currently under exclusive management intent"
            )
        }

        val released = AtomicBoolean(false)
        return AutoCloseable { if (released.compareAndSet(false, true)) counter.decrementAndGet() }
    }

    /**
     * 在指定 gitCommonDir 下获取独占管理意图。
     *
     * 1. 跨 Project 按 gitCommonDir 序列化管理操作。
     * 2. 标记所有涉及 checkoutRoot 的 managementIntent = true，立即阻止后续新执行租约。
     * 3. 等待既有执行租约归零（超时抛出 WORKSPACE_BUSY）。
     */
    suspend fun <T> withManagementIntent(
        gitCommonDir: String,
        checkoutRoots: List<String>,
        waitTimeoutMs: Long = 10000L,
        permitOperationId: String,
        block: suspend (ManagementPermit) -> T
    ): T {
        val mutex = getCommonDirMutex(gitCommonDir)
        val canonicalRoots = checkoutRoots.map { canonicalize(it) }

        return mutex.withLock {
            val flags = canonicalRoots.map { getManagementFlag(it) }
            flags.forEach { it.set(true) }

            try {
                // 等待所有 checkoutRoot 的活跃执行租约归零
                val deadline = System.currentTimeMillis() + waitTimeoutMs
                for (root in canonicalRoots) {
                    val counter = getExecutionCounter(root)
                    while (counter.get() > 0) {
                        if (System.currentTimeMillis() > deadline) {
                            throw MederiWorkspaceException(
                                code = WorkspaceErrorCode.WORKSPACE_BUSY,
                                message = "Timed out waiting for active execution leases on $root to quiesce"
                            )
                        }
                        delay(20)
                    }
                }

                val permit = ManagementPermit(
                    operationId = permitOperationId,
                    gitCommonDir = canonicalize(gitCommonDir),
                    checkoutRoots = canonicalRoots,
                    createdAt = System.currentTimeMillis()
                )
                try {
                    block(permit)
                } finally {
                    permit.invalidate()
                }
            } finally {
                flags.forEach { it.set(false) }
            }
        }
    }
}

/**
 * 纯 Git I/O 驱动（ProcessBuilder argv + NUL 协议 + 安全沙盒）。
 */
class GitWorkspaceDriver(
    val leaseManager: WorkspaceLeaseManager = WorkspaceLeaseManager()
) {
    private val emptyHooksDir: File by lazy {
        val dir = File(System.getProperty("java.io.tmpdir"), "mederi_empty_git_hooks")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    /**
     * 运行原始 Git 命令（不经 shell，禁用 hooks、fsmonitor，记录 ProcessRegistry，超时收割进程组）。
     */
    suspend fun runGit(
        workingDir: String,
        args: List<String>,
        timeoutSeconds: Int = 30
    ): GitExecutionResult = withContext(Dispatchers.IO) {
        val workDirFile = File(workingDir)
        val fullArgv = mutableListOf<String>()
        fullArgv.add("git")
        fullArgv.add("--no-pager")
        fullArgv.add("-c")
        fullArgv.add("core.hooksPath=${emptyHooksDir.absolutePath}")
        fullArgv.add("-c")
        fullArgv.add("core.fsmonitor=false")
        fullArgv.add("-c")
        fullArgv.add("diff.autorefreshindex=false")
        fullArgv.addAll(args)

        val pb = ProcessBuilder(fullArgv)
        pb.directory(workDirFile)

        // 净化环境变量：移除一切改变仓库身份的继承变量
        val env = pb.environment()
        val removeKeys = env.keys.filter { key ->
            key.startsWith("GIT_") && key !in setOf("GIT_EXEC_PATH", "GIT_TEMPLATE_DIR", "GIT_SSH", "GIT_SSH_COMMAND")
        }
        removeKeys.forEach { env.remove(it) }

        env["GIT_TERMINAL_PROMPT"] = "0"
        env["GIT_OPTIONAL_LOCKS"] = "0"
        env["LC_ALL"] = "C"

        val process = pb.start()
        val pid = try { process.pid() } catch (_: Throwable) { -1L }
        if (pid > 0) {
            ProcessRegistry.register(pid, pid, fullArgv.joinToString(" "), workingDir)
        }

        val stdoutStream = ByteArrayOutputStream()
        val stderrStream = ByteArrayOutputStream()

        val stdoutThread = Thread { drain(process.inputStream, stdoutStream, limit = 64 * 1024 * 1024) }
        val stderrThread = Thread { drain(process.errorStream, stderrStream, limit = 8 * 1024) }
        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            if (pid > 0) {
                ProcessRegistry.terminate(pid, force = true)
            }
            process.destroyForcibly()
            stdoutThread.interrupt()
            stderrThread.interrupt()
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.IO_ERROR,
                message = "Git command timed out after ${timeoutSeconds}s: ${args.joinToString(" ")}"
            )
        }

        stdoutThread.join(2000)
        stderrThread.join(2000)

        if (pid > 0) {
            ProcessRegistry.remove(pid)
        }

        val exitCode = process.exitValue()
        GitExecutionResult(
            exitCode = exitCode,
            stdoutBytes = stdoutStream.toByteArray(),
            stderr = String(stderrStream.toByteArray(), StandardCharsets.UTF_8).take(8192)
        )
    }

    private fun drain(input: InputStream, output: ByteArrayOutputStream, limit: Int) {
        val buffer = ByteArray(8192)
        var total = 0
        try {
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (total < limit) {
                    val toWrite = minOf(read, limit - total)
                    output.write(buffer, 0, toWrite)
                    total += toWrite
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 发现仓库与工作区身份（识别 linked worktree、子目录偏移、共同公共目录、HEAD及分支占用）。
     */
    suspend fun getGitCommonDir(directoryPath: String): String? {
        val res = runGitCatching(directoryPath, listOf("rev-parse", "--path-format=absolute", "--git-common-dir"))
        return if (res.exitCode == 0) File(res.stdoutString.trim()).canonicalPath else null
    }

    suspend fun discover(directoryPath: String): RepositoryInfo {
        val dir = File(directoryPath)
        if (!dir.exists()) {
            return RepositoryInfo(isGit = false, unsupportedReasons = listOf("Directory does not exist"))
        }

        val topLevelResult = runGitCatching(dir.absolutePath, listOf("rev-parse", "--show-toplevel"))
        if (topLevelResult.exitCode != 0) {
            return RepositoryInfo(isGit = false)
        }

        val checkoutRoot = File(topLevelResult.stdoutString.trim()).canonicalPath
        val prefixResult = runGitCatching(dir.absolutePath, listOf("rev-parse", "--show-prefix"))
        val projectRelativePath = prefixResult.stdoutString.trim().trimEnd('/')

        val commonDirResult = runGitCatching(
            dir.absolutePath,
            listOf("rev-parse", "--path-format=absolute", "--git-common-dir")
        )
        val gitCommonDirectory = if (commonDirResult.exitCode == 0) {
            File(commonDirResult.stdoutString.trim()).canonicalPath
        } else null

        // 探测 HEAD commit
        val headOidResult = runGitCatching(dir.absolutePath, listOf("rev-parse", "--verify", "HEAD^{commit}"))
        val headOid = if (headOidResult.exitCode == 0) headOidResult.stdoutString.trim() else null

        // 探测 HEAD 分支名
        val branchResult = runGitCatching(dir.absolutePath, listOf("symbolic-ref", "-q", "HEAD"))
        val currentBranch = if (branchResult.exitCode == 0) {
            branchResult.stdoutString.trim().removePrefix("refs/heads/")
        } else null

        // 收集分支列表与已检出 worktree 映射
        val worktreeCheckoutMap = parseWorktreeList(checkoutRoot)
        val branches = parseBranches(checkoutRoot, worktreeCheckoutMap)

        // 检查未合并冲突与 dirty 状态
        val statusResult = runGitCatching(
            dir.absolutePath,
            listOf("status", "--porcelain=v2", "-z", "--untracked-files=all")
        )
        val entries = parsePorcelainV2(statusResult.stdoutBytes)
        val isDirty = entries.any { !it.isIgnored }

        val unsupportedReasons = mutableListOf<String>()
        if (headOid == null) {
            unsupportedReasons.add("unborn HEAD")
        }
        if (entries.any { it.isUnmerged }) {
            unsupportedReasons.add("unmerged index entries exist")
        }

        // 检查合并/变基中断状态
        val commonDir = gitCommonDirectory?.let { File(it) } ?: File(checkoutRoot, ".git")
        if (File(commonDir, "MERGE_HEAD").exists()) {
            unsupportedReasons.add("merge in progress")
        }
        if (File(commonDir, "rebase-merge").exists() || File(commonDir, "rebase-apply").exists()) {
            unsupportedReasons.add("rebase in progress")
        }
        if (File(commonDir, "CHERRY_PICK_HEAD").exists()) {
            unsupportedReasons.add("cherry-pick in progress")
        }
        if (File(commonDir, "BISECT_LOG").exists()) {
            unsupportedReasons.add("bisect in progress")
        }

        return RepositoryInfo(
            isGit = true,
            checkoutRoot = checkoutRoot,
            projectRelativePath = projectRelativePath,
            gitCommonDirectory = gitCommonDirectory,
            headOid = headOid,
            branch = currentBranch,
            branches = branches,
            dirty = isDirty,
            unsupportedReasons = unsupportedReasons
        )
    }

    /**
     * 校验基线 ref，解析为固定的 commit OID。
     * 拒绝任何以 '-' 开头的参数注入行为。
     */
    suspend fun resolveBaseRef(checkoutRoot: String, baseRef: String): String {
        if (baseRef.startsWith("-")) {
            throw MederiValidationException("Invalid baseRef: option-like references are rejected: $baseRef")
        }

        val res = runGitCatching(checkoutRoot, listOf("rev-parse", "--verify", "--end-of-options", "$baseRef^{commit}"))
        if (res.exitCode != 0) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.NO_BASE_COMMIT,
                message = "Cannot resolve baseRef $baseRef to a commit: ${res.stderr}"
            )
        }
        return res.stdoutString.trim()
    }

    /**
     * 核验基线 ref 是否自解析后发生改变。
     */
    suspend fun verifyBaseRefUnchanged(checkoutRoot: String, baseRef: String, expectedOid: String) {
        val currentOid = resolveBaseRef(checkoutRoot, baseRef)
        if (currentOid != expectedOid) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.BASE_CHANGED,
                message = "Base ref $baseRef has changed from $expectedOid to $currentOid"
            )
        }
    }

    /**
     * 前置校验仓库状态（检查 unborn HEAD、merge/rebase/bisect、自定义 filter）。
     */
    suspend fun preflightRepository(checkoutRoot: String, gitCommonDir: String? = null) {
        val root = File(checkoutRoot)
        val commonDir = gitCommonDir?.let { File(it) } ?: File(root, ".git")

        // 1. HEAD commit 存在性
        val headRes = runGitCatching(checkoutRoot, listOf("rev-parse", "--verify", "HEAD^{commit}"))
        if (headRes.exitCode != 0) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.NO_BASE_COMMIT,
                message = "Repository has no base commit (unborn HEAD)"
            )
        }

        // 2. 冲突/临时状态
        if (File(commonDir, "MERGE_HEAD").exists()) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.UNSUPPORTED_REPOSITORY_STATE,
                message = "Merge in progress (MERGE_HEAD present)"
            )
        }
        if (File(commonDir, "rebase-merge").exists() || File(commonDir, "rebase-apply").exists()) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.UNSUPPORTED_REPOSITORY_STATE,
                message = "Rebase in progress"
            )
        }
        if (File(commonDir, "CHERRY_PICK_HEAD").exists()) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.UNSUPPORTED_REPOSITORY_STATE,
                message = "Cherry-pick in progress"
            )
        }
        if (File(commonDir, "BISECT_LOG").exists()) {
            throw MederiWorkspaceException(
                code = WorkspaceErrorCode.UNSUPPORTED_REPOSITORY_STATE,
                message = "Bisect in progress"
            )
        }

        // 3. 检查自定义 filter
        val filterRes = runGitCatching(checkoutRoot, listOf("config", "--get-regexp", "^filter\\."))
        if (filterRes.exitCode == 0 && filterRes.stdoutString.isNotBlank()) {
            val lines = filterRes.stdoutString.lines().filter { it.isNotBlank() }
            if (lines.any { it.contains(".clean") || it.contains(".smudge") || it.contains(".process") }) {
                throw MederiWorkspaceException(
                    code = WorkspaceErrorCode.UNSUPPORTED_REPOSITORY_STATE,
                    message = "Custom git filters are configured: ${filterRes.stdoutString.trim()}"
                )
            }
        }
    }

    /**
     * 解析 porcelain v2 -z 格式状态列表。
     */
    fun parsePorcelainV2(bytes: ByteArray): List<GitStatusEntry> {
        val entries = mutableListOf<GitStatusEntry>()
        var offset = 0

        while (offset < bytes.size) {
            val nulIdx = indexOfNul(bytes, offset)
            if (nulIdx < 0) break

            val lineStr = String(bytes, offset, nulIdx - offset, StandardCharsets.UTF_8)
            offset = nulIdx + 1

            if (lineStr.startsWith("#")) {
                // Header line
                continue
            } else if (lineStr.startsWith("1 ")) {
                // Ordinary change: 1 <xy> <sub> <mH> <mI> <mW> <hH> <hI> <path>
                val parts = lineStr.split(" ", limit = 9)
                if (parts.size >= 9) {
                    val xy = parts[1]
                    val path = parts[8]
                    entries.add(
                        GitStatusEntry(
                            path = path,
                            stagedStatus = xy.getOrNull(0) ?: '.',
                            unstagedStatus = xy.getOrNull(1) ?: '.'
                        )
                    )
                }
            } else if (lineStr.startsWith("2 ")) {
                // Rename / Copy: 2 <xy> <sub> <mH> <mI> <mW> <hH> <hI> <X><score> <path>\0<origPath>\0
                val parts = lineStr.split(" ", limit = 10)
                val origNulIdx = indexOfNul(bytes, offset)
                val origPath = if (origNulIdx >= 0) {
                    val p = String(bytes, offset, origNulIdx - offset, StandardCharsets.UTF_8)
                    offset = origNulIdx + 1
                    p
                } else null

                if (parts.size >= 10) {
                    val xy = parts[1]
                    val path = parts[9]
                    entries.add(
                        GitStatusEntry(
                            path = path,
                            origPath = origPath,
                            stagedStatus = xy.getOrNull(0) ?: '.',
                            unstagedStatus = xy.getOrNull(1) ?: '.'
                        )
                    )
                }
            } else if (lineStr.startsWith("u ")) {
                // Unmerged
                val parts = lineStr.split(" ", limit = 11)
                val path = parts.lastOrNull() ?: ""
                entries.add(GitStatusEntry(path = path, isUnmerged = true))
            } else if (lineStr.startsWith("? ")) {
                // Untracked: ? <path>
                val path = lineStr.substring(2)
                entries.add(GitStatusEntry(path = path, isUntracked = true))
            } else if (lineStr.startsWith("! ")) {
                // Ignored: ! <path>
                val path = lineStr.substring(2)
                entries.add(GitStatusEntry(path = path, isIgnored = true))
            }
        }

        return entries
    }

    private suspend fun parseWorktreeList(checkoutRoot: String): Map<String, String> {
        val res = runGitCatching(checkoutRoot, listOf("worktree", "list", "--porcelain", "-z"))
        if (res.exitCode != 0) return emptyMap()

        val map = mutableMapOf<String, String>() // branchName -> worktreePath
        val bytes = res.stdoutBytes
        var offset = 0
        var currentWtPath: String? = null

        while (offset < bytes.size) {
            val nulIdx = indexOfNul(bytes, offset)
            if (nulIdx < 0) break
            val line = String(bytes, offset, nulIdx - offset, StandardCharsets.UTF_8)
            offset = nulIdx + 1

            if (line.startsWith("worktree ")) {
                currentWtPath = line.removePrefix("worktree ").trim()
            } else if (line.startsWith("branch ")) {
                val branch = line.removePrefix("branch ").trim().removePrefix("refs/heads/")
                if (currentWtPath != null) {
                    map[branch] = currentWtPath
                }
            } else if (line.isEmpty()) {
                currentWtPath = null
            }
        }
        return map
    }

    private suspend fun parseBranches(
        checkoutRoot: String,
        worktreeCheckoutMap: Map<String, String>
    ): List<GitBranchInfo> {
        val res = runGitCatching(
            checkoutRoot,
            listOf("for-each-ref", "--format=%(refname:short)%00%(objectname)", "refs/heads")
        )
        if (res.exitCode != 0) return emptyList()

        val branches = mutableListOf<GitBranchInfo>()
        val bytes = res.stdoutBytes
        var offset = 0

        while (offset < bytes.size) {
            val nameNul = indexOfNul(bytes, offset)
            if (nameNul < 0) break
            val name = String(bytes, offset, nameNul - offset, StandardCharsets.UTF_8)
            offset = nameNul + 1

            // Next entry ends with \n or EOF
            var nextNewline = -1
            for (i in offset until bytes.size) {
                if (bytes[i] == '\n'.code.toByte()) {
                    nextNewline = i
                    break
                }
            }
            val endIdx = if (nextNewline >= 0) nextNewline else bytes.size
            val oid = String(bytes, offset, endIdx - offset, StandardCharsets.UTF_8).trim()
            offset = if (nextNewline >= 0) nextNewline + 1 else bytes.size

            if (name.isNotBlank()) {
                branches.add(
                    GitBranchInfo(
                        name = name,
                        headOid = oid,
                        inUseByCheckout = worktreeCheckoutMap[name]
                    )
                )
            }
        }
        return branches
    }

    private fun indexOfNul(bytes: ByteArray, startIndex: Int): Int {
        for (i in startIndex until bytes.size) {
            if (bytes[i] == 0.toByte()) return i
        }
        return -1
    }

    private suspend fun runGitCatching(workingDir: String, args: List<String>): GitExecutionResult =
        try {
            runGit(workingDir, args)
        } catch (_: Exception) {
            GitExecutionResult(-1, ByteArray(0), "Git invocation failed")
        }
}
