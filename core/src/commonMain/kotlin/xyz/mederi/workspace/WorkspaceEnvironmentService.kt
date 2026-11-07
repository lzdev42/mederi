package xyz.mederi.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.mederi.api.exception.MederiStateException
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.store.WorkspaceStore
import xyz.mederi.tools.ShellTools
import xyz.mederi.tools.sandbox.CommandSandbox
import xyz.mederi.tools.sandbox.ExecutionPermissionMode
import xyz.mederi.tools.sandbox.ExecutionPermissions
import xyz.mederi.tools.sandbox.ProcessRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 8MiB 循环滚动 UTF-8 操作日志缓冲。
 */
class WorkspaceOperationLogBuffer(
    val maxCapacity: Int = 8 * 1024 * 1024
) {
    private val lock = Any()
    private val buffer = ByteArrayOutputStream()
    private var totalBytesWritten = 0L
    private var isTruncated = false

    fun append(bytes: ByteArray) = synchronized(lock) {
        totalBytesWritten += bytes.size
        buffer.write(bytes)
        if (buffer.size() > maxCapacity) {
            isTruncated = true
            val full = buffer.toByteArray()
            val keep = full.takeLast(maxCapacity).toByteArray()
            buffer.reset()
            buffer.write(keep)
        }
    }

    fun append(text: String) {
        append(text.toByteArray(Charsets.UTF_8))
    }

    fun readChunk(afterOffset: Long, maxBytes: Int = 256 * 1024): LogChunk = synchronized(lock) {
        val currentBytes = buffer.toByteArray()
        val bufferBaseOffset = totalBytesWritten - currentBytes.size

        if (afterOffset >= totalBytesWritten) {
            return LogChunk(text = "", nextOffset = totalBytesWritten, eof = true, truncated = isTruncated)
        }

        val localStart = if (afterOffset < bufferBaseOffset) 0 else (afterOffset - bufferBaseOffset).toInt()
        val safeStart = adjustToUtf8Boundary(currentBytes, localStart)

        val desiredEnd = (safeStart + maxBytes).coerceAtMost(currentBytes.size)
        val safeEnd = adjustToUtf8Boundary(currentBytes, desiredEnd)

        val slice = currentBytes.copyOfRange(safeStart, safeEnd)
        val text = slice.decodeToString()
        val nextOffset = bufferBaseOffset + safeEnd
        val isEof = nextOffset >= totalBytesWritten

        return LogChunk(
            text = text,
            nextOffset = nextOffset,
            eof = isEof,
            truncated = isTruncated
        )
    }

    private fun adjustToUtf8Boundary(bytes: ByteArray, index: Int): Int {
        if (index <= 0 || index >= bytes.size) return index
        var pos = index
        while (pos > 0 && (bytes[pos].toInt() and 0xC0) == 0x80) {
            pos--
        }
        return pos
    }
}

/**
 * 工作区环境管理与操作执行服务。
 */
class WorkspaceEnvironmentService(
    private val workspaceStore: WorkspaceStore,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
    /** RF-07/R11: 工作区执行租约管理器——setup/action 脚本执行期间必须持有 checkout 租约。 */
    private val leaseManager: xyz.mederi.workspace.WorkspaceLeaseManager? = null
) {
    private val logBuffers = ConcurrentHashMap<String, WorkspaceOperationLogBuffer>()
    private val activeOperations = ConcurrentHashMap<String, Job>()

    private suspend fun trackOperation(
        operationId: String,
        execute: suspend () -> WorkspaceOperation
    ): WorkspaceOperation = coroutineScope {
        val job = coroutineContext.job
        check(activeOperations.putIfAbsent(operationId, job) == null)
        try {
            execute()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { markInterrupted(operationId) }
            throw cancelled
        } catch (failure: Exception) {
            val error = WorkspaceError(
                (failure as? MederiWorkspaceException)?.code ?: WorkspaceErrorCode.IO_ERROR,
                failure.message ?: "Environment script execution failed"
            )
            markInterrupted(operationId, error)
            throw failure
        } finally {
            activeOperations.remove(operationId, job)
        }
    }

    /** 等待此操作自己的审批或进程清理完成，不取消同会话的其他调用。 */
    suspend fun stopOperationExecution(operationId: String): Boolean {
        val job = activeOperations[operationId] ?: return false
        job.cancelAndJoin()
        return true
    }

    private suspend fun markInterrupted(operationId: String, error: WorkspaceError? = null) {
        val op = workspaceStore.getOperation(operationId) ?: return
        if (op.kind !in setOf(WorkspaceOperationKind.SETUP, WorkspaceOperationKind.ACTION) ||
            op.state !in setOf(
                WorkspaceOperationState.PENDING,
                WorkspaceOperationState.WAITING_APPROVAL,
                WorkspaceOperationState.RUNNING,
                WorkspaceOperationState.CANCELLING
            )
        ) return
        val cancelled = error == null
        logBuffers[operationId]?.append("\n[operation] ${error?.message ?: "Execution cancelled."}\n")
        workspaceStore.saveOperation(op.copy(
            state = if (cancelled) WorkspaceOperationState.CANCELLED else WorkspaceOperationState.FAILED,
            phase = WorkspacePhase.COMPLETE,
            cancelRequested = cancelled,
            error = error,
            version = op.version + 1,
            updatedAt = Instant.now().toString()
        ))
    }

    /**
     * 通过统一 [ShellTools]/[CommandSandbox] 后端执行环境脚本。
     *
     * SETUP/ACTION 不得再 ProcessBuilder 裸跑：实际启动 argv 必须是 CommandSandbox.wrap 的结果，
     * 这样默认受限无网络、deniedPaths/extraWritablePaths/protectedDirectories 生效、network 仅开网络、
     * off/FULL 仍受显式 deny 约束；子进程组与取消经 [ProcessRegistry] 与已有 shell 一致。
     *
     * 授权由 [ExecutionPermissions.authorizeCommand] 在 prepareCommand 内完成（default 不请求提升），
     * 缺少 permissions 的提升请求会在 ShellTools 内抛出，不会裸跑。
     */
    private suspend fun runEnvironmentScript(
        resolvedCode: String,
        workDir: File,
        checkoutRoot: String?,
        sandboxMode: String,
        timeoutSeconds: Int,
        permissions: ExecutionPermissions?,
        logBuffer: WorkspaceOperationLogBuffer,
        kind: String,
        permit: WorkspaceLeaseManager.ManagementPermit? = null,
    ): ShellTools.CommandResult {
        // R11: setup/action 在授权上下文下必须持有 checkout 执行租约，直到脚本退出——
        // 归档/配额靠租约计数判断「仍有执行在写盘」，没有租约的脚本期间 checkout 可能被强删。
        //
        // T04: 管理 permit 不可伪造——只有 withManagementIntent 内部创建的 permit 才能证明
        // 当前调用拥有管理意图所有权（POST_SETUP 场景）。持有合法 permit 时跳过共享租约；
        // 常规 action 无 permit，遇 WORKSPACE_BUSY 必须拒绝（不再吞掉异常继续执行）。
        val lease: AutoCloseable? = if (permissions != null && checkoutRoot != null && leaseManager != null) {
            if (permit != null && permit.covers(checkoutRoot)) {
                // 管理临界区内：permit 证明所有权，管理意图已保护 checkout，跳过共享租约。
                null
            } else {
                // 常规 action：必须取租约；BUSY 即拒绝，不继续。
                leaseManager.acquireExecutionLease(checkoutRoot)
            }
        } else null
        try {
            return runEnvironmentScriptUnleased(resolvedCode, workDir, sandboxMode, timeoutSeconds, permissions, logBuffer, kind)
        } finally {
            lease?.close()
        }
    }

    private suspend fun runEnvironmentScriptUnleased(
        resolvedCode: String,
        workDir: File,
        sandboxMode: String,
        timeoutSeconds: Int,
        permissions: ExecutionPermissions?,
        logBuffer: WorkspaceOperationLogBuffer,
        kind: String,
    ): ShellTools.CommandResult {
        // T08: 无授权上下文一律禁止执行——生产调用方必须提供真实 [ExecutionPermissions]，
        // null 不再走 ProcessBuilder 裸跑分支（deniedPaths/沙箱/进程组会被旁路）。
        if (permissions == null) {
            throw MederiWorkspaceException(
                WorkspaceErrorCode.INVALID_REQUEST,
                "Environment script requires execution permissions; null context is forbidden in production"
            )
        }
        val allowed = listOf(workDir.canonicalPath)
        val shell = ShellTools(
            allowedDirectories = allowed,
            sandbox = CommandSandbox(allowed),
            executionPermissions = permissions,
        )
        val result = shell.runCommand(
            command = resolvedCode,
            timeoutSeconds = timeoutSeconds.coerceAtLeast(1),
            cwd = workDir.path,
            sandboxMode = sandboxMode,
            toolName = kind,
            reapDescendantsOnCompletion = true,
        )
        logBuffer.append(result.output)
        if (result.output.isNotEmpty() && !result.output.endsWith("\n")) logBuffer.append("\n")
        return result
    }

    fun getLogBuffer(operationId: String): WorkspaceOperationLogBuffer {
        return logBuffers.computeIfAbsent(operationId) { WorkspaceOperationLogBuffer() }
    }

    fun readLog(operationId: String, afterOffset: Long, maxBytes: Int = 256 * 1024): LogChunk {
        val buf = logBuffers[operationId] ?: return LogChunk(text = "", nextOffset = 0, eof = true, truncated = false)
        return buf.readChunk(afterOffset, maxBytes)
    }

    /**
     * 解析当前平台脚本。
     * 选择规则：对应平台项非 null 优先；空字符串表示该平台不执行（不回退 default）；无覆盖才用 default。
     */
    fun resolvePlatformScript(
        script: PlatformScript,
        platformName: String = System.getProperty("os.name")
    ): String? {
        val os = platformName.lowercase()
        return when {
            os.contains("mac") -> script.macos ?: script.default
            os.contains("linux") -> script.linux ?: script.default
            os.contains("windows") -> script.windows ?: script.default
            else -> script.default
        }
    }

    /**
     * 列出工作区环境配置。
     */
    fun listEnvironments(workingDirectory: File): List<ProjectEnvironment> {
        val envDir = File(workingDirectory, ".mederi/environments")
        if (!envDir.exists() || !envDir.isDirectory) return emptyList()
        return envDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { f ->
                try {
                    json.decodeFromString(ProjectEnvironment.serializer(), f.readText())
                } catch (_: Exception) {
                    null
                }
            }?.sortedBy { it.id } ?: emptyList()
    }

    /**
     * 读取指定环境配置。
     */
    fun getEnvironment(workingDirectory: File, environmentId: String): ProjectEnvironment? {
        val envFile = File(workingDirectory, ".mederi/environments/$environmentId.json")
        if (!envFile.exists() || !envFile.isFile) return null
        return try {
            json.decodeFromString(ProjectEnvironment.serializer(), envFile.readText())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 保存环境配置（原子版本 CAS 检查）。
     */
    fun saveEnvironment(
        workingDirectory: File,
        environment: ProjectEnvironment,
        expectedVersion: Long?
    ): ProjectEnvironment {
        val envDir = File(workingDirectory, ".mederi/environments")
        envDir.mkdirs()
        val envFile = File(envDir, "${environment.id}.json")

        if (envFile.exists()) {
            val existing = try {
                json.decodeFromString(ProjectEnvironment.serializer(), envFile.readText())
            } catch (e: Exception) {
                throw MederiWorkspaceException(WorkspaceErrorCode.IO_ERROR, "Failed to parse existing environment file: ${e.message}")
            }
            if (expectedVersion != null && existing.version != expectedVersion) {
                throw MederiWorkspaceException(
                    WorkspaceErrorCode.ENVIRONMENT_CHANGED,
                    "Environment version mismatch: expected $expectedVersion, found ${existing.version}"
                )
            }
        } else if (expectedVersion != null && expectedVersion != 0L) {
            throw MederiWorkspaceException(
                WorkspaceErrorCode.ENVIRONMENT_CHANGED,
                "Environment does not exist but expectedVersion is $expectedVersion"
            )
        }

        val newVersion = (expectedVersion ?: 0L) + 1
        val updated = environment.copy(version = newVersion)
        val text = json.encodeToString(ProjectEnvironment.serializer(), updated)

        // 原子写入
        val tempFile = File(envDir, "${environment.id}.json.tmp")
        tempFile.writeText(text)
        if (!tempFile.renameTo(envFile)) {
            tempFile.copyTo(envFile, overwrite = true)
            tempFile.delete()
        }

        return updated
    }

    /**
     * 计算环境配置内容指纹。
     */
    fun computeEnvironmentHash(environment: ProjectEnvironment): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(json.encodeToString(ProjectEnvironment.serializer(), environment).toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 执行环境初始化 (Setup)。
     * 具备幂等去重、等待审批、进程组管理与日志排空能力。
     */
    suspend fun executeSetup(
        workspace: Workspace,
        environment: ProjectEnvironment,
        expectedHash: String,
        requestId: String,
        permissions: ExecutionPermissions? = null,
        dryRun: Boolean = false,
        permit: WorkspaceLeaseManager.ManagementPermit? = null
    ): WorkspaceOperation {
        val idempotencyKey = "SETUP:$requestId"
        val requestHash = computeEnvironmentHash(environment)
        val sessionId = permissions?.conversationId

        // 1. 幂等去重检查（仅已成功完成的 setup 才允许短路去重；失败或取消的历史操作允许重试重新执行脚本）
        val existingOp = workspaceStore.listOperations(workspace.projectId).find {
            it.kind == WorkspaceOperationKind.SETUP && it.idempotencyKey == idempotencyKey
        }
        if (existingOp != null && existingOp.state == WorkspaceOperationState.SUCCEEDED) {
            if (existingOp.requestHash == requestHash && existingOp.workspaceId == workspace.id && existingOp.sessionId == sessionId) {
                return existingOp
            } else {
                throw MederiWorkspaceException(
                    WorkspaceErrorCode.INVALID_REQUEST,
                    "RequestId $requestId already exists with a different request hash"
                )
            }
        }

        val operationId = "op_${UUID.randomUUID().toString().take(12)}"
        val logBuffer = getLogBuffer(operationId)

        // 2. 核验环境配置指纹
        val currentHash = computeEnvironmentHash(environment)
        if (currentHash != expectedHash) {
            throw MederiWorkspaceException(
                WorkspaceErrorCode.ENVIRONMENT_CHANGED,
                "Environment hash changed: expected $expectedHash, got $currentHash"
            )
        }

        val setupScript = environment.setup
        val resolvedCode = setupScript?.let { resolvePlatformScript(it) }

        // 若为空字符串，表示该平台显式不执行
        if (resolvedCode == "") {
            val plan = WorkspaceOperationPlan.SetupPlan(
                requestHash = requestHash,
                workspaceId = workspace.id,
                environmentId = environment.id,
                environmentVersion = environment.version,
                environmentHash = expectedHash,
                script = ""
            )
            val op = WorkspaceOperation(
                id = operationId,
                projectId = workspace.projectId,
                sessionId = sessionId,
                workspaceId = workspace.id,
                kind = WorkspaceOperationKind.SETUP,
                state = WorkspaceOperationState.SUCCEEDED,
                phase = WorkspacePhase.COMPLETE,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                operationPlan = plan,
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(op)
            logBuffer.append("[setup] Platform override specifies empty script; skipping execution.\n")
            return op
        }

        if (resolvedCode == null) {
            val plan = WorkspaceOperationPlan.SetupPlan(
                requestHash = requestHash,
                workspaceId = workspace.id,
                environmentId = environment.id,
                environmentVersion = environment.version,
                environmentHash = expectedHash,
                script = null
            )
            val op = WorkspaceOperation(
                id = operationId,
                projectId = workspace.projectId,
                sessionId = sessionId,
                workspaceId = workspace.id,
                kind = WorkspaceOperationKind.SETUP,
                state = WorkspaceOperationState.SUCCEEDED,
                phase = WorkspacePhase.COMPLETE,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                operationPlan = plan,
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(op)
            return op
        }

        // 3. 执行权限与审批检查
        val plan = WorkspaceOperationPlan.SetupPlan(
            requestHash = requestHash,
            workspaceId = workspace.id,
            environmentId = environment.id,
            environmentVersion = environment.version,
            environmentHash = expectedHash,
            script = resolvedCode
        )

        return trackOperation(operationId) {
            var op = WorkspaceOperation(
                id = operationId,
                projectId = workspace.projectId,
                sessionId = sessionId,
                workspaceId = workspace.id,
                kind = WorkspaceOperationKind.SETUP,
                state = WorkspaceOperationState.PENDING,
                phase = WorkspacePhase.SETUP,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                operationPlan = plan,
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(op)

            // 提升类脚本（非 default 且非 FULL）先持久化 WAITING_APPROVAL，让 UI 在审批挂起期间可见；
            // 实际授权由 runEnvironmentScript 内的 ShellTools.prepareCommand→authorizeCommand 完成，
            // 不再在此裸调 authorize 造成双重审批。dryRun 在挂起前返回。
            if (permissions != null) {
                val settings = permissions.manager.getSettings(workspace.projectId)
                if (settings.mode != ExecutionPermissionMode.FULL_ACCESS && setupScript.sandbox != "default") {
                    op = op.copy(state = WorkspaceOperationState.WAITING_APPROVAL, updatedAt = Instant.now().toString())
                    workspaceStore.saveOperation(op)
                    logBuffer.append("[setup] Script requires sandbox=${setupScript.sandbox}; waiting for execution approval...\n")

                    val checkHash = computeEnvironmentHash(environment)
                    if (checkHash != expectedHash) {
                        op = op.copy(
                            state = WorkspaceOperationState.FAILED,
                            error = WorkspaceError(WorkspaceErrorCode.ENVIRONMENT_CHANGED, "Environment changed during approval"),
                            updatedAt = Instant.now().toString()
                        )
                        workspaceStore.saveOperation(op)
                        throw MederiWorkspaceException(
                            WorkspaceErrorCode.ENVIRONMENT_CHANGED,
                            "Environment configuration changed during approval"
                        )
                    }

                    if (dryRun) {
                        return@trackOperation op
                    }
                }
            }

            // 4. 经统一沙盒后端启动（授权→包装→ProcessRegistry→超时/取消收割），argv 为 CommandSandbox.wrap 结果
            op = op.copy(state = WorkspaceOperationState.RUNNING, updatedAt = Instant.now().toString())
            workspaceStore.saveOperation(op)
            logBuffer.append("[setup] Starting execution: $resolvedCode\n")

            val workDir = File(workspace.checkoutRoot, workspace.projectRelativePath).let {
                if (it.exists()) it else File(workspace.checkoutRoot)
            }

            val result = try {
                runEnvironmentScript(
                    resolvedCode, workDir, workspace.checkoutRoot, setupScript.sandbox, setupScript.timeoutSeconds, permissions, logBuffer, "setup", permit
                )
            } catch (e: MederiWorkspaceException) {
                if (e.code == WorkspaceErrorCode.WORKSPACE_BUSY) {
                    op = op.copy(
                        state = WorkspaceOperationState.FAILED,
                        error = WorkspaceError(WorkspaceErrorCode.WORKSPACE_BUSY, "Workspace is busy: ${e.message}"),
                        updatedAt = Instant.now().toString()
                    )
                    workspaceStore.saveOperation(op)
                    logBuffer.append("\n[setup] FAILED: Workspace is busy (WORKSPACE_BUSY).\n")
                    if (permissions == null) throw e
                    return@trackOperation op
                }
                throw e
            }

            // 与 executeAction 一致：授权上下文下失败通过操作状态反馈，无授权上下文抛异常。
            if (result.timedOut) {
                op = op.copy(
                    state = WorkspaceOperationState.FAILED,
                    error = WorkspaceError(WorkspaceErrorCode.SETUP_TIMEOUT, "Setup timed out after ${setupScript.timeoutSeconds}s"),
                    updatedAt = Instant.now().toString()
                )
                workspaceStore.saveOperation(op)
                logBuffer.append("\n[setup] FAILED: Execution timed out.\n")
                if (permissions == null) throw MederiWorkspaceException(WorkspaceErrorCode.SETUP_TIMEOUT, "Setup timed out")
                return@trackOperation op
            }

            if (result.exitCode != 0) {
                op = op.copy(
                    state = WorkspaceOperationState.FAILED,
                    error = WorkspaceError(WorkspaceErrorCode.SETUP_FAILED, "Setup command failed with exit code ${result.exitCode}"),
                    updatedAt = Instant.now().toString()
                )
                workspaceStore.saveOperation(op)
                logBuffer.append("\n[setup] FAILED: Process exited with code ${result.exitCode}\n")
                if (permissions == null) throw MederiWorkspaceException(WorkspaceErrorCode.SETUP_FAILED, "Setup command failed with exit code ${result.exitCode}")
                return@trackOperation op
            }

            op = op.copy(
                state = WorkspaceOperationState.SUCCEEDED,
                phase = WorkspacePhase.COMPLETE,
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(op)
            logBuffer.append("\n[setup] SUCCEEDED: Process completed successfully.\n")
            op
        }
    }

    /**
     * 执行环境动作 (Action)。
     */
    suspend fun executeAction(
        workspace: Workspace,
        actionInput: WorkspaceActionInput,
        environment: ProjectEnvironment,
        permissions: ExecutionPermissions? = null,
        permit: WorkspaceLeaseManager.ManagementPermit? = null
    ): WorkspaceOperation {
        val idempotencyKey = "ACTION:${actionInput.requestId}"
        val action = environment.actions.find { it.id == actionInput.actionId }
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Action ${actionInput.actionId} not found")

        val requestHash = computeEnvironmentHash(environment)
        val sessionId = permissions?.conversationId

        // 幂等去重
        val existingOp = workspaceStore.listOperations(workspace.projectId).find {
            it.kind == WorkspaceOperationKind.ACTION && it.idempotencyKey == idempotencyKey
        }
        if (existingOp != null) {
            val existingPlan = existingOp.operationPlan as? WorkspaceOperationPlan.ActionPlan
            if (existingOp.requestHash == requestHash && existingOp.workspaceId == workspace.id &&
                existingOp.sessionId == sessionId && existingPlan?.actionId == action.id
            ) {
                return existingOp
            } else {
                throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "RequestId ${actionInput.requestId} already exists with different parameters")
            }
        }

        val operationId = "op_${UUID.randomUUID().toString().take(12)}"
        val logBuffer = getLogBuffer(operationId)

        val resolvedScript = resolvePlatformScript(action.script)
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "No script for current platform in action ${action.id}")

        val plan = WorkspaceOperationPlan.ActionPlan(
            requestHash = requestHash,
            workspaceId = workspace.id,
            environmentId = environment.id,
            actionId = action.id,
            environmentVersion = environment.version,
            script = resolvedScript
        )

        return trackOperation(operationId) {
            var op = WorkspaceOperation(
                id = operationId,
                projectId = workspace.projectId,
                sessionId = sessionId,
                workspaceId = workspace.id,
                kind = WorkspaceOperationKind.ACTION,
                state = WorkspaceOperationState.RUNNING,
                phase = WorkspacePhase.COMPLETE,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                operationPlan = plan,
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(op)
            logBuffer.append("[action] Running action '${action.name}': $resolvedScript\n")

            val workDir = File(workspace.checkoutRoot, workspace.projectRelativePath).let {
                if (it.exists()) it else File(workspace.checkoutRoot)
            }

            // ACTION 同样经统一沙盒后端：实际 argv 为 CommandSandbox.wrap 结果，deniedPaths/黑名单/进程组一致。
            val result = try {
                runEnvironmentScript(
                    resolvedScript, workDir, workspace.checkoutRoot, action.script.sandbox, action.script.timeoutSeconds, permissions, logBuffer, "action", permit
                )
            } catch (e: MederiWorkspaceException) {
                if (e.code == WorkspaceErrorCode.WORKSPACE_BUSY) {
                    op = op.copy(
                        state = WorkspaceOperationState.FAILED,
                        error = WorkspaceError(WorkspaceErrorCode.WORKSPACE_BUSY, "Workspace is busy: ${e.message}"),
                        updatedAt = Instant.now().toString()
                    )
                    workspaceStore.saveOperation(op)
                    logBuffer.append("\n[action] FAILED: Workspace is busy (WORKSPACE_BUSY).\n")
                    if (permissions == null) throw e
                    return@trackOperation op
                }
                throw e
            }

            // 在授权执行上下文（permissions != null）中，沙盒拒绝/命令失败通过操作状态反馈，不抛异常：
            // 调用方（生产 UI/管理意图）检查 op.state，被拒绝的副作用已被沙盒挡住、外部不变。
            // 无授权上下文的旧路径仍抛异常以暴露错误。
            if (result.timedOut) {
                op = op.copy(
                    state = WorkspaceOperationState.FAILED,
                    error = WorkspaceError(WorkspaceErrorCode.SETUP_TIMEOUT, "Action timed out"),
                    updatedAt = Instant.now().toString()
                )
                workspaceStore.saveOperation(op)
                if (permissions == null) throw MederiWorkspaceException(WorkspaceErrorCode.SETUP_TIMEOUT, "Action timed out")
                return@trackOperation op
            }

            if (result.exitCode != 0) {
                op = op.copy(
                    state = WorkspaceOperationState.FAILED,
                    error = WorkspaceError(WorkspaceErrorCode.SETUP_FAILED, "Action exited with code ${result.exitCode}"),
                    updatedAt = Instant.now().toString()
                )
                workspaceStore.saveOperation(op)
                if (permissions == null) throw MederiWorkspaceException(WorkspaceErrorCode.SETUP_FAILED, "Action exited with code ${result.exitCode}")
                return@trackOperation op
            }

            op = op.copy(
                state = WorkspaceOperationState.SUCCEEDED,
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(op)
            op
        }
    }

    /** 取消脚本，也可关闭没有活体执行的待批记录。 */
    suspend fun cancelOperation(operationId: String) {
        if (!stopOperationExecution(operationId)) markInterrupted(operationId)
    }
}
