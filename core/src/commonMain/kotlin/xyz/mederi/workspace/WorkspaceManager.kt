package xyz.mederi.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import xyz.mederi.api.exception.MederiNotFoundException
import xyz.mederi.api.exception.MederiStateException
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.MessageStatus
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.SessionStore
import xyz.mederi.store.WorkspaceStore
import xyz.mederi.tools.sandbox.ExecutionPermissions
import xyz.mederi.tools.sandbox.SandboxManager
import xyz.mederi.tools.sandbox.SandboxPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * 工作区唯一真理源与生命周期管理器。
 */
class WorkspaceManager(
    workspaceStore: WorkspaceStore,
    val sessionStore: SessionStore,
    val historyStore: HistoryStore,
    val gitDriver: GitWorkspaceDriver = GitWorkspaceDriver(),
    val worktreesRoot: String,
    val snapshotsRoot: String = File(worktreesRoot).parentFile?.resolve("workspace-snapshots")?.absolutePath ?: "/tmp/workspace-snapshots",
    val snapshotService: WorkspaceSnapshotService = WorkspaceSnapshotService(snapshotsRoot = File(snapshotsRoot), gitDriver = gitDriver),
    val includeService: WorkspaceIncludeService = WorkspaceIncludeService(gitDriver = gitDriver),
    val leaseManager: WorkspaceLeaseManager = gitDriver.leaseManager,
    val environmentService: WorkspaceEnvironmentService = WorkspaceEnvironmentService(workspaceStore = workspaceStore, leaseManager = leaseManager),
    val projectStore: xyz.mederi.store.ProjectStore? = null,
    val settingsStore: xyz.mederi.store.SettingsStore? = null,
    /** 项目级读写策略真理源：文件 API 必须在此查询当前有效黑名单并过滤拒绝条目，不能绕过前批文件工具的权限检查。 */
    val sandboxManager: SandboxManager? = null,
    /**
     * T09：宿主会话事件总线。非空时工作区状态/操作提交点经 [EventEmittingWorkspaceStore]
     * 外显 WORKSPACE_CHANGED / WORKSPACE_OPERATION_UPDATED（reducer 已就绪），供 UI 实时投影；
     * 默认 null 保持既有测试/嵌入路径不发射。
     */
    val eventBus: MutableSharedFlow<MederiEvent>? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 对外/内部统一的工作区存储句柄。eventBus 非空时包一层发射装饰器（透传全部读写，
     * 仅在 saveWorkspace / saveOperation / commitBinding 成功后补发事件），保证不遗漏任何提交点。
     */
    val workspaceStore: WorkspaceStore = if (eventBus != null) {
        EventEmittingWorkspaceStore(workspaceStore, this)
    } else workspaceStore

    /**
     * 工作区操作"活跃态"词表：这些状态的操作对会话投影可见（含 RECOVERY_REQUIRED，
     * 崩溃恢复后仍须保持可操作——见 T09）。
     */
    private val activeOperationStates = listOf(
        WorkspaceOperationState.PENDING,
        WorkspaceOperationState.RUNNING,
        WorkspaceOperationState.WAITING_APPROVAL,
        WorkspaceOperationState.CANCELLING,
        WorkspaceOperationState.RECOVERY_REQUIRED
    )

    /**
     * T09：发射 WORKSPACE_CHANGED 事件（payload: projectId / sessionId? / workspaceId? / workspaceInfo JSON）。
     * eventBus 为 null 时静默 no-op；payload 字段缺失时省略（reducer 按 projectId 归属、sessionId 可选过滤）。
     */
    private fun emitWorkspaceEvent(sessionId: String?, projectId: String, workspaceId: String?, wsInfo: ConversationWorkspaceInfo?) {
        val bus = eventBus ?: return
        val payload = mutableMapOf("projectId" to projectId)
        if (sessionId != null) payload["sessionId"] = sessionId
        if (workspaceId != null) payload["workspaceId"] = workspaceId
        if (wsInfo != null) {
            payload["workspaceInfo"] = json.encodeToString(ConversationWorkspaceInfo.serializer(), wsInfo)
        }
        bus.tryEmit(MederiEvent(type = EventType.WORKSPACE_CHANGED, sessionId = sessionId ?: "", payload = payload, timestamp = Instant.now().toString()))
    }

    /**
     * T09：发射 WORKSPACE_OPERATION_UPDATED 事件（payload: projectId / sessionId? / operation JSON）。
     * reducer 按 operation.version 去重，老版本忽略。
     */
    private fun emitOperationEvent(sessionId: String?, projectId: String, op: WorkspaceOperation) {
        val bus = eventBus ?: return
        val payload = mutableMapOf("projectId" to projectId, "operation" to json.encodeToString(WorkspaceOperation.serializer(), op))
        if (sessionId != null) payload["sessionId"] = sessionId
        bus.tryEmit(MederiEvent(type = EventType.WORKSPACE_OPERATION_UPDATED, sessionId = sessionId ?: "", payload = payload, timestamp = Instant.now().toString()))
    }

    /**
     * T09：由 [Workspace] 重建 [ConversationWorkspaceInfo]（active 动态信息 + 当前活跃操作）。
     * 供事件装饰器在提交点补发 WORKSPACE_CHANGED；git 探测失败返回 null（此时仅发操作事件保进度）。
     */
    private suspend fun buildConversationWorkspaceInfo(ws: Workspace): ConversationWorkspaceInfo? = runCatching {
        val activeInfo = getWorkspaceInfo(ws)
        val op = workspaceStore.listOperations(ws.projectId).firstOrNull {
            it.workspaceId == ws.id && it.state in activeOperationStates
        }
        ConversationWorkspaceInfo(active = activeInfo, associated = null, operation = op)
    }.getOrNull()

    /**
     * T09：工作区存储发射装饰器。透传全部读写；在 saveWorkspace / saveOperation / commitBinding
     * 成功后补发 WORKSPACE_CHANGED / WORKSPACE_OPERATION_UPDATED，保证所有状态提交点（prepare/handoff/
     * archive/recovery/scan）一致外显。仅当 eventBus 非空时装配。
     */
    private class EventEmittingWorkspaceStore(
        private val delegate: WorkspaceStore,
        private val manager: WorkspaceManager,
    ) : WorkspaceStore {
        override suspend fun getWorkspace(id: String): Workspace? = delegate.getWorkspace(id)
        override suspend fun listWorkspaces(projectId: String): List<Workspace> = delegate.listWorkspaces(projectId)
        override suspend fun saveWorkspace(workspace: Workspace) {
            delegate.saveWorkspace(workspace)
            runCatching {
                manager.emitWorkspaceEvent(
                    sessionId = workspace.associatedSessionId,
                    projectId = workspace.projectId,
                    workspaceId = workspace.id,
                    wsInfo = manager.buildConversationWorkspaceInfo(workspace)
                )
            }
        }
        override suspend fun deleteWorkspace(id: String): Unit = delegate.deleteWorkspace(id)
        override suspend fun getOperation(id: String): WorkspaceOperation? = delegate.getOperation(id)
        override suspend fun getOperationByIdempotencyKey(key: String): WorkspaceOperation? = delegate.getOperationByIdempotencyKey(key)
        override suspend fun listOperations(projectId: String): List<WorkspaceOperation> = delegate.listOperations(projectId)
        override suspend fun saveOperation(operation: WorkspaceOperation) {
            delegate.saveOperation(operation)
            runCatching {
                manager.emitOperationEvent(
                    sessionId = operation.sessionId,
                    projectId = operation.projectId,
                    op = operation
                )
            }
        }
        override suspend fun deleteOperation(id: String): Unit = delegate.deleteOperation(id)
        override suspend fun commitBinding(
            sessionId: String,
            expectedWorkspaceId: String?,
            expectedGeneration: Long?,
            newWorkspace: Workspace,
            associatedWorktreeId: String?,
            operation: WorkspaceOperation
        ): Boolean {
            val result = delegate.commitBinding(
                sessionId = sessionId,
                expectedWorkspaceId = expectedWorkspaceId,
                expectedGeneration = expectedGeneration,
                newWorkspace = newWorkspace,
                associatedWorktreeId = associatedWorktreeId,
                operation = operation
            )
            if (result) {
                runCatching {
                    manager.emitWorkspaceEvent(
                        sessionId = sessionId,
                        projectId = newWorkspace.projectId,
                        workspaceId = newWorkspace.id,
                        wsInfo = manager.buildConversationWorkspaceInfo(newWorkspace)
                    )
                    manager.emitOperationEvent(
                        sessionId = operation.sessionId,
                        projectId = operation.projectId,
                        op = operation
                    )
                }
            }
            return result
        }
    }

    /**
     * 解析并校验工作区文件目标路径。
     *
     * 用 [Path.startsWith]（逐路径组件比较）替代字符串前缀判断，避免 `repo` 把 `repo-secrets` 当作内部；
     * canonical 已消解符号链接与穿越，落在根外的目标（含根内符号链接指向根外）一律拒绝。
     */
    private fun resolveFileTarget(root: File, path: String): File {
        val target = if (path.isBlank()) root else File(root, path).canonicalFile
        if (!target.toPath().startsWith(root.toPath())) {
            throw MederiWorkspaceException(WorkspaceErrorCode.PATH_DENIED, "Path escapes workspace root: $path")
        }
        return target
    }

    /**
     * R04: deniedPaths 是原始项目根的绝对路径；managed checkout 内容在同相对路径但不同绝对路径下。
     * 把项目根内的 denied 条目重映射到工作区根，使受管副本同样受黑名单约束。
     */
    private suspend fun remapDeniedForRoot(projectId: String, workspaceRoot: File): List<String> {
        val manager = sandboxManager ?: return emptyList()
        val settings = manager.getSettings(projectId)
        if (settings.deniedPaths.isEmpty()) return emptyList()
        val projectDir = projectStore?.get(projectId)?.directory ?: return settings.deniedPaths
        val projCanonical = File(projectDir).canonicalFile
        return settings.deniedPaths.map { denied ->
            val deniedFile = File(denied).canonicalFile
            if (deniedFile.toPath().startsWith(projCanonical.toPath())) {
                val rel = projCanonical.toPath().relativize(deniedFile.toPath()).toString().replace('\\', '/')
                File(workspaceRoot, rel).canonicalPath
            } else denied
        }
    }

    /**
     * 对读取目标应用当前项目有效读策略（deniedPaths）。拒绝条目不可读、不可列举。
     * 与 [ExecutionPermissions.readPolicy] / [SandboxPaths.allowsRead] 同一真理源。
     */
    private suspend fun enforceReadPolicy(projectId: String, workspaceRoot: File, vararg targets: File) {
        val remappedDenied = remapDeniedForRoot(projectId, workspaceRoot)
        if (remappedDenied.isEmpty()) return
        val policy = SandboxPaths(
            projectDirs = listOf(workspaceRoot.absolutePath),
            extraWritablePaths = emptyList(),
            tempDirs = emptyList(),
            deniedPaths = remappedDenied,
        )
        if (targets.any { !policy.allowsRead(it.toPath()) }) {
            throw MederiWorkspaceException(WorkspaceErrorCode.PATH_DENIED, "Path is denied by this project's blacklist: $projectId")
        }
    }

    /**
     * 发现仓库信息。
     */
    suspend fun getRepositoryInfo(repoRoot: String): RepositoryInfo {
        return gitDriver.discover(repoRoot)
    }

    /**
     * 创建或预留工作区。
     */
    suspend fun createWorkspace(
        projectId: String,
        sourceRepoRoot: String,
        projectRelativePath: String = "",
        selection: WorkspaceSelection = WorkspaceSelection(),
        sessionId: String? = null
    ): Workspace {
        val root = File(sourceRepoRoot).canonicalFile
        val cleanRel = projectRelativePath.trim().trim('/', '\\')

        if (selection.kind == WorkspaceKind.LOCAL) {
            val localId = "ws_local_${projectId.take(16)}"
            val pending = PendingWorkspaceSelection(
                selection = selection,
                sourceCheckoutId = root.absolutePath,
                environmentId = selection.environmentId
            )
            val existing = workspaceStore.getWorkspace(localId)
            if (existing != null) {
                if (sessionId != null) {
                    sessionStore.updateWorkspaceBinding(sessionId, workspaceId = localId, associatedWorktreeId = null)
                }
                return existing
            }

            val repoInfo = gitDriver.discover(root.absolutePath)
            val ws = Workspace(
                id = localId,
                projectId = projectId,
                kind = WorkspaceKind.LOCAL,
                checkoutRoot = root.absolutePath,
                projectRelativePath = cleanRel,
                gitCommonDirectory = repoInfo.gitCommonDirectory,
                baseCommit = repoInfo.headOid,
                state = WorkspaceState.READY,
                generation = 1L,
                createdAt = Instant.now().toString(),
                lastUsedAt = Instant.now().toString(),
                pendingSelection = pending
            )
            workspaceStore.saveWorkspace(ws)
            if (sessionId != null) {
                sessionStore.updateWorkspaceBinding(sessionId, workspaceId = localId, associatedWorktreeId = null)
            }
            return ws
        }

        // MANAGED_WORKTREE: 预留 REQUESTED 状态
        val workspaceId = "ws_${UUID.randomUUID().toString().take(12)}"
        val checkoutRoot = File(getWorktreeSettings().rootPath, "$workspaceId/checkout").canonicalPath
        val repoInfo = gitDriver.discover(root.absolutePath)

        val pending = PendingWorkspaceSelection(
            selection = selection,
            sourceCheckoutId = root.absolutePath,
            environmentId = selection.environmentId
        )

        val ws = Workspace(
            id = workspaceId,
            projectId = projectId,
            kind = WorkspaceKind.MANAGED_WORKTREE,
            checkoutRoot = checkoutRoot,
            projectRelativePath = cleanRel,
            gitCommonDirectory = repoInfo.gitCommonDirectory,
            baseCommit = null,
            associatedSessionId = sessionId,
            state = WorkspaceState.REQUESTED,
            generation = 0L,
            createdAt = Instant.now().toString(),
            lastUsedAt = Instant.now().toString(),
            pendingSelection = pending
        )
        workspaceStore.saveWorkspace(ws)

        if (sessionId != null) {
            sessionStore.updateWorkspaceBinding(sessionId, workspaceId = workspaceId, associatedWorktreeId = workspaceId)
        }

        return ws
    }

    /**
     * 稳定接纳首条/用户输入。
     * 确保同一 messageId 只落库一次，准备失败保留输入，重试准备复用该输入。
     */
    suspend fun acceptUserMessage(
        sessionId: String,
        messageId: String,
        content: String,
        sourceRepoRoot: String,
        permissions: ExecutionPermissions? = null
    ): Boolean {
        val existingHistory = historyStore.load(sessionId)
        val alreadyExists = existingHistory.any { it.id == messageId }

        if (!alreadyExists) {
            val msg = Message(
                id = messageId,
                sessionId = sessionId,
                role = MessageRole.USER,
                parts = listOf(MessagePart.Text(content)),
                createdAt = Instant.now().toString(),
                status = MessageStatus.COMPLETED,
                pendingSteering = true
            )
            historyStore.append(sessionId, msg)
        }

        // 核对工作区准备状态门禁
        val session = sessionStore.get(sessionId) ?: return true
        val wsId = session.workspaceId ?: return true
        val ws = workspaceStore.getWorkspace(wsId) ?: return true

        if (ws.state == WorkspaceState.REQUESTED) {
            val op = runCatching {
                prepareWorkspace(
                    workspaceId = wsId,
                    sourceRepoRoot = sourceRepoRoot,
                    requestId = "create_${ws.id}",
                    permissions = permissions
                )
            }.getOrNull()
            return op?.state == WorkspaceOperationState.SUCCEEDED
        }

        return ws.state == WorkspaceState.READY
    }

    /**
     * 检查当前会话的模型执行门禁。
     * 工作区未达到 READY 时绝不启动模型，绝不静默回退 LOCAL。
     */
    suspend fun isModelExecutable(sessionId: String): Boolean {
        val session = sessionStore.get(sessionId) ?: return false
        val wsId = session.workspaceId ?: return false
        val ws = workspaceStore.getWorkspace(wsId) ?: return false
        return ws.state == WorkspaceState.READY
    }

    /**
     * RF-02：准备会话当前绑定的工作区（若处于 REQUESTED）。首次 send 沿统一消息接纳落 messageId 后，
     * resolve 命中非 READY 时由 TurnExecutor 调用本方法把 managed 工作区置 READY，再执行模型工具。
     * 返回 true 表示工作区已 READY（原本就 READY 或本次准备成功）。
     */
    suspend fun prepareSessionWorkspace(sessionId: String): Boolean {
        val session = sessionStore.get(sessionId) ?: return false
        val wsId = session.workspaceId ?: return false
        val ws = workspaceStore.getWorkspace(wsId) ?: return false
        if (ws.state == WorkspaceState.READY) return true
        if (ws.state != WorkspaceState.REQUESTED) return false
        val project = projectStore?.get(ws.projectId) ?: return false
        // R01: 生产准备也必须传真实权限（setup 脚本不得裸跑）
        val permissions = buildWorkspacePermissions(ws, sessionId)
        return try {
            prepareWorkspace(wsId, project.directory, "send_prepare_$sessionId", permissions = permissions).state == WorkspaceOperationState.SUCCEEDED
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 准备工作区 (CREATE 流程)。
     */
    suspend fun prepareWorkspace(
        workspaceId: String,
        sourceRepoRoot: String,
        requestId: String,
        permissions: ExecutionPermissions? = null
    ): WorkspaceOperation {
        var ws = workspaceStore.getWorkspace(workspaceId)
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_MISSING, "Workspace $workspaceId not found")

        val idempotencyKey = "CREATE:$requestId"

        // 幂等去重检查
        val existingOp = workspaceStore.listOperations(ws.projectId).find {
            it.kind == WorkspaceOperationKind.CREATE && it.idempotencyKey == idempotencyKey
        }
        if (existingOp != null && existingOp.state == WorkspaceOperationState.SUCCEEDED && ws.state == WorkspaceState.READY) {
            return existingOp
        }

        val pending = ws.pendingSelection
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "No pending selection for workspace $workspaceId")
        val selection = pending.selection
        val srcRoot = File(sourceRepoRoot).canonicalFile

        val requestHash = "${ws.projectId}:${selection.baseRef}:${selection.carryLocalChanges}:${selection.environmentId}"

        val operationId = existingOp?.id ?: "op_${UUID.randomUUID().toString().take(12)}"
        val logBuffer = environmentService.getLogBuffer(operationId)

        // 阶段 1: VALIDATE
        logBuffer.append("[prepare] Validating source repository and base ref...\n")
        ws = ws.copy(state = WorkspaceState.PREPARING)
        workspaceStore.saveWorkspace(ws)

        val baseRef = selection.baseRef ?: "HEAD"
        val baseCommitRes = gitDriver.runGit(srcRoot.absolutePath, listOf("rev-parse", "--verify", "$baseRef^{commit}"))
        if (baseCommitRes.exitCode != 0) {
            val error = WorkspaceError(WorkspaceErrorCode.NO_BASE_COMMIT, "Base ref $baseRef could not be resolved: ${baseCommitRes.stderr}")
            val op = WorkspaceOperation(
                id = operationId,
                projectId = ws.projectId,
                workspaceId = ws.id,
                kind = WorkspaceOperationKind.CREATE,
                state = WorkspaceOperationState.FAILED,
                phase = WorkspacePhase.VALIDATE,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                error = error,
                operationPlan = WorkspaceOperationPlan.CreatePlan(requestHash = requestHash, selection = selection, targetPath = ws.checkoutRoot),
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(op)
            throw MederiWorkspaceException(WorkspaceErrorCode.NO_BASE_COMMIT, error.message)
        }
        val baseOid = baseCommitRes.stdoutString.trim()

        // 校验 carryLocalChanges 约束：基线必须与当前源 HEAD 严格一致
        if (selection.carryLocalChanges) {
            val headRes = gitDriver.runGit(srcRoot.absolutePath, listOf("rev-parse", "--verify", "HEAD^{commit}"))
            val curHeadOid = headRes.stdoutString.trim()
            if (curHeadOid != baseOid) {
                val error = WorkspaceError(WorkspaceErrorCode.BASE_CHANGED, "Cannot carry local changes across different base commit ($curHeadOid vs $baseOid)")
                val op = WorkspaceOperation(
                    id = operationId,
                    projectId = ws.projectId,
                    workspaceId = ws.id,
                    kind = WorkspaceOperationKind.CREATE,
                    state = WorkspaceOperationState.FAILED,
                    phase = WorkspacePhase.VALIDATE,
                    idempotencyKey = idempotencyKey,
                    requestHash = requestHash,
                    error = error,
                    operationPlan = WorkspaceOperationPlan.CreatePlan(requestHash = requestHash, selection = selection, targetPath = ws.checkoutRoot),
                    createdAt = Instant.now().toString(),
                    updatedAt = Instant.now().toString()
                )
                workspaceStore.saveOperation(op)
                throw MederiWorkspaceException(WorkspaceErrorCode.BASE_CHANGED, error.message)
            }
        }

        // 阶段 2: QUIESCE / SNAPSHOT
        var transferSnapshotId: String? = null
        if (selection.carryLocalChanges) {
            logBuffer.append("[prepare] Capturing TRANSFER snapshot of source local changes...\n")
            val snap = snapshotService.captureSnapshot(
                checkoutRoot = srcRoot.absolutePath,
                workspaceId = ws.id,
                reason = "create_carry_transfer",
                captureType = SnapshotCaptureType.TRANSFER
            )
            transferSnapshotId = snap.snapshotId
        }

        // 阶段 3: CREATE_CHECKOUT
        val checkoutDir = File(ws.checkoutRoot).canonicalFile
        val isAlreadyCreated = File(checkoutDir, ".git").exists()

        if (!isAlreadyCreated) {
            logBuffer.append("[prepare] Creating git worktree at ${checkoutDir.path}...\n")
            checkoutDir.parentFile?.mkdirs()
            val addRes = gitDriver.runGit(
                srcRoot.absolutePath,
                listOf("worktree", "add", "--detach", checkoutDir.absolutePath, baseOid)
            )
            if (addRes.exitCode != 0) {
                val error = WorkspaceError(WorkspaceErrorCode.IO_ERROR, "git worktree add failed: ${addRes.stderr}")
                val op = WorkspaceOperation(
                    id = operationId,
                    projectId = ws.projectId,
                    workspaceId = ws.id,
                    kind = WorkspaceOperationKind.CREATE,
                    state = WorkspaceOperationState.FAILED,
                    phase = WorkspacePhase.CREATE_CHECKOUT,
                    idempotencyKey = idempotencyKey,
                    requestHash = requestHash,
                    error = error,
                    operationPlan = WorkspaceOperationPlan.CreatePlan(requestHash = requestHash, selection = selection, targetPath = ws.checkoutRoot),
                    createdAt = Instant.now().toString(),
                    updatedAt = Instant.now().toString()
                )
                workspaceStore.saveOperation(op)
                throw MederiWorkspaceException(WorkspaceErrorCode.IO_ERROR, error.message)
            }
        } else {
            logBuffer.append("[prepare] Checkout directory already exists, resuming...\n")
        }

        // 阶段 4: COPY_CONTENT
        // 校验 projectRelativePath
        if (ws.projectRelativePath.isNotEmpty()) {
            val targetProjDir = File(checkoutDir, ws.projectRelativePath)
            if (!targetProjDir.exists() || !targetProjDir.isDirectory) {
                val error = WorkspaceError(
                    WorkspaceErrorCode.PROJECT_SUBDIRECTORY_MISSING,
                    "Project relative path ${ws.projectRelativePath} missing in target checkout"
                )
                val op = WorkspaceOperation(
                    id = operationId,
                    projectId = ws.projectId,
                    workspaceId = ws.id,
                    kind = WorkspaceOperationKind.CREATE,
                    state = WorkspaceOperationState.FAILED,
                    phase = WorkspacePhase.COPY_CONTENT,
                    idempotencyKey = idempotencyKey,
                    requestHash = requestHash,
                    error = error,
                    operationPlan = WorkspaceOperationPlan.CreatePlan(requestHash = requestHash, selection = selection, targetPath = ws.checkoutRoot),
                    createdAt = Instant.now().toString(),
                    updatedAt = Instant.now().toString()
                )
                workspaceStore.saveOperation(op)
                throw MederiWorkspaceException(WorkspaceErrorCode.PROJECT_SUBDIRECTORY_MISSING, error.message)
            }
        }

        // 若开启 carryLocalChanges，将 transferSnapshot 内容还原到工作区（源不改动）
        if (selection.carryLocalChanges && transferSnapshotId != null) {
            logBuffer.append("[prepare] Materializing carried local changes...\n")
            materializeCarriedSnapshot(transferSnapshotId, checkoutDir)
        }

        // 执行包含文件复制
        val includeFile = File(srcRoot, ".worktreeinclude")
        val includeRules = if (includeFile.exists()) includeFile.readText() else null
        val candidates = includeService.computeCandidates(
            repoRoot = srcRoot.absolutePath,
            includeRulesContent = includeRules,
            selectedEnvironmentFileRel = selection.environmentId?.let { ".mederi/environments/$it.json" }
        )
        if (candidates.isNotEmpty()) {
            logBuffer.append("[prepare] Copying ${candidates.size} included candidate files...\n")
            includeService.copyIncludeFiles(srcRoot.absolutePath, checkoutDir.absolutePath, candidates)
        }

        // 阶段 5: SETUP (恢复时不重复执行已完成的 setup)
        var setupExecuted = existingOp?.phaseRecords?.any { it.phase == WorkspacePhase.SETUP && it.finishedAt != null } ?: false
        if (!setupExecuted && selection.environmentId != null) {
            val targetEnvFile = File(checkoutDir, ".mederi/environments/${selection.environmentId}.json")
            val srcEnvFile = File(srcRoot, ".mederi/environments/${selection.environmentId}.json")
            if (!targetEnvFile.exists() && srcEnvFile.exists() && srcEnvFile.isFile) {
                targetEnvFile.parentFile?.mkdirs()
                srcEnvFile.copyTo(targetEnvFile, overwrite = true)
            }

            logBuffer.append("[prepare] Executing environment setup for '${selection.environmentId}'...\n")
            val env = environmentService.getEnvironment(checkoutDir, selection.environmentId)
            if (env != null && env.setup != null) {
                val envHash = environmentService.computeEnvironmentHash(env)
                // T08: 生产调用方不传 permissions 时用 buildWorkspacePermissions 兜底构造真实权限，
                // 绝不允许 null 透传给 executeSetup 造成 setup 脚本裸跑（deniedPaths/沙箱/进程组旁路）。
                val setupPermissions = permissions ?: buildWorkspacePermissions(ws)
                try {
                    val setupResult = environmentService.executeSetup(
                        workspace = ws,
                        environment = env,
                        expectedHash = envHash,
                        requestId = "setup_${ws.id}_${operationId}",
                        permissions = setupPermissions
                    )
                    // T11: 仅 SUCCEEDED 才允许进入 COMMIT_BINDING/READY；FAILED/WAITING/CANCELLED 一律中止。
                    if (setupResult.state != WorkspaceOperationState.SUCCEEDED) {
                        throw MederiWorkspaceException(
                            WorkspaceErrorCode.SETUP_FAILED,
                            "Environment setup did not succeed: ${setupResult.state}"
                        )
                    }
                    setupExecuted = true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val error = WorkspaceError(WorkspaceErrorCode.SETUP_FAILED, "Environment setup failed: ${e.message}")
                    val op = WorkspaceOperation(
                        id = operationId,
                        projectId = ws.projectId,
                        workspaceId = ws.id,
                        kind = WorkspaceOperationKind.CREATE,
                        state = WorkspaceOperationState.FAILED,
                        phase = WorkspacePhase.SETUP,
                        idempotencyKey = idempotencyKey,
                        requestHash = requestHash,
                        error = error,
                        operationPlan = WorkspaceOperationPlan.CreatePlan(requestHash = requestHash, selection = selection, targetPath = ws.checkoutRoot),
                        createdAt = Instant.now().toString(),
                        updatedAt = Instant.now().toString()
                    )
                    workspaceStore.saveOperation(op)
                    throw e
                }
            }
        }

        // 阶段 6: COMMIT_BINDING
        logBuffer.append("[prepare] Committing binding and opening execution gate...\n")
        val newGeneration = ws.generation + 1
        val updatedWs = ws.copy(
            state = WorkspaceState.READY,
            generation = newGeneration,
            baseCommit = baseOid,
            lastUsedAt = Instant.now().toString()
        )

        val plan = WorkspaceOperationPlan.CreatePlan(
            requestHash = requestHash,
            selection = selection,
            baseOid = baseOid,
            targetPath = ws.checkoutRoot,
            environmentId = selection.environmentId,
            snapshotId = transferSnapshotId
        )

        val successOp = WorkspaceOperation(
            id = operationId,
            projectId = ws.projectId,
            sessionId = ws.associatedSessionId,
            workspaceId = ws.id,
            kind = WorkspaceOperationKind.CREATE,
            state = WorkspaceOperationState.SUCCEEDED,
            phase = WorkspacePhase.COMPLETE,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            operationPlan = plan,
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString()
        )

        if (ws.associatedSessionId != null) {
            workspaceStore.commitBinding(
                sessionId = ws.associatedSessionId!!,
                expectedWorkspaceId = ws.id,
                expectedGeneration = ws.generation,
                newWorkspace = updatedWs,
                associatedWorktreeId = ws.id,
                operation = successOp
            )
        } else {
            workspaceStore.saveWorkspace(updatedWs)
            workspaceStore.saveOperation(successOp)
        }

        logBuffer.append("[prepare] Workspace is READY.\n")
        return successOp
    }

    private suspend fun materializeCarriedSnapshot(snapshotId: String, targetRoot: File) {
        val snapshotDir = File(snapshotService.snapshotsRoot, snapshotId)
        val manifestFile = File(snapshotDir, "manifest.json")
        if (!manifestFile.exists()) return

        val manifest = json.decodeFromString(SnapshotManifest.serializer(), manifestFile.readText())
        // 统一使用安全物化原语（safeMaterializeFile/safeMaterializeSymlink/safeDeleteEntry）：
        // 解除末级符号链接再写文件，防写穿到链接目标；逐级校验祖先防路径逃逸。
        snapshotService.materializeSnapshotIntoCheckout(snapshotDir, manifest, targetRoot)
    }

    private val previewCache = java.util.concurrent.ConcurrentHashMap<String, CachedHandoffPreview>()

    data class CachedHandoffPreview(
        val preview: HandoffPreview,
        val sessionId: String,
        val createdAt: Instant,
        val sourceWorkspace: Workspace,
        val targetWorkspace: Workspace,
        val sourceHeadOid: String,
        val targetHeadOid: String?,
        val sourceFingerprint: String,
        val taskBranch: String,
        val affectedSessionIds: List<String>
    )

    private suspend fun createNewManagedWorkspaceForHandoff(sourceWs: Workspace, session: xyz.mederi.domain.model.Session): Workspace {
        val targetWsId = "ws_${UUID.randomUUID().toString().take(12)}"
        val checkoutRoot = File(getWorktreeSettings().rootPath, "$targetWsId/checkout").canonicalPath
        File(checkoutRoot).parentFile?.mkdirs()

        val sourceHeadRes = gitDriver.runGit(sourceWs.checkoutRoot, listOf("rev-parse", "--verify", "HEAD^{commit}"))
        val baseOid = if (sourceHeadRes.exitCode == 0) sourceHeadRes.stdoutString.trim() else "HEAD"

        val addRes = gitDriver.runGit(sourceWs.checkoutRoot, listOf("worktree", "add", "--detach", checkoutRoot, baseOid))
        if (addRes.exitCode != 0) {
            throw MederiWorkspaceException(WorkspaceErrorCode.IO_ERROR, "git worktree add failed: ${addRes.stderr}")
        }

        val targetWs = Workspace(
            id = targetWsId,
            projectId = sourceWs.projectId,
            kind = WorkspaceKind.MANAGED_WORKTREE,
            checkoutRoot = checkoutRoot,
            projectRelativePath = "",
            gitCommonDirectory = sourceWs.gitCommonDirectory,
            baseCommit = baseOid,
            associatedSessionId = session.id,
            pendingSelection = sourceWs.pendingSelection,
            state = WorkspaceState.READY,
            generation = 0L,
            createdAt = Instant.now().toString(),
            lastUsedAt = Instant.now().toString()
        )
        workspaceStore.saveWorkspace(targetWs)
        sessionStore.updateWorkspaceBinding(session.id, workspaceId = sourceWs.id, associatedWorktreeId = targetWsId)
        return targetWs
    }

    /**
     * Handoff 预览（生成 60s TTL 的 previewToken 与冲突核验）。
     */
    suspend fun previewHandoff(sessionId: String, input: HandoffPreviewInput): HandoffPreview {
        val session = sessionStore.get(sessionId)
            ?: throw MederiNotFoundException("Session $sessionId not found")
        val wsId = session.workspaceId
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_MISSING, "Session has no bound workspace")
        val sourceWs = workspaceStore.getWorkspace(wsId)
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_MISSING, "Workspace $wsId not found")

        val targetKind = input.targetKind
        if (sourceWs.kind == targetKind) {
            throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Source and target workspace kind are both $targetKind")
        }

        // 确定目标工作区
        val targetWs: Workspace = when (targetKind) {
            WorkspaceKind.LOCAL -> {
                val localWsId = "ws_local_${sourceWs.projectId}"
                workspaceStore.getWorkspace(localWsId)
                    ?: throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_MISSING, "Local workspace not found")
            }
            WorkspaceKind.MANAGED_WORKTREE -> {
                val assocId = session.associatedWorktreeId
                if (assocId != null) {
                    workspaceStore.getWorkspace(assocId) ?: createNewManagedWorkspaceForHandoff(sourceWs, session)
                } else {
                    createNewManagedWorkspaceForHandoff(sourceWs, session)
                }
            }
        }

        val sourceRoot = sourceWs.checkoutRoot
        val targetRoot = targetWs.checkoutRoot

        // 检查受影响会话
        val allSessions = sessionStore.list().filter { it.projectId == sourceWs.projectId }
        val affectedSessions = allSessions.filter { it.workspaceId == sourceWs.id && it.id != session.id }
            .map { AffectedSession(id = it.id, title = it.title, running = false) }

        // 探测 HEAD commit 和分支
        val sourceRepo = gitDriver.discover(sourceRoot)
        val targetRepo = gitDriver.discover(targetRoot)

        val conflicts = mutableListOf<WorkspaceConflict>()

        // 校验目标状态：必须 clean（排除 Mederi 自身运行时产物：notebook/plans/research 等 D09 工件，
        // 会话活动会持续写入这些文件，不能用它们阻断 handoff）
        val targetStatusRes = gitDriver.runGit(targetRoot, listOf("status", "--porcelain=v2", "-z", "--untracked-files=all"))
        if (targetStatusRes.exitCode == 0) {
            val statusEntries = gitDriver.parsePorcelainV2(targetStatusRes.stdoutBytes)
                .filterNot { WorkspaceSnapshotService.isD09ArtifactPath(it.path) }
            if (statusEntries.isNotEmpty()) {
                for (entry in statusEntries) {
                    conflicts.add(
                        WorkspaceConflict(
                            path = entry.path,
                            reason = WorkspaceErrorCode.TARGET_DIRTY
                        )
                    )
                }
            }
        }

        // 分支推导逻辑
        val currentBranch = sourceRepo.branch
        val isDetached = currentBranch == null || sourceRepo.headOid == null
        val taskBranch = when {
            sourceWs.kind == WorkspaceKind.MANAGED_WORKTREE && !isDetached && currentBranch != null -> {
                currentBranch
            }
            sourceWs.kind == WorkspaceKind.MANAGED_WORKTREE && isDetached -> {
                input.taskBranchName ?: "task/${session.id.takeLast(8)}"
            }
            sourceWs.kind == WorkspaceKind.LOCAL && currentBranch != null && currentBranch.startsWith("task/") -> {
                currentBranch
            }
            sourceWs.kind == WorkspaceKind.LOCAL -> {
                input.taskBranchName ?: "task/${session.id.takeLast(8)}"
            }
            else -> input.taskBranchName ?: "task/${session.id.takeLast(8)}"
        }

        val sourceFp = snapshotService.computeFingerprint(sourceRoot)
        val targetFp = snapshotService.computeFingerprint(targetRoot)

        val token = "prev_${UUID.randomUUID().toString().take(12)}"
        val now = Instant.now()
        val expiresAt = now.plusSeconds(60).toString()

        val preview = HandoffPreview(
            token = token,
            expiresAt = expiresAt,
            source = WorkspaceInfo(
                workspace = sourceWs,
                headOid = sourceRepo.headOid ?: "",
                branch = sourceRepo.branch,
                dirty = conflicts.isNotEmpty(),
                capabilities = emptySet()
            ),
            target = WorkspaceInfo(
                workspace = targetWs,
                headOid = targetRepo.headOid ?: "",
                branch = targetRepo.branch,
                dirty = conflicts.isNotEmpty(),
                capabilities = emptySet()
            ),
            fingerprints = mapOf("source" to sourceFp, "target" to targetFp),
            affectedSessions = affectedSessions,
            taskBranch = taskBranch,
            conflicts = conflicts
        )

        previewCache[token] = CachedHandoffPreview(
            preview = preview,
            sessionId = sessionId,
            createdAt = now,
            sourceWorkspace = sourceWs,
            targetWorkspace = targetWs,
            sourceHeadOid = sourceRepo.headOid ?: "",
            targetHeadOid = targetRepo.headOid,
            sourceFingerprint = sourceFp,
            taskBranch = taskBranch,
            affectedSessionIds = affectedSessions.map { it.id }
        )

        return preview
    }

    /**
     * 执行双向 Handoff 迁移。
     */
    suspend fun executeHandoff(
        sessionId: String,
        input: HandoffInput,
        permissions: ExecutionPermissions? = null
    ): WorkspaceOperation {
        val cached = previewCache[input.previewToken]
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.STALE_PREVIEW, "Preview token not found or already used")

        if (cached.sessionId != sessionId || input.targetKind != cached.targetWorkspace.kind ||
            Instant.now().isAfter(Instant.parse(cached.preview.expiresAt))
        ) {
            throw MederiWorkspaceException(WorkspaceErrorCode.STALE_PREVIEW, "Preview token expired")
        }

        // 重新核验源指纹：无外部变化
        val currentSourceFp = snapshotService.computeFingerprint(cached.sourceWorkspace.checkoutRoot)
        if (currentSourceFp != cached.sourceFingerprint) {
            throw MederiWorkspaceException(WorkspaceErrorCode.EXTERNAL_CHANGE, "Source changed externally since preview")
        }

        // 核验目标 clean
        if (cached.preview.conflicts.isNotEmpty()) {
            throw MederiWorkspaceException(WorkspaceErrorCode.TARGET_DIRTY, "Target checkout is dirty")
        }
        val targetStatusRes = gitDriver.runGit(cached.targetWorkspace.checkoutRoot, listOf("status", "--porcelain=v2", "-z", "--untracked-files=all"))
        // 与 preview 同一口径：Mederi 自身 D09 工件（.mederi/notebook.md 等）不算目标 dirty
        val targetEntries = gitDriver.parsePorcelainV2(targetStatusRes.stdoutBytes)
            .filterNot { WorkspaceSnapshotService.isD09ArtifactPath(it.path) }
        if (targetEntries.isNotEmpty()) {
            throw MederiWorkspaceException(WorkspaceErrorCode.TARGET_DIRTY, "Target workspace is dirty")
        }

        // 核验受影响会话确认
        if (cached.affectedSessionIds.isNotEmpty() && !input.stopAffectedSessions) {
            throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_BUSY, "Multiple sessions affected; stopAffectedSessions must be confirmed")
        }

        val operationId = "op_${UUID.randomUUID().toString().take(12)}"
        val logBuffer = environmentService.getLogBuffer(operationId)
        val idempotencyKey = "HANDOFF:${input.requestId}"
        val requestHash = "${cached.sourceWorkspace.id}:${cached.targetWorkspace.id}:${cached.taskBranch}"

        val gitCommonDir = cached.sourceWorkspace.gitCommonDirectory
            ?: gitDriver.getGitCommonDir(cached.sourceWorkspace.checkoutRoot)
            ?: cached.sourceWorkspace.checkoutRoot

        return runManagedOperation(
            operationId = operationId,
            gitCommonDir = gitCommonDir,
            checkoutRoots = listOf(cached.sourceWorkspace.checkoutRoot, cached.targetWorkspace.checkoutRoot),
        ) { permit ->
            val latestSession = sessionStore.get(sessionId)
            val latestSource = workspaceStore.getWorkspace(cached.sourceWorkspace.id)
            val latestTarget = workspaceStore.getWorkspace(cached.targetWorkspace.id)
            if (latestSession?.workspaceId != cached.sourceWorkspace.id ||
                latestSource?.generation != cached.sourceWorkspace.generation ||
                latestTarget?.generation != cached.targetWorkspace.generation ||
                latestSource.state != WorkspaceState.READY || latestTarget.state != WorkspaceState.READY
            ) {
                throw MederiWorkspaceException(WorkspaceErrorCode.STALE_PREVIEW, "Workspace binding changed since preview")
            }
            if (snapshotService.computeFingerprint(cached.sourceWorkspace.checkoutRoot) != cached.sourceFingerprint ||
                snapshotService.computeFingerprint(cached.targetWorkspace.checkoutRoot) != cached.preview.fingerprints["target"]
            ) {
                throw MederiWorkspaceException(WorkspaceErrorCode.EXTERNAL_CHANGE, "A checkout changed while waiting for handoff")
            }
            // 阶段 1: QUIESCE
            logBuffer.append("[handoff] Quiescing execution leases and entering TRANSFERRING state...\n")
            var srcWs = cached.sourceWorkspace.copy(state = WorkspaceState.TRANSFERRING)
            var tgtWs = cached.targetWorkspace.copy(state = WorkspaceState.TRANSFERRING)
            workspaceStore.saveWorkspace(srcWs)
            workspaceStore.saveWorkspace(tgtWs)

            // RF-04: 在第一个 git 副作用（快照/switch）之前持久化 RUNNING 操作日志与完整 HandoffPlan。
            // snapshotIds 在快照阶段完成后回填；崩溃恢复只依赖持久日志 + 文件/Git 状态。
            val initialPlan = WorkspaceOperationPlan.HandoffPlan(
                requestHash = requestHash,
                sourceWorkspaceId = srcWs.id,
                targetWorkspaceId = tgtWs.id,
                targetKind = tgtWs.kind,
                taskBranchName = cached.taskBranch,
                expectedSourceHeadOid = cached.sourceHeadOid,
                expectedTargetHeadOid = cached.targetHeadOid,
                stopAffectedSessions = input.stopAffectedSessions,
                previewToken = input.previewToken
            )
            var journalOp = WorkspaceOperation(
                id = operationId,
                projectId = tgtWs.projectId,
                sessionId = sessionId,
                workspaceId = tgtWs.id,
                kind = WorkspaceOperationKind.HANDOFF,
                state = WorkspaceOperationState.RUNNING,
                phase = WorkspacePhase.SNAPSHOT,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                sourceWorkspaceId = srcWs.id,
                targetWorkspaceId = tgtWs.id,
                operationPlan = initialPlan,
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(journalOp)

            // 阶段 2: SNAPSHOT
            logBuffer.append("[handoff] Capturing source transfer snapshot and preimages...\n")
            val srcTransfer = snapshotService.captureSnapshot(
                checkoutRoot = srcWs.checkoutRoot,
                workspaceId = srcWs.id,
                reason = "handoff_transfer",
                captureType = SnapshotCaptureType.TRANSFER
            )
            val srcPreimage = snapshotService.captureSnapshot(
                checkoutRoot = srcWs.checkoutRoot,
                workspaceId = srcWs.id,
                reason = "handoff_source_preimage",
                captureType = SnapshotCaptureType.PREIMAGE
            )
            val tgtPreimage = snapshotService.captureSnapshot(
                checkoutRoot = tgtWs.checkoutRoot,
                workspaceId = tgtWs.id,
                reason = "handoff_target_preimage",
                captureType = SnapshotCaptureType.PREIMAGE
            )

            // 快照已持久化：回填 snapshotIds 与 SNAPSHOT 完成记录，进入 TRANSFER 阶段
            journalOp = journalOp.copy(
                operationPlan = initialPlan.copy(
                    sourceTransferSnapshotId = srcTransfer.snapshotId,
                    sourcePreimageSnapshotId = srcPreimage.snapshotId,
                    targetPreimageSnapshotId = tgtPreimage.snapshotId,
                    acceptedTargetSnapshotId = srcTransfer.snapshotId
                ),
                sourceSnapshotId = srcTransfer.snapshotId,
                targetSnapshotId = srcTransfer.snapshotId,
                phase = WorkspacePhase.SWITCH_TARGET,
                phaseRecords = journalOp.phaseRecords + WorkspacePhaseRecord(
                    phase = WorkspacePhase.SNAPSHOT,
                    startedAt = journalOp.createdAt,
                    finishedAt = Instant.now().toString(),
                    expectedHeadOid = cached.sourceHeadOid
                ),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(journalOp)

            // 阶段 3: DETACH_SOURCE (如果源正在检出 taskBranch，先 detach 源以免分支在目标被占用)
            // R10: 阶段开始即记录起始时间，git 副作用完成后立即持久该阶段后像，崩溃恢复据此核对。
            val detachStartedAt = Instant.now().toString()
            val curSrcBranch = gitDriver.discover(srcWs.checkoutRoot).branch
            if (curSrcBranch == cached.taskBranch) {
                logBuffer.append("[handoff] Detaching source from branch '${cached.taskBranch}'...\n")
                gitDriver.runGit(srcWs.checkoutRoot, listOf("switch", "--detach", cached.sourceHeadOid))
            }
            journalOp = journalOp.copy(
                phase = WorkspacePhase.SWITCH_TARGET,
                phaseRecords = journalOp.phaseRecords + WorkspacePhaseRecord(
                    phase = WorkspacePhase.DETACH_SOURCE,
                    startedAt = detachStartedAt,
                    finishedAt = Instant.now().toString(),
                    expectedHeadOid = cached.sourceHeadOid
                ),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(journalOp)

            // 阶段 4: SWITCH_TARGET
            val switchStartedAt = Instant.now().toString()
            logBuffer.append("[handoff] Switching target to branch '${cached.taskBranch}'...\n")
            val checkBranch = gitDriver.runGit(tgtWs.checkoutRoot, listOf("show-ref", "--verify", "--quiet", "refs/heads/${cached.taskBranch}"))
            if (checkBranch.exitCode != 0) {
                val createRes = gitDriver.runGit(tgtWs.checkoutRoot, listOf("checkout", "-b", cached.taskBranch, cached.sourceHeadOid))
                if (createRes.exitCode != 0) {
                    throw MederiWorkspaceException(WorkspaceErrorCode.BRANCH_IN_USE, "Failed to create and switch branch ${cached.taskBranch}: ${createRes.stderr}")
                }
            } else {
                val switchRes = gitDriver.runGit(tgtWs.checkoutRoot, listOf("switch", cached.taskBranch))
                if (switchRes.exitCode != 0) {
                    throw MederiWorkspaceException(WorkspaceErrorCode.BRANCH_IN_USE, "Failed to switch to branch ${cached.taskBranch}: ${switchRes.stderr}")
                }
            }
            // R10: 记录目标 ref 变更后像（任务分支指向 sourceHeadOid）
            journalOp = journalOp.copy(
                phase = WorkspacePhase.MATERIALIZE_TARGET,
                phaseRecords = journalOp.phaseRecords + WorkspacePhaseRecord(
                    phase = WorkspacePhase.SWITCH_TARGET,
                    startedAt = switchStartedAt,
                    finishedAt = Instant.now().toString(),
                    expectedHeadOid = cached.sourceHeadOid,
                    expectedHeadRef = "refs/heads/${cached.taskBranch}"
                ),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(journalOp)

            // 阶段 5: MATERIALIZE_TARGET
            // R10: 受影响路径 = 转移清单 + 包含文件（相对路径），后像取目标实际文件状态（type/hash/symlink）
            val materializeStartedAt = Instant.now().toString()
            logBuffer.append("[handoff] Materializing transfer snapshot onto target...\n")
            materializeCarriedSnapshot(srcTransfer.snapshotId, File(tgtWs.checkoutRoot))

            // 复制包含文件
            val includeFile = File(srcWs.checkoutRoot, ".worktreeinclude")
            val includeRules = if (includeFile.exists()) includeFile.readText() else null
            val candidates = includeService.computeCandidates(srcWs.checkoutRoot, includeRules, null)
            if (candidates.isNotEmpty()) {
                includeService.copyIncludeFiles(srcWs.checkoutRoot, tgtWs.checkoutRoot, candidates)
            }
            val materializedPaths = (srcTransfer.entries.map { it.repoRelativePath } + candidates).distinct()
            journalOp = journalOp.copy(
                phase = WorkspacePhase.VERIFY_TARGET,
                phaseRecords = journalOp.phaseRecords + WorkspacePhaseRecord(
                    phase = WorkspacePhase.MATERIALIZE_TARGET,
                    startedAt = materializeStartedAt,
                    finishedAt = Instant.now().toString(),
                    affectedPaths = capturePhasePathRecords(File(tgtWs.checkoutRoot), materializedPaths),
                    expectedHeadOid = cached.sourceHeadOid
                ),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(journalOp)

            // 阶段 6: VERIFY_TARGET
            val verifyStartedAt = Instant.now().toString()
            logBuffer.append("[handoff] Verifying target materialized content...\n")
            val verified = verifyTargetMatchesTransfer(File(tgtWs.checkoutRoot), srcTransfer)
            if (!verified) {
                logBuffer.append("[handoff] Target verification failed; stopping without cleaning source!\n")
                throw MederiWorkspaceException(WorkspaceErrorCode.SNAPSHOT_CORRUPT, "Target materialized content does not match transfer snapshot")
            }
            journalOp = journalOp.copy(
                phase = WorkspacePhase.VERIFY_TARGET,
                phaseRecords = journalOp.phaseRecords + WorkspacePhaseRecord(
                    phase = WorkspacePhase.VERIFY_TARGET,
                    startedAt = verifyStartedAt,
                    finishedAt = Instant.now().toString(),
                    expectedHeadOid = cached.sourceHeadOid
                ),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(journalOp)

            // 阶段 7: POST_SETUP (若目标有环境脚本，执行并在执行后捕获 acceptedTargetSnapshot)
            var acceptedTargetSnapshotId = srcTransfer.snapshotId
            val envId = tgtWs.pendingSelection?.selection?.environmentId
                ?: srcWs.pendingSelection?.selection?.environmentId
            if (envId != null) {
                val srcEnvDir = File(srcWs.checkoutRoot, ".mederi/environments")
                val tgtEnvDir = File(tgtWs.checkoutRoot, ".mederi/environments")
                if (srcEnvDir.exists() && !tgtEnvDir.exists()) {
                    srcEnvDir.copyRecursively(tgtEnvDir, overwrite = true)
                }
                val env = environmentService.getEnvironment(File(tgtWs.checkoutRoot), envId)
                if (env != null && env.setup != null) {
                    val setupStartedAt = Instant.now().toString()
                    logBuffer.append("[handoff] Executing post-transfer target setup for '$envId'...\n")
                    val envHash = environmentService.computeEnvironmentHash(env)
                    // T08: 生产调用方不传 permissions 时用 buildWorkspacePermissions 兜底构造真实权限，
                    // 绝不允许 null 透传给 executeSetup 造成 setup 脚本裸跑（deniedPaths/沙箱/进程组旁路）。
                    val setupPermissions = permissions ?: buildWorkspacePermissions(tgtWs, sessionId)
                    val setupResult = environmentService.executeSetup(
                        workspace = tgtWs,
                        environment = env,
                        expectedHash = envHash,
                        requestId = "setup_handoff_${tgtWs.id}",
                        permissions = setupPermissions,
                        permit = permit
                    )
                    // T11: 仅 SUCCEEDED 才继续 acceptedTargetSnapshot/clean_source；否则置 FAILED 并中止，
                    // 不清理源、不提交 binding，避免目标 setup 失败却把 handoff 标记成功。
                    if (setupResult.state != WorkspaceOperationState.SUCCEEDED) {
                        journalOp = journalOp.copy(
                            state = WorkspaceOperationState.FAILED,
                            error = WorkspaceError(
                                WorkspaceErrorCode.SETUP_FAILED,
                                "Post-transfer setup did not succeed: ${setupResult.state}"
                            ),
                            updatedAt = Instant.now().toString()
                        )
                        workspaceStore.saveOperation(journalOp)
                        throw MederiWorkspaceException(
                            WorkspaceErrorCode.SETUP_FAILED,
                            "Post-transfer setup did not succeed: ${setupResult.state}"
                        )
                    }
                    val acceptedManifest = snapshotService.captureSnapshot(
                        checkoutRoot = tgtWs.checkoutRoot,
                        workspaceId = tgtWs.id,
                        reason = "accepted_target_after_setup",
                        captureType = SnapshotCaptureType.TRANSFER
                    )
                    acceptedTargetSnapshotId = acceptedManifest.snapshotId
                    // R10: setup 完成后记录目标侧 SETUP 阶段后像（accepted 清单路径）
                    journalOp = journalOp.copy(
                        phase = WorkspacePhase.CLEAN_SOURCE,
                        phaseRecords = journalOp.phaseRecords + WorkspacePhaseRecord(
                            phase = WorkspacePhase.SETUP,
                            startedAt = setupStartedAt,
                            finishedAt = Instant.now().toString(),
                            affectedPaths = capturePhasePathRecords(File(tgtWs.checkoutRoot), acceptedManifest.entries.map { it.repoRelativePath }),
                            expectedHeadOid = cached.sourceHeadOid
                        ),
                        updatedAt = Instant.now().toString()
                    )
                    workspaceStore.saveOperation(journalOp)
                }
            }

            // 阶段 8: CLEAN_SOURCE (只有在目标核验通过后，才清理源)
            // R10: 受影响路径 = 转移清单路径，后像取清源后源的实际文件状态（tracked 恢复基线、untracked 已删）
            val cleanStartedAt = Instant.now().toString()
            val cleanedPaths = srcTransfer.entries.map { it.repoRelativePath }.distinct()
            logBuffer.append("[handoff] Cleaning transferred changes from source...\n")
            currentCoroutineContext().ensureActive()
            cleanTransferredFromSource(File(srcWs.checkoutRoot), cached.sourceHeadOid, srcTransfer)

            // 清源完成：持久 CLEAN_SOURCE 完成记录并进入 COMMIT_BINDING 阶段
            journalOp = journalOp.copy(
                phase = WorkspacePhase.COMMIT_BINDING,
                operationPlan = initialPlan.copy(
                    sourceTransferSnapshotId = srcTransfer.snapshotId,
                    sourcePreimageSnapshotId = srcPreimage.snapshotId,
                    targetPreimageSnapshotId = tgtPreimage.snapshotId,
                    acceptedTargetSnapshotId = acceptedTargetSnapshotId
                ),
                phaseRecords = journalOp.phaseRecords + WorkspacePhaseRecord(
                    phase = WorkspacePhase.CLEAN_SOURCE,
                    startedAt = cleanStartedAt,
                    finishedAt = Instant.now().toString(),
                    affectedPaths = capturePhasePathRecords(File(srcWs.checkoutRoot), cleanedPaths),
                    expectedHeadOid = cached.sourceHeadOid
                ),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(journalOp)

            // 阶段 9: COMMIT_BINDING
            logBuffer.append("[handoff] Committing session binding to target workspace...\n")
            val newGen = tgtWs.generation + 1
            val updatedTgtWs = tgtWs.copy(
                state = WorkspaceState.READY,
                generation = newGen,
                taskBranchName = cached.taskBranch,
                pendingSelection = tgtWs.pendingSelection ?: srcWs.pendingSelection,
                lastUsedAt = Instant.now().toString()
            )
            val updatedSrcWs = srcWs.copy(
                state = WorkspaceState.READY,
                lastUsedAt = Instant.now().toString()
            )

            val associatedWorktreeId = if (tgtWs.kind == WorkspaceKind.MANAGED_WORKTREE) tgtWs.id else srcWs.id

            val plan = WorkspaceOperationPlan.HandoffPlan(
                requestHash = requestHash,
                sourceWorkspaceId = srcWs.id,
                targetWorkspaceId = tgtWs.id,
                targetKind = tgtWs.kind,
                taskBranchName = cached.taskBranch,
                expectedSourceHeadOid = cached.sourceHeadOid,
                expectedTargetHeadOid = cached.targetHeadOid,
                sourceTransferSnapshotId = srcTransfer.snapshotId,
                sourcePreimageSnapshotId = srcPreimage.snapshotId,
                targetPreimageSnapshotId = tgtPreimage.snapshotId,
                acceptedTargetSnapshotId = acceptedTargetSnapshotId,
                stopAffectedSessions = input.stopAffectedSessions,
                previewToken = input.previewToken
            )

            val op = WorkspaceOperation(
                id = operationId,
                projectId = tgtWs.projectId,
                sessionId = sessionId,
                workspaceId = tgtWs.id,
                kind = WorkspaceOperationKind.HANDOFF,
                state = WorkspaceOperationState.SUCCEEDED,
                phase = WorkspacePhase.COMPLETE,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                sourceWorkspaceId = srcWs.id,
                targetWorkspaceId = tgtWs.id,
                sourceSnapshotId = srcTransfer.snapshotId,
                targetSnapshotId = acceptedTargetSnapshotId,
                phaseRecords = journalOp.phaseRecords,
                operationPlan = plan,
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString()
            )

            // R08: Handoff 成功提交必须原子化。源工作区 READY 先落盘（清源已完成，无绑定副作用），
            // 随后用 commitBinding CAS 一次性提交：会话绑定 + 目标工作区 READY + SUCCEEDED 操作。
            // 任一步失败时操作保持 RUNNING（而非 SUCCEEDED），恢复扫描仍可重试/回滚；
            // 绝不出现「源已清理、操作 SUCCEEDED、但会话仍指向源」的中间态。
            workspaceStore.saveWorkspace(updatedSrcWs)
            if (sessionId.isNotBlank()) {
                workspaceStore.commitBinding(
                    sessionId = sessionId,
                    expectedWorkspaceId = srcWs.id,
                    expectedGeneration = srcWs.generation,
                    newWorkspace = updatedTgtWs,
                    associatedWorktreeId = associatedWorktreeId,
                    operation = op
                )
            } else {
                workspaceStore.saveWorkspace(updatedTgtWs)
                workspaceStore.saveOperation(op)
            }

            previewCache.remove(input.previewToken)
            logBuffer.append("[handoff] Handoff completed successfully.\n")
            op
        }
    }

    private suspend fun verifyTargetMatchesTransfer(
        targetRoot: File,
        manifest: SnapshotManifest
    ): Boolean {
        for (entry in manifest.entries) {
            val file = File(targetRoot, entry.repoRelativePath)
            if (!entry.exists) {
                if (file.exists()) return false
            } else {
                if (!file.exists()) return false
                if (entry.kind == SnapshotEntryKind.FILE && entry.sha256 != null) {
                    val curSha = snapshotService.fileSha256(file)
                    if (curSha != entry.sha256) return false
                }
            }
        }
        return true
    }

    internal suspend fun cleanTransferredFromSource(
        sourceRoot: File,
        sourceHeadOid: String,
        transferManifest: SnapshotManifest
    ) {
        val rootPath = sourceRoot.absolutePath
        val trackedPaths = mutableListOf<String>()
        val untrackedEntries = mutableListOf<SnapshotEntry>()

        for (entry in transferManifest.entries) {
            val rel = entry.repoRelativePath
            if (WorkspaceSnapshotService.isD09ArtifactPath(rel)) continue
            if (entry.entryClass == SnapshotEntryClass.TRACKED) {
                trackedPaths.add(rel)
            } else if (entry.entryClass == SnapshotEntryClass.UNTRACKED) {
                untrackedEntries.add(entry)
            }
        }

        // R12: 清源前全量核对工作树、index 与 ref 基线。仅凭工作树 FILE hash 不够——
        // 用户可在快照后 `git add` 新内容（index 变）或将工作树文本恢复为快照值（hash 仍过）；
        // git restore --staged --worktree 会清掉未进入快照的暂存内容。任何差异 → RECOVERY_REQUIRED，
        // 不执行 restore，未知改动零覆盖。
        val headRes = gitDriver.runGit(rootPath, listOf("rev-parse", "--verify", "HEAD^{commit}"))
        if (headRes.exitCode != 0 || headRes.stdoutString.trim() != transferManifest.headOid) {
            throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED,
                "Clean-source aborted: HEAD moved since snapshot (expected ${transferManifest.headOid}, current ${headRes.stdoutString.trim()})")
        }

        // R12: 暂存区基线核对——用 stage 条目内容指纹（indexStageSha256）而不是 index 文件原字节：
        // git 在 detach/checkout 时会重写 index 文件的 stat 缓存（字节变化但暂存内容不变），
        // 而用户快照后 `git add` 新内容会改变 stage 条目（oid/mode），必须拦截。
        // legacy 清单无 stage 指纹时回退到原字节比较（尽力严格）。
        val stageRes = gitDriver.runGit(rootPath, listOf("ls-files", "--stage", "-z"))
        if (stageRes.exitCode == 0) {
            val expectedStageSha = transferManifest.indexStageSha256
            if (expectedStageSha != null) {
                val curStageSha = sha256Hex(stageRes.stdoutBytes)
                if (curStageSha != expectedStageSha) {
                    throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED,
                        "Clean-source aborted: staged index changed since snapshot (postimage=$expectedStageSha, current=$curStageSha)")
                }
            } else {
                val indexPathRes = gitDriver.runGit(rootPath, listOf("rev-parse", "--git-path", "index"))
                if (indexPathRes.exitCode == 0) {
                    val idxPath = indexPathRes.stdoutString.trim()
                    val idxFile = File(idxPath).let { if (it.isAbsolute) it else File(sourceRoot, it.path) }
                    val curIndexSha = if (idxFile.exists()) snapshotService.fileSha256(idxFile) else null
                    if (curIndexSha != transferManifest.indexSha256) {
                        throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED,
                            "Clean-source aborted: index changed since snapshot (postimage=${transferManifest.indexSha256}, current=$curIndexSha)")
                    }
                }
            }
        }

        // RF-05/R12：逐 tracked 路径核对存在性/类型/hash/mode/linkTarget 与捕获后像一致。任何外部变更
        // （用户/IDE 编辑、chmod 执行位、改符号链接目标）→ RECOVERY_REQUIRED，保留双方快照，
        // 不把新内容丢弃也不假成功。
        val trackedByPath = transferManifest.entries.filter { it.entryClass == SnapshotEntryClass.TRACKED }.associateBy { it.repoRelativePath }
        for (p in trackedPaths) {
            val entry = trackedByPath[p] ?: continue
            val file = File(sourceRoot, p)
            val nioPath = file.toPath()
            val isSym = Files.isSymbolicLink(nioPath)
            val curExists = isSym || Files.exists(nioPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            if (curExists != entry.exists) {
                throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED,
                    "Clean-source aborted: tracked path '$p' existence changed since snapshot (postimage=${entry.exists}, current=$curExists)")
            }
            if (!curExists) continue
            when (entry.kind) {
                SnapshotEntryKind.FILE -> {
                    if (isSym || !file.isFile) {
                        throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED,
                            "Clean-source aborted: tracked path '$p' type changed since snapshot (expected FILE)")
                    }
                    if (entry.sha256 != null) {
                        val curSha = snapshotService.fileSha256(file)
                        if (curSha != entry.sha256) {
                            throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED,
                                "Clean-source aborted: tracked path '$p' content changed since snapshot (postimage mismatch)")
                        }
                    }
                    // 执行位（unixMode 低 9 位 0o111=73）与快照一致
                    val expectedExec = entry.unixMode?.let { (it and 73) != 0 } ?: false
                    if (file.canExecute() != expectedExec) {
                        throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED,
                            "Clean-source aborted: tracked path '$p' executable bit changed since snapshot (postimage=${entry.unixMode}, current=${file.canExecute()})")
                    }
                }
                SnapshotEntryKind.SYMLINK -> {
                    if (!isSym) {
                        throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED,
                            "Clean-source aborted: tracked path '$p' type changed since snapshot (expected SYMLINK)")
                    }
                    if (entry.linkTarget != null) {
                        val curTarget = Files.readSymbolicLink(nioPath).toString()
                        if (curTarget != entry.linkTarget) {
                            throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED,
                                "Clean-source aborted: tracked path '$p' link target changed since snapshot (postimage=${entry.linkTarget}, current=$curTarget)")
                        }
                    }
                }
                SnapshotEntryKind.DIRECTORY -> { /* 目录条目无内容比对；存在性已核 */ }
            }
        }

        // 1. 恢复 tracked paths 到 sourceHeadOid
        if (trackedPaths.isNotEmpty()) {
            for (p in trackedPaths) {
                val res = gitDriver.runGit(rootPath, listOf("restore", "--source=$sourceHeadOid", "--staged", "--worktree", "--", p))
                if (res.exitCode != 0) {
                    throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED,
                        "Clean-source aborted: git restore failed for '$p' (exit ${res.exitCode}): ${res.stderr}")
                }
            }
        }

        // 2. 删除已转移且 hash 仍然匹配的 ordinary untracked 文件
        for (entry in untrackedEntries) {
            val file = File(sourceRoot, entry.repoRelativePath)
            if (file.exists() && file.isFile && entry.sha256 != null) {
                val currentSha = snapshotService.fileSha256(file)
                if (currentSha == entry.sha256) {
                    file.delete()
                    var parent = file.parentFile
                    while (parent != null && parent != sourceRoot && (parent.listFiles()?.isEmpty() == true)) {
                        parent.delete()
                        parent = parent.parentFile
                    }
                }
            }
        }
    }

    // R10: 目标侧（SWITCH/MATERIALIZE/VERIFY/SETUP）与源侧（DETACH/CLEAN）各自由哪些阶段改动。
    // 崩溃回滚按根过滤 journal 记录，避免把另一侧的后像误判为外部修改。
    private val TARGET_MUTATING_PHASES = setOf(
        WorkspacePhase.SWITCH_TARGET,
        WorkspacePhase.MATERIALIZE_TARGET,
        WorkspacePhase.VERIFY_TARGET,
        WorkspacePhase.SETUP
    )
    private val SOURCE_MUTATING_PHASES = setOf(
        WorkspacePhase.DETACH_SOURCE,
        WorkspacePhase.CLEAN_SOURCE
    )

    /**
     * R10: 为阶段记录构造后像——按相对路径读取当前文件系统状态（exists/type/hash/symlink），
     * 与 rollbackCheckout 的核对口径一致（file=sha256、symlink=link target 文本 sha256）。
     */
    private suspend fun capturePhasePathRecords(root: File, paths: Collection<String>): List<PhasePathRecord> {
        return paths.map { p ->
            val file = File(root, p)
            val nioPath = file.toPath()
            val isSym = Files.isSymbolicLink(nioPath)
            val exists = isSym || Files.exists(nioPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            val type = when {
                !exists -> null
                isSym -> "symlink"
                Files.isDirectory(nioPath, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> "directory"
                else -> "file"
            }
            val hash = when (type) {
                "file" -> snapshotService.fileSha256(file)
                "symlink" -> sha256HexUtf8(Files.readSymbolicLink(nioPath).toString())
                else -> null
            }
            PhasePathRecord(
                repoRelativePath = p,
                preExists = false,
                preType = null,
                preHash = null,
                postExists = exists,
                postType = type,
                postHash = hash
            )
        }
    }

    private fun sha256HexUtf8(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val recoveryPreviewCache = java.util.concurrent.ConcurrentHashMap<String, CachedRecoveryPreview>()
    private val executingOperations = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private suspend fun <T> runManagedOperation(
        operationId: String,
        gitCommonDir: String,
        checkoutRoots: List<String>,
        block: suspend (WorkspaceLeaseManager.ManagementPermit) -> T
    ): T = coroutineScope {
        val job = coroutineContext[Job]!!
        check(executingOperations.putIfAbsent(operationId, job) == null)
        try {
            leaseManager.withManagementIntent(gitCommonDir, checkoutRoots, permitOperationId = operationId, block = block)
        } finally {
            executingOperations.remove(operationId, job)
        }
    }

    private data class CachedRecoveryPreview(
        val preview: RecoveryPreview,
        val createdAt: Instant,
        val operation: WorkspaceOperation
    )

    /**
     * 崩溃恢复：扫描并恢复所有未完成的操作。
     */
    suspend fun scanAndRecoverPendingOperations(projectId: String? = null): List<WorkspaceOperation> {
        val projects = if (projectId != null) listOf(projectId) else sessionStore.list().map { it.projectId }.distinct()
        val allPendingOps = mutableListOf<WorkspaceOperation>()

        for (pid in projects) {
            val ops = workspaceStore.listOperations(pid)
            for (op in ops) {
                if (!executingOperations.containsKey(op.id) && op.state in listOf(
                        WorkspaceOperationState.RUNNING,
                        WorkspaceOperationState.CANCELLING,
                        WorkspaceOperationState.RECOVERY_REQUIRED
                    )
                ) {
                    val recovered = recoverSingleOperation(op)
                    allPendingOps.add(recovered)
                }
            }
        }
        return allPendingOps
    }

    private suspend fun recoverSingleOperation(op: WorkspaceOperation): WorkspaceOperation {
        val now = Instant.now().toString()
        val newVersion = op.version + 1

        when (op.kind) {
            WorkspaceOperationKind.HANDOFF -> {
                val plan = op.operationPlan as? WorkspaceOperationPlan.HandoffPlan
                    ?: return op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = newVersion, updatedAt = now)

                val srcWs = workspaceStore.getWorkspace(op.sourceWorkspaceId ?: "")
                val tgtWs = workspaceStore.getWorkspace(op.targetWorkspaceId ?: "")

                if (srcWs == null || tgtWs == null) {
                    val updated = op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = newVersion, updatedAt = now)
                    workspaceStore.saveOperation(updated)
                    return updated
                }

                // 检查 target 是否与 acceptedTargetSnapshot 完全匹配，且 source 是否已 clean
                val tgtFile = File(tgtWs.checkoutRoot)
                val srcFile = File(srcWs.checkoutRoot)

                val targetMatchesAccepted = plan.acceptedTargetSnapshotId?.let { snapId ->
                    val manifest = try { snapshotService.loadManifest(snapId) } catch (e: Exception) { null }
                    manifest != null && verifyTargetMatchesTransfer(tgtFile, manifest)
                } ?: false

                val sourceClean = gitDriver.runGit(srcFile.absolutePath, listOf("status", "--porcelain=v2", "-z", "--untracked-files=all"))
                    .let { it.exitCode == 0 && gitDriver.parsePorcelainV2(it.stdoutBytes).none { entry -> !WorkspaceSnapshotService.isD09ArtifactPath(entry.path) } }

                // 第四轮复审 P1 修复: Handoff 只有在完整阶段证据齐备时才能宣告成功提交绑定
                // 仅 target 匹配与 source clean 不足（例如新副本与源同 HEAD 时若阶段还未进入 CLEAN_SOURCE / COMMIT_BINDING 不得宣告成功）
                // 完整阶段检查（第四轮复审 P1）: 只有阶段达到 CLEAN_SOURCE / COMMIT_BINDING 且目标与源后像完备才允许宣告成功，
                // 杜绝在 SNAPSHOT / SWITCH / MATERIALIZE 阶段（如新副本与源同 HEAD）提前判定成功
                val hasFullPhaseEvidence = op.phase == WorkspacePhase.COMMIT_BINDING &&
                    op.phaseRecords.any { it.phase == WorkspacePhase.CLEAN_SOURCE && it.finishedAt != null }

                if (targetMatchesAccepted && sourceClean && hasFullPhaseEvidence) {
                    // 阶段后像完备：原子完成 COMMIT_BINDING（R08: commitBinding CAS 一次提交
                    // 会话绑定 + 目标 READY + SUCCEEDED，杜绝半写入）
                    val updatedTgtWs = tgtWs.copy(state = WorkspaceState.READY, generation = tgtWs.generation + 1, lastUsedAt = now)
                    val updatedSrcWs = srcWs.copy(state = WorkspaceState.READY, lastUsedAt = now)
                    workspaceStore.saveWorkspace(updatedSrcWs)

                    val associatedWorktreeId = if (tgtWs.kind == WorkspaceKind.MANAGED_WORKTREE) tgtWs.id else srcWs.id
                    val succeededOp = op.copy(state = WorkspaceOperationState.SUCCEEDED, phase = WorkspacePhase.COMPLETE, version = newVersion, updatedAt = now)
                    if (op.sessionId != null) {
                        workspaceStore.commitBinding(
                            sessionId = op.sessionId,
                            expectedWorkspaceId = srcWs.id,
                            expectedGeneration = srcWs.generation,
                            newWorkspace = updatedTgtWs,
                            associatedWorktreeId = associatedWorktreeId,
                            operation = succeededOp
                        )
                    } else {
                        workspaceStore.saveWorkspace(updatedTgtWs)
                        workspaceStore.saveOperation(succeededOp)
                    }
                    return succeededOp
                }

                // 检查外部未知修改：如果 target 或 source 发生未知改动，必须转 RECOVERY_REQUIRED
                val srcDiff = gitDriver.runGit(srcFile.absolutePath, listOf("status", "--porcelain=v2", "-z", "--untracked-files=all"))
                val tgtDiff = gitDriver.runGit(tgtFile.absolutePath, listOf("status", "--porcelain=v2", "-z", "--untracked-files=all"))

                // 若在 CLEAN_SOURCE 之前中断，且无外部篡改：可回滚到 PREIMAGE
                if (plan.sourcePreimageSnapshotId != null && plan.targetPreimageSnapshotId != null) {
                    // T06: journal 完整性检查——若目标侧 SWITCH_TARGET 已完成但 MATERIALIZE_TARGET 未持久（finishedAt 缺失），
                    // 物化可能已部分发生但后像未记录（affectedPaths 未知），不可安全回滚，转 RECOVERY_REQUIRED。
                    val switchDone = op.phaseRecords.any { it.phase == WorkspacePhase.SWITCH_TARGET && it.finishedAt != null }
                    val materializeDone = op.phaseRecords.any { it.phase == WorkspacePhase.MATERIALIZE_TARGET && it.finishedAt != null }
                    if (switchDone && !materializeDone) {
                        val reqOp = op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = newVersion, updatedAt = now)
                        workspaceStore.saveOperation(reqOp)
                        workspaceStore.saveWorkspace(srcWs.copy(state = WorkspaceState.RECOVERY_REQUIRED, lastUsedAt = now))
                        workspaceStore.saveWorkspace(tgtWs.copy(state = WorkspaceState.RECOVERY_REQUIRED, lastUsedAt = now))
                        return reqOp
                    }
                    try {
                        // R10: 逐根选择真正改动该 checkout 的阶段记录——目标侧只核验
                        // SWITCH/MATERIALIZE/VERIFY/SETUP 后像，源侧只核验 DETACH/CLEAN 后像，
                        // 避免把另一侧路径的后像误判为外部修改，也让回滚真实恢复复制的工作树文件。
                        val targetRecords = op.phaseRecords.filter { it.phase in TARGET_MUTATING_PHASES }
                        val sourceRecords = op.phaseRecords.filter { it.phase in SOURCE_MUTATING_PHASES }
                        snapshotService.rollbackCheckout(tgtWs.checkoutRoot, plan.targetPreimageSnapshotId, targetRecords)
                        snapshotService.rollbackCheckout(srcWs.checkoutRoot, plan.sourcePreimageSnapshotId, sourceRecords)

                        val finalState = if (op.state == WorkspaceOperationState.CANCELLING || op.cancelRequested) {
                            WorkspaceOperationState.CANCELLED
                        } else {
                            WorkspaceOperationState.FAILED
                        }
                        val rollbackOp = op.copy(state = finalState, phase = WorkspacePhase.COMPLETE, version = newVersion, updatedAt = now)
                        workspaceStore.saveOperation(rollbackOp)
                        workspaceStore.saveWorkspace(srcWs.copy(state = WorkspaceState.READY, lastUsedAt = now))
                        workspaceStore.saveWorkspace(tgtWs.copy(state = WorkspaceState.READY, lastUsedAt = now))
                        return rollbackOp
                    } catch (e: Exception) {
                        // 回滚失败，转入 RECOVERY_REQUIRED
                    }
                }

                val reqOp = op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = newVersion, updatedAt = now)
                workspaceStore.saveOperation(reqOp)
                workspaceStore.saveWorkspace(srcWs.copy(state = WorkspaceState.RECOVERY_REQUIRED, lastUsedAt = now))
                workspaceStore.saveWorkspace(tgtWs.copy(state = WorkspaceState.RECOVERY_REQUIRED, lastUsedAt = now))
                return reqOp
            }
            WorkspaceOperationKind.CREATE -> {
                val ws = workspaceStore.getWorkspace(op.workspaceId)
                if (ws != null && ws.state == WorkspaceState.PREPARING) {
                    val updatedWs = ws.copy(state = WorkspaceState.REQUESTED, lastUsedAt = now)
                    workspaceStore.saveWorkspace(updatedWs)
                }
                val failedOp = op.copy(state = WorkspaceOperationState.FAILED, phase = WorkspacePhase.COMPLETE, version = newVersion, updatedAt = now)
                workspaceStore.saveOperation(failedOp)
                return failedOp
            }
            WorkspaceOperationKind.ARCHIVE -> {
                val plan = op.operationPlan as? WorkspaceOperationPlan.ArchivePlan
                    ?: return op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = newVersion, updatedAt = now)

                val ws = workspaceStore.getWorkspace(plan.workspaceId)
                    ?: return op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = newVersion, updatedAt = now)

                val checkoutExists = File(plan.checkoutPath).exists()
                val hasSnapshot = plan.snapshotId != null

                // 数据库已提交 ARCHIVED、仅差 SUCCEEDED 操作记录：幂等补全，不再重复递增 generation
                if (ws.state == WorkspaceState.ARCHIVED && hasSnapshot) {
                    val succeededOp = op.copy(state = WorkspaceOperationState.SUCCEEDED, phase = WorkspacePhase.COMPLETE, version = newVersion, updatedAt = now)
                    workspaceStore.saveOperation(succeededOp)
                    return succeededOp
                }

                if (!checkoutExists && hasSnapshot) {
                    // worktree 已移除但数据库尚未提交 ARCHIVED —— 完成归档
                    val updatedWs = ws.copy(
                        state = WorkspaceState.ARCHIVED,
                        lastSnapshotId = plan.snapshotId,
                        generation = ws.generation + 1,
                        lastUsedAt = now
                    )
                    workspaceStore.saveWorkspace(updatedWs)
                    if (ws.associatedSessionId != null) {
                        sessionStore.setArchived(ws.associatedSessionId, now)
                    }
                    val succeededOp = op.copy(state = WorkspaceOperationState.SUCCEEDED, phase = WorkspacePhase.COMPLETE, version = newVersion, updatedAt = now)
                    workspaceStore.saveOperation(succeededOp)
                    return succeededOp
                }

                if (checkoutExists && hasSnapshot) {
                    // R06: 快照已捕获但 remove 未发生——必须先核对当前 checkout 内容与快照后像是否一致。
                    // 如果快照之后有新改动（用户停机期间写入），不得强制删除，转 RECOVERY_REQUIRED 保留双方。
                    // S6: 指纹计算/快照读取失败、或快照无指纹 = 状态未知，绝不允许"当作无变化"放行强删，
                    // 一律隔离为 RECOVERY_REQUIRED（fail-closed）。指纹现覆盖 ignored/symlink/mode，
                    // 任何快照后的工作区变化都会使其变化。
                    suspend fun markArchiveRecoveryRequired(): WorkspaceOperation {
                        val reqOp = op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = newVersion, updatedAt = now)
                        workspaceStore.saveOperation(reqOp)
                        workspaceStore.saveWorkspace(ws.copy(state = WorkspaceState.RECOVERY_REQUIRED, lastSnapshotId = plan.snapshotId, lastUsedAt = now))
                        return reqOp
                    }
                    val currentFingerprint: String = try {
                        snapshotService.computeFingerprint(ws.checkoutRoot)
                    } catch (e: Exception) {
                        // 指纹计算/枚举失败 = 状态未知，不得放行删除
                        return markArchiveRecoveryRequired()
                    }
                    val snapshotFingerprint: String? = try {
                        snapshotService.loadManifest(plan.snapshotId).contentFingerprint
                    } catch (e: Exception) {
                        // 快照读取失败 = 不可比对，不放行
                        return markArchiveRecoveryRequired()
                    }
                    if (snapshotFingerprint == null) {
                        // 快照无指纹，不可比对，不放行
                        return markArchiveRecoveryRequired()
                    }
                    if (currentFingerprint != snapshotFingerprint) {
                        // 快照后有新改动 → 保留双方
                        return markArchiveRecoveryRequired()
                    }
                    // 快照后无新改动——安全重试 remove
                    val gitCommonDir = ws.gitCommonDirectory ?: gitDriver.getGitCommonDir(ws.checkoutRoot) ?: ws.checkoutRoot
                    val removeRes = gitDriver.runGit(gitCommonDir, listOf("worktree", "remove", "--force", ws.checkoutRoot))
                    if (removeRes.exitCode == 0 && !File(ws.checkoutRoot).exists()) {
                        val updatedWs = ws.copy(
                            state = WorkspaceState.ARCHIVED,
                            lastSnapshotId = plan.snapshotId,
                            generation = ws.generation + 1,
                            lastUsedAt = now
                        )
                        workspaceStore.saveWorkspace(updatedWs)
                        if (ws.associatedSessionId != null) {
                            sessionStore.setArchived(ws.associatedSessionId, now)
                        }
                        val succeededOp = op.copy(state = WorkspaceOperationState.SUCCEEDED, phase = WorkspacePhase.COMPLETE, version = newVersion, updatedAt = now)
                        workspaceStore.saveOperation(succeededOp)
                        return succeededOp
                    }
                }

                if (checkoutExists && !hasSnapshot) {
                    // 快照从未捕获：尚没有任何破坏性副作用，安全回滚为 READY
                    val failedOp = op.copy(state = WorkspaceOperationState.FAILED, phase = WorkspacePhase.COMPLETE, version = newVersion, updatedAt = now)
                    workspaceStore.saveOperation(failedOp)
                    workspaceStore.saveWorkspace(ws.copy(state = WorkspaceState.READY, lastUsedAt = now))
                    return failedOp
                }

                // 未知状态（目录已无快照等异常）—— 隔离为 RECOVERY_REQUIRED
                val reqOp = op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = newVersion, updatedAt = now)
                workspaceStore.saveOperation(reqOp)
                workspaceStore.saveWorkspace(ws.copy(state = WorkspaceState.RECOVERY_REQUIRED, lastUsedAt = now))
                return reqOp
            }
            WorkspaceOperationKind.RESTORE -> {
                val plan = op.operationPlan as? WorkspaceOperationPlan.RestorePlan
                    ?: return op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = newVersion, updatedAt = now)

                val ws = workspaceStore.getWorkspace(plan.workspaceId)
                    ?: return op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = newVersion, updatedAt = now)

                val target = File(plan.targetCheckoutPath)
                val isGitRepo = target.exists() &&
                    gitDriver.runGit(target.absolutePath, listOf("rev-parse", "--is-inside-work-tree")).exitCode == 0

                // R07/T14: 不得仅凭 "是 git 仓库" 就宣告恢复成功——必须完整核验快照内容确实已物化。
                // 覆盖 HEAD/headRef 身份、index 语义指纹、逐条目（FILE hash / SYMLINK 目标 / DIRECTORY /
                // 墓碑 exists=false / unixMode 可执行位）以及 worktree 注册。任一不符或读取失败 → 不 READY。
                val contentComplete = isGitRepo && ws.gitCommonDirectory != null &&
                    verifyRestoreCompleteness(target, plan.snapshotId, ws.gitCommonDirectory)

                // 数据库已提交 READY、仅差 SUCCEEDED 操作记录：幂等补全，不再重复递增 generation
                if (ws.state == WorkspaceState.READY && ws.checkoutRoot == plan.targetCheckoutPath && contentComplete) {
                    val succeededOp = op.copy(state = WorkspaceOperationState.SUCCEEDED, phase = WorkspacePhase.COMPLETE, version = newVersion, updatedAt = now)
                    workspaceStore.saveOperation(succeededOp)
                    return succeededOp
                }

                if (contentComplete) {
                    // 恢复目标已是注册的 git worktree —— 完成恢复
                    val updatedWs = ws.copy(
                        state = WorkspaceState.READY,
                        checkoutRoot = plan.targetCheckoutPath,
                        generation = ws.generation + 1,
                        lastUsedAt = now
                    )
                    workspaceStore.saveWorkspace(updatedWs)
                    if (ws.associatedSessionId != null) {
                        sessionStore.setArchived(ws.associatedSessionId, null)
                    }
                    val succeededOp = op.copy(state = WorkspaceOperationState.SUCCEEDED, phase = WorkspacePhase.COMPLETE, version = newVersion, updatedAt = now)
                    workspaceStore.saveOperation(succeededOp)
                    return succeededOp
                }

                // 目标 checkout 不存在或不是有效 git 仓库 —— 隔离为 RECOVERY_REQUIRED
                val reqOp = op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = newVersion, updatedAt = now)
                workspaceStore.saveOperation(reqOp)
                workspaceStore.saveWorkspace(ws.copy(state = WorkspaceState.RECOVERY_REQUIRED, lastUsedAt = now))
                return reqOp
            }
            else -> {
                val updated = op.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED, version = newVersion, updatedAt = now)
                workspaceStore.saveOperation(updated)
                return updated
            }
        }
    }

    /**
     * T14: 完整核验一次 RESTORE 是否已把快照内容真实物化到 [target]。
     *
     * 恢复 index 前崩溃会出现"文件全对、index 仍为 HEAD"的假 READY 场景，因此除逐 FILE hash 外，
     * 还必须核验：HEAD/headRef 身份、index 的 stage 语义指纹、SYMLINK 目标文本、墓碑（exists=false
     * 的路径必须不存在）、可执行位（unixMode），以及目标确已注册为 git worktree。
     * manifest 读取失败或任一 git 探测失败一律返回 false（fail-closed），绝不宣告 READY。
     */
    internal suspend fun verifyRestoreCompleteness(target: File, snapshotId: String, expectedCommonDir: String? = null): Boolean {
        val manifest = try {
            snapshotService.loadManifest(snapshotId)
        } catch (_: Exception) {
            return false
        }

        // 1. HEAD 提交对象身份匹配
        val headRes = gitDriver.runGit(target.absolutePath, listOf("rev-parse", "--verify", "HEAD^{commit}"))
        if (headRes.exitCode != 0 || headRes.stdoutString.trim() != manifest.headOid) return false

        // 2. 分支或 detached commit 身份匹配：
        // 新副本按原规范恢复为 detached HEAD，只要 HEAD^{commit} 与 headOid 匹配即可；
        // 若非 detached（symbolic-ref 存在），则要求 headRef 匹配。
        val refRes = gitDriver.runGit(target.absolutePath, listOf("symbolic-ref", "-q", "HEAD"))
        when (refRes.exitCode) {
            0 -> if (refRes.stdoutString.trim() != manifest.headRef) return false
            1 -> Unit
            else -> return false
        }

        // 2.1 核验 common-dir（确保关联至原仓库 git common dir）
        val commonDir = gitDriver.getGitCommonDir(target.absolutePath) ?: return false
        if (expectedCommonDir != null && File(commonDir).canonicalFile != File(expectedCommonDir).canonicalFile) return false

        // 3. index 语义指纹（git ls-files --stage -z 原始字节的 SHA-256）匹配
        if (manifest.indexStageSha256 != null) {
            val stageRes = gitDriver.runGit(target.absolutePath, listOf("ls-files", "--stage", "-z"))
            if (stageRes.exitCode != 0) return false
            if (sha256Hex(stageRes.stdoutBytes) != manifest.indexStageSha256) return false
        }

        // 4. 逐条目核验：FILE hash / SYMLINK 目标 / DIRECTORY / 墓碑 / 可执行位
        for (entry in manifest.entries) {
            val file = File(target, entry.repoRelativePath)
            if (!snapshotService.noSymlinkAncestor(file, target)) return false
            if (!entry.exists) {
                // 墓碑：快照声明该路径不存在——恢复后也必须不存在
                if (file.exists() || Files.isSymbolicLink(file.toPath())) return false
                continue
            }
            when (entry.kind) {
                SnapshotEntryKind.FILE -> {
                    if (!file.exists() || !file.isFile || Files.isSymbolicLink(file.toPath())) return false
                    if (snapshotService.fileSha256(file) != entry.sha256) return false
                    if (entry.unixMode != null && ((entry.unixMode and 73) != 0) != file.canExecute()) return false
                }
                SnapshotEntryKind.SYMLINK -> {
                    if (!Files.isSymbolicLink(file.toPath())) return false
                    val linkTarget = try {
                        Files.readSymbolicLink(file.toPath()).toString()
                    } catch (_: Exception) {
                        return false
                    }
                    if (linkTarget != entry.linkTarget) return false
                }
                SnapshotEntryKind.DIRECTORY -> {
                    if (!file.exists() || !file.isDirectory || Files.isSymbolicLink(file.toPath())) return false
                }
            }
        }

        // 5. 目标确已注册为 git worktree
        val wtRes = gitDriver.runGit(target.absolutePath, listOf("worktree", "list", "--porcelain", "-z"))
        if (wtRes.exitCode != 0) return false
        val canonicalTarget = target.canonicalFile
        if (wtRes.stdoutString.splitToSequence('\u0000').none { record ->
                if (!record.startsWith("worktree ")) return@none false
                val checkout = File(record.removePrefix("worktree "))
                checkout.isAbsolute && checkout.canonicalFile == canonicalTarget
            }) return false

        return true
    }

    /**
     * 取消进行中的操作。
     */
    suspend fun cancelOperation(operationId: String): WorkspaceOperation {
        val initial = workspaceStore.getOperation(operationId)
            ?: throw MederiNotFoundException("Operation $operationId not found")
        if (initial.state in terminalOperationStates) return initial
        // 先等待执行协程及其子进程收尾，再读日志。已完成的提交不能被旧取消请求覆盖。
        executingOperations[operationId]?.cancelAndJoin()
        environmentService.stopOperationExecution(operationId)
        val op = workspaceStore.getOperation(operationId)
            ?: throw MederiNotFoundException("Operation $operationId not found")
        if (op.state in terminalOperationStates) return op
        val workspaceIds = listOfNotNull(op.workspaceId, op.sourceWorkspaceId, op.targetWorkspaceId).distinct()
        val workspaces = workspaceIds.mapNotNull { workspaceStore.getWorkspace(it) }
        val checkoutRoots = (workspaces.map { it.checkoutRoot } +
            listOfNotNull((op.operationPlan as? WorkspaceOperationPlan.RestorePlan)?.targetCheckoutPath)).distinct()
        val gitCommonDir = workspaces.firstNotNullOfOrNull { it.gitCommonDirectory } ?: checkoutRoots.firstOrNull()
        if (gitCommonDir == null) {
            return cancelStoppedOperation(operationId)
        }
        return leaseManager.withManagementIntent(gitCommonDir, checkoutRoots, permitOperationId = op.id) {
            cancelStoppedOperation(operationId)
        }
    }

    private val terminalOperationStates = setOf(
        WorkspaceOperationState.SUCCEEDED, WorkspaceOperationState.FAILED, WorkspaceOperationState.CANCELLED
    )

    private suspend fun cancelStoppedOperation(operationId: String): WorkspaceOperation {
        val op = workspaceStore.getOperation(operationId)
            ?: throw MederiNotFoundException("Operation $operationId not found")
        if (op.state in terminalOperationStates) return op
        val now = Instant.now().toString()
        val newVersion = op.version + 1

        if (op.kind == WorkspaceOperationKind.ARCHIVE) {
            val workspace = op.workspaceId?.let { workspaceStore.getWorkspace(it) }
            // 已移除的副本只能完成归档记录。仍在磁盘的副本保留，不因取消重试删除。
            if (workspace != null && Files.isDirectory(File(workspace.checkoutRoot).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                workspaceStore.saveWorkspace(workspace.copy(state = WorkspaceState.READY))
                val cancelled = op.copy(state = WorkspaceOperationState.CANCELLED, phase = WorkspacePhase.COMPLETE,
                    cancelRequested = true, version = newVersion, updatedAt = now)
                workspaceStore.saveOperation(cancelled)
                return cancelled
            }
        }

        // 阶段判断：首个写副作用前（VALIDATE/QUIESCE）直接 CANCELLED
        if (op.phase == WorkspacePhase.VALIDATE || op.phase == WorkspacePhase.QUIESCE || op.state == WorkspaceOperationState.PENDING) {
            val cancelledOp = op.copy(state = WorkspaceOperationState.CANCELLED, phase = WorkspacePhase.COMPLETE, cancelRequested = true, version = newVersion, updatedAt = now)
            workspaceStore.saveOperation(cancelledOp)
            op.sourceWorkspaceId?.let { wsId ->
                workspaceStore.getWorkspace(wsId)?.let { ws ->
                    workspaceStore.saveWorkspace(ws.copy(state = WorkspaceState.READY))
                }
            }
            op.targetWorkspaceId?.let { wsId ->
                workspaceStore.getWorkspace(wsId)?.let { ws ->
                    workspaceStore.saveWorkspace(ws.copy(state = WorkspaceState.READY))
                }
            }
            return cancelledOp
        }

        // 首个写副作用后：标记 CANCELLING，并在独占管理意图内执行安全回滚，防止原操作与取消同时修改文件
        val cancellingOp = op.copy(state = WorkspaceOperationState.CANCELLING, cancelRequested = true, version = newVersion, updatedAt = now)
        workspaceStore.saveOperation(cancellingOp)

        return recoverSingleOperation(cancellingOp)
    }

    /**
     * 崩溃恢复预览（验证候选快照所有权与版本核验）。
     */
    suspend fun previewRecovery(
        operationId: String,
        strategy: RecoveryStrategy = RecoveryStrategy.KEEP_VERIFIED_CURRENT,
        snapshotId: String? = null,
        keepWorkspaceId: String? = null
    ): RecoveryPreview {
        val op = workspaceStore.getOperation(operationId)
            ?: throw MederiNotFoundException("Operation $operationId not found")

        // 严格仅搜集属于该操作的合规快照
        val candidateSnapshotIds = mutableListOf<String>()
        op.sourceSnapshotId?.let { candidateSnapshotIds.add(it) }
        op.targetSnapshotId?.let { candidateSnapshotIds.add(it) }

        val plan = op.operationPlan
        if (plan is WorkspaceOperationPlan.HandoffPlan) {
            plan.sourceTransferSnapshotId?.let { candidateSnapshotIds.add(it) }
            plan.sourcePreimageSnapshotId?.let { candidateSnapshotIds.add(it) }
            plan.targetPreimageSnapshotId?.let { candidateSnapshotIds.add(it) }
            plan.acceptedTargetSnapshotId?.let { candidateSnapshotIds.add(it) }
        }

        val eligibleSnapshots = mutableListOf<RecoverySnapshotInfo>()
        for (snapId in candidateSnapshotIds.distinct()) {
            val manifestFile = File(snapshotsRoot, "$snapId/manifest.json")
            if (manifestFile.exists()) {
                val manifest = try { snapshotService.loadManifest(snapId) } catch (e: Exception) { null }
                if (manifest != null) {
                    val sizeBytes = File(snapshotsRoot, snapId).walkTopDown().filter { it.isFile }.map { it.length() }.sum()
                    eligibleSnapshots.add(
                        RecoverySnapshotInfo(
                            snapshotId = snapId,
                            createdAt = manifest.createdAt,
                            reason = manifest.reason,
                            sizeBytes = sizeBytes
                        )
                    )
                }
            }
        }

        // 校验请求的 snapshotId
        if (snapshotId != null && eligibleSnapshots.none { it.snapshotId == snapshotId }) {
            throw MederiWorkspaceException(WorkspaceErrorCode.SNAPSHOT_CORRUPT, "Requested snapshot $snapshotId does not belong to operation $operationId")
        }

        val sourceWs = op.sourceWorkspaceId?.let { workspaceStore.getWorkspace(it) }
        val targetWs = op.targetWorkspaceId?.let { workspaceStore.getWorkspace(it) }

        val sourceInfo = sourceWs?.let { ws ->
            val repo = gitDriver.discover(ws.checkoutRoot)
            WorkspaceInfo(workspace = ws, headOid = repo.headOid, branch = repo.branch, dirty = repo.dirty, capabilities = emptySet())
        }
        val targetInfo = targetWs?.let { ws ->
            val repo = gitDriver.discover(ws.checkoutRoot)
            WorkspaceInfo(workspace = ws, headOid = repo.headOid, branch = repo.branch, dirty = repo.dirty, capabilities = emptySet())
        }

        val token = "recov_${UUID.randomUUID().toString().take(12)}"
        val now = Instant.now()
        val expiresAt = now.plusSeconds(60).toString()

        val preview = RecoveryPreview(
            token = token,
            operationVersion = op.version,
            sourceWorkspace = sourceInfo,
            targetWorkspace = targetInfo,
            eligibleSnapshots = eligibleSnapshots,
            conflicts = emptyList(),
            expiresAt = expiresAt
        )

        recoveryPreviewCache[token] = CachedRecoveryPreview(
            preview = preview,
            createdAt = now,
            operation = op
        )

        return preview
    }

    /**
     * 执行崩溃恢复。
     */
    suspend fun executeRecovery(input: WorkspaceRecoveryInput): WorkspaceOperation {
        val cached = recoveryPreviewCache[input.previewToken]
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.STALE_PREVIEW, "Recovery preview token not found or expired")

        if (Instant.now().isAfter(Instant.parse(cached.preview.expiresAt))) {
            throw MederiWorkspaceException(WorkspaceErrorCode.STALE_PREVIEW, "Recovery preview token expired")
        }

        val currentOp = workspaceStore.getOperation(cached.operation.id)
            ?: throw MederiNotFoundException("Operation ${cached.operation.id} not found")

        if (currentOp.version != input.expectedOperationVersion) {
            throw MederiWorkspaceException(WorkspaceErrorCode.STALE_PREVIEW, "Operation version mismatch: expected ${input.expectedOperationVersion}, actual ${currentOp.version}")
        }

        val now = Instant.now().toString()
        val newVersion = currentOp.version + 1

        when (input.strategy) {
            RecoveryStrategy.KEEP_VERIFIED_CURRENT -> {
                val keepId = input.keepWorkspaceId
                    ?: throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "keepWorkspaceId must be specified for KEEP_VERIFIED_CURRENT")

                if (keepId != currentOp.sourceWorkspaceId && keepId != currentOp.targetWorkspaceId) {
                    throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "keepWorkspaceId $keepId does not belong to operation ${currentOp.id}")
                }

                val keepWs = workspaceStore.getWorkspace(keepId)
                    ?: throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_MISSING, "Workspace $keepId not found")

                // 核验无活跃租约并捕获当前基准快照
                snapshotService.captureSnapshot(
                    checkoutRoot = keepWs.checkoutRoot,
                    workspaceId = keepWs.id,
                    reason = "keep_verified_current_baseline",
                    captureType = SnapshotCaptureType.TRANSFER
                )

                val updatedWs = keepWs.copy(state = WorkspaceState.READY, generation = keepWs.generation + 1, lastUsedAt = now)
                workspaceStore.saveWorkspace(updatedWs)

                if (currentOp.sessionId != null) {
                    val associatedWorktreeId = if (updatedWs.kind == WorkspaceKind.MANAGED_WORKTREE) updatedWs.id else null
                    sessionStore.updateWorkspaceBinding(id = currentOp.sessionId, workspaceId = updatedWs.id, associatedWorktreeId = associatedWorktreeId)
                }

                val resolution = RecoveryResolution(strategy = RecoveryStrategy.KEEP_VERIFIED_CURRENT, keepWorkspaceId = keepWs.id, resolvedAt = now)
                val resolvedOp = currentOp.copy(state = WorkspaceOperationState.SUCCEEDED, phase = WorkspacePhase.COMPLETE, recoveryResolution = resolution, version = newVersion, updatedAt = now)
                workspaceStore.saveOperation(resolvedOp)
                recoveryPreviewCache.remove(input.previewToken)
                return resolvedOp
            }
            RecoveryStrategy.RESTORE_TO_NEW_MANAGED -> {
                val snapshotId = input.snapshotId
                    ?: throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "snapshotId must be specified for RESTORE_TO_NEW_MANAGED")

                // 硬校验快照所有权：必须属于本操作的 eligibleSnapshots
                val isEligible = cached.preview.eligibleSnapshots.any { it.snapshotId == snapshotId }
                if (!isEligible) {
                    throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Snapshot $snapshotId does not belong to operation ${currentOp.id}")
                }

                val manifest = snapshotService.loadManifest(snapshotId)
                val snapshotWorkspace = workspaceStore.getWorkspace(manifest.workspaceId)
                val commonDir = snapshotWorkspace?.takeIf { it.projectId == currentOp.projectId }?.gitCommonDirectory
                    ?: throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_MISSING, "Snapshot has no original Git common directory")
                val boundWorkspace = currentOp.sessionId?.let { sessionId ->
                    val session = sessionStore.get(sessionId)
                    val workspaceId = session?.workspaceId
                    if (workspaceId == null || workspaceId !in setOf(currentOp.workspaceId, currentOp.sourceWorkspaceId, currentOp.targetWorkspaceId)) {
                        throw MederiWorkspaceException(WorkspaceErrorCode.STALE_GENERATION, "Session no longer belongs to the recovery operation")
                    }
                    workspaceStore.getWorkspace(workspaceId)
                        ?: throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_MISSING, "Session workspace is missing")
                }
                val newWsId = "ws_${UUID.randomUUID().toString().take(12)}"
                val newCheckoutRoot = File(getWorktreeSettings().rootPath, "$newWsId/checkout").canonicalPath
                val roots = listOfNotNull(newCheckoutRoot, boundWorkspace?.checkoutRoot)
                return runManagedOperation(currentOp.id, commonDir, roots) {
                    if (workspaceStore.getOperation(currentOp.id)?.version != currentOp.version) {
                        throw MederiWorkspaceException(WorkspaceErrorCode.STALE_PREVIEW, "Recovery operation changed")
                    }
                    snapshotService.restoreToLinkedWorktree(snapshotId, newCheckoutRoot, commonDir)
                    if (!verifyRestoreCompleteness(File(newCheckoutRoot), snapshotId, commonDir)) {
                        throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED, "Recovered checkout does not match its snapshot")
                    }

                    val newWs = Workspace(
                        id = newWsId,
                        projectId = currentOp.projectId,
                        kind = WorkspaceKind.MANAGED_WORKTREE,
                        checkoutRoot = newCheckoutRoot,
                        projectRelativePath = manifest.projectRelativePath,
                        gitCommonDirectory = commonDir,
                        baseCommit = manifest.headOid,
                        associatedSessionId = currentOp.sessionId,
                        state = WorkspaceState.READY,
                        generation = 1L,
                        createdAt = now,
                        lastUsedAt = now
                    )

                    val resolution = RecoveryResolution(strategy = RecoveryStrategy.RESTORE_TO_NEW_MANAGED, snapshotId = snapshotId, resolvedAt = now)
                    val resolvedOp = currentOp.copy(state = WorkspaceOperationState.SUCCEEDED, phase = WorkspacePhase.COMPLETE, recoveryResolution = resolution, version = newVersion, updatedAt = now)
                    if (currentOp.sessionId != null) {
                        val committed = workspaceStore.commitBinding(currentOp.sessionId, boundWorkspace!!.id, boundWorkspace.generation, newWs, newWs.id, resolvedOp)
                        if (!committed) {
                            throw MederiWorkspaceException(WorkspaceErrorCode.STALE_GENERATION, "Recovery binding changed before commit")
                        }
                    } else {
                        workspaceStore.saveWorkspace(newWs)
                        workspaceStore.saveOperation(resolvedOp)
                    }
                    recoveryPreviewCache.remove(input.previewToken)
                    resolvedOp
                }
            }
        }
    }

    /**
     * 重试准备。
     */
    suspend fun retryPreparation(
        sessionId: String,
        failedOperationId: String,
        requestId: String
    ): WorkspaceOperation {
        val failedOp = workspaceStore.getOperation(failedOperationId)
            ?: throw MederiNotFoundException("Failed operation $failedOperationId not found")

        val session = sessionStore.get(sessionId)
            ?: throw MederiNotFoundException("Session $sessionId not found")

        val wsId = session.workspaceId
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "No workspace associated with failed operation")

        if (failedOp.projectId != session.projectId || failedOp.workspaceId != wsId ||
            (failedOp.sessionId != null && failedOp.sessionId != sessionId) ||
            failedOp.kind != WorkspaceOperationKind.CREATE ||
            failedOp.state !in setOf(WorkspaceOperationState.FAILED, WorkspaceOperationState.CANCELLED)
        ) {
            throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Operation $failedOperationId is not a retryable preparation for session $sessionId")
        }

        val ws = workspaceStore.getWorkspace(wsId)
            ?: throw MederiNotFoundException("Workspace $wsId not found")
        if (ws.state == WorkspaceState.READY) {
            val previous = workspaceStore.getOperationByIdempotencyKey("CREATE:$requestId")
            if (previous?.workspaceId == ws.id && previous.state == WorkspaceOperationState.SUCCEEDED) return previous
            // 响应丢失或重启后，准备已经成功。返回既有结果供调用方继续原输入，不重跑 setup。
            return workspaceStore.listOperations(ws.projectId).firstOrNull {
                it.workspaceId == ws.id && it.kind == WorkspaceOperationKind.CREATE && it.state == WorkspaceOperationState.SUCCEEDED
            } ?: throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Workspace preparation is already complete")
        }
        if (ws.state !in setOf(WorkspaceState.REQUESTED, WorkspaceState.PREPARING)) {
            throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_BUSY, "Workspace is in state ${ws.state}")
        }

        val project = projectStore?.get(ws.projectId)
        val sourceRepoRoot = project?.directory ?: ws.checkoutRoot
        val permissions = buildWorkspacePermissions(ws, sessionId)

        return runCatching {
            prepareWorkspace(ws.id, sourceRepoRoot, requestId, permissions)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            // 重试操作在持久层已记录 FAILED/WAITING 等终态操作记录，返回该操作以供 UI 状态条展示，不裸抛异常
            val latestOp = workspaceStore.listOperations(ws.projectId)
                .filter { it.workspaceId == ws.id && it.kind == WorkspaceOperationKind.CREATE && it.idempotencyKey == "CREATE:$requestId" }
                .maxByOrNull { it.updatedAt }
            latestOp ?: throw e
        }
    }

    suspend fun retryPreparation(
        workspaceId: String,
        sourceRepoRoot: String,
        requestId: String,
        permissions: ExecutionPermissions? = null
    ): WorkspaceOperation {
        val ws = workspaceStore.getWorkspace(workspaceId)
            ?: throw MederiNotFoundException("Workspace $workspaceId not found")

        if (ws.state != WorkspaceState.REQUESTED && ws.state != WorkspaceState.PREPARING) {
            throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Workspace $workspaceId is in state ${ws.state}, cannot retry preparation")
        }

        return prepareWorkspace(workspaceId, sourceRepoRoot, requestId, permissions)
    }

    /**
     * 重试 setup 脚本。
     */
    suspend fun retrySetup(
        workspaceId: String,
        failedOperationId: String,
        expectedEnvironmentHash: String,
        requestId: String,
        permissions: ExecutionPermissions? = null
    ): WorkspaceOperation {
        val ws = workspaceStore.getWorkspace(workspaceId)
            ?: throw MederiNotFoundException("Workspace $workspaceId not found")

        val failedOp = workspaceStore.getOperation(failedOperationId)
            ?: throw MederiNotFoundException("Failed operation $failedOperationId not found")

        val envId = (failedOp.operationPlan as? WorkspaceOperationPlan.SetupPlan)?.environmentId
            ?: ws.pendingSelection?.environmentId
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "No environment found on failed operation")

        val env = environmentService.getEnvironment(File(ws.checkoutRoot), envId)
            ?: throw MederiNotFoundException("Environment $envId not found")

        val computedHash = environmentService.computeEnvironmentHash(env)
        if (computedHash != expectedEnvironmentHash) {
            throw MederiWorkspaceException(WorkspaceErrorCode.ENVIRONMENT_CHANGED, "Environment hash mismatch: expected $expectedEnvironmentHash, computed $computedHash")
        }

        val effectivePermissions = permissions ?: buildWorkspacePermissions(ws)
        val op = environmentService.executeSetup(
            workspace = ws,
            environment = env,
            expectedHash = computedHash,
            requestId = requestId,
            permissions = effectivePermissions
        ).copy(parentOperationId = failedOperationId)

        workspaceStore.saveOperation(op)

        if (op.state == WorkspaceOperationState.SUCCEEDED) {
            val updatedWs = ws.copy(state = WorkspaceState.READY, lastUsedAt = Instant.now().toString())
            workspaceStore.saveWorkspace(updatedWs)
        }
        return op
    }

    suspend fun retrySetup(
        workspaceId: String,
        environmentId: String,
        requestId: String,
        permissions: ExecutionPermissions? = null
    ): WorkspaceOperation {
        val ws = workspaceStore.getWorkspace(workspaceId)
            ?: throw MederiNotFoundException("Workspace $workspaceId not found")

        val env = environmentService.getEnvironment(File(ws.checkoutRoot), environmentId)
            ?: throw MederiNotFoundException("Environment $environmentId not found")

        val expectedHash = environmentService.computeEnvironmentHash(env)
        val effectivePermissions = permissions ?: buildWorkspacePermissions(ws)
        val op = environmentService.executeSetup(
            workspace = ws,
            environment = env,
            expectedHash = expectedHash,
            requestId = requestId,
            permissions = effectivePermissions
        )

        workspaceStore.saveOperation(op)

        if (op.state == WorkspaceOperationState.SUCCEEDED) {
            val updatedWs = ws.copy(state = WorkspaceState.READY, lastUsedAt = Instant.now().toString())
            workspaceStore.saveWorkspace(updatedWs)
        }
        return op
    }

    private val worktreeSettingsLock = Mutex()
    private var inMemoryWorktreeSettings = WorktreeSettings(rootPath = worktreesRoot, version = 1L)

    private suspend fun readWorktreeSettings(): WorktreeSettings {
        val stored = settingsStore?.get("workspace.settings") ?: return inMemoryWorktreeSettings
        return try {
            json.decodeFromString(WorktreeSettings.serializer(), stored)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw MederiStateException("Stored worktree settings are invalid", e)
        }
    }

    suspend fun getWorktreeSettings(): WorktreeSettings = worktreeSettingsLock.withLock { readWorktreeSettings() }

    suspend fun updateWorktreeSettings(newSettings: WorktreeSettings): WorktreeSettings = worktreeSettingsLock.withLock {
        val rootFile = File(newSettings.rootPath)
        if (!rootFile.isAbsolute) {
            throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Worktree root path must be an absolute path: ${newSettings.rootPath}")
        }
        if (newSettings.maxManagedWorktrees <= 0) {
            throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Max managed worktrees must be positive: ${newSettings.maxManagedWorktrees}")
        }

        val root = rootFile.canonicalFile.toPath()
        val snapshotRoot = File(snapshotsRoot).canonicalFile.toPath()
        val overlapsProject = projectStore?.list().orEmpty().any { project ->
            val projectRoot = File(project.directory).canonicalFile.toPath()
            root.startsWith(projectRoot) || projectRoot.startsWith(root)
        }
        if (root.parent == null || root.startsWith(snapshotRoot) || snapshotRoot.startsWith(root) || overlapsProject) {
            throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Worktree root overlaps a source project, the snapshot store, or the filesystem root")
        }
        if (Files.exists(root) && !Files.isDirectory(root)) {
            throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Worktree root must be a directory: $root")
        }
        val current = readWorktreeSettings()
        if (newSettings.version != current.version) {
            throw MederiWorkspaceException(WorkspaceErrorCode.STALE_GENERATION, "Settings version mismatch: expected ${current.version}, actual ${newSettings.version}")
        }
        val updated = newSettings.copy(version = current.version + 1)
        val store = settingsStore
        if (store != null) {
            store.set("workspace.settings", json.encodeToString(WorktreeSettings.serializer(), updated))
        } else {
            inMemoryWorktreeSettings = updated
        }
        updated
    }

    /**
     * 归档工作区：
     * LOCAL 仅标记归档记录，绝不删除本地目录；
     * MANAGED 必须先捕获完整快照（包含 ignored），通过后安全 remove worktree。
     */
    suspend fun archiveWorkspace(
        workspaceId: String,
        input: ArchiveWorkspaceInput
    ): WorkspaceOperation {
        if (input.confirmDiscardIgnored) {
            throw MederiWorkspaceException(
                WorkspaceErrorCode.INVALID_REQUEST,
                "v1 never allows skipping backup via confirmDiscardIgnored"
            )
        }

        val ws = workspaceStore.getWorkspace(workspaceId)
            ?: throw MederiNotFoundException("Workspace $workspaceId not found")

        if (ws.state == WorkspaceState.ARCHIVED) {
            val existing = workspaceStore.listOperations(ws.projectId).find { it.workspaceId == ws.id && it.kind == WorkspaceOperationKind.ARCHIVE && it.state == WorkspaceOperationState.SUCCEEDED }
            if (existing != null) return existing
        }

        val now = Instant.now().toString()
        val operationId = "op_arch_${UUID.randomUUID().toString().take(12)}"
        val idempotencyKey = "ARCHIVE:${input.requestId}"
        val requestHash = "${ws.id}:${ws.generation}"

        val plan = WorkspaceOperationPlan.ArchivePlan(
            requestHash = requestHash,
            workspaceId = ws.id,
            checkoutPath = ws.checkoutRoot
        )

        if (ws.kind == WorkspaceKind.LOCAL) {
            // LOCAL: 绝不删除目录
            val updatedWs = ws.copy(state = WorkspaceState.ARCHIVED, lastUsedAt = now)
            workspaceStore.saveWorkspace(updatedWs)
            if (ws.associatedSessionId != null) {
                sessionStore.setArchived(ws.associatedSessionId, now)
            }
            val op = WorkspaceOperation(
                id = operationId,
                projectId = ws.projectId,
                sessionId = ws.associatedSessionId,
                workspaceId = ws.id,
                kind = WorkspaceOperationKind.ARCHIVE,
                state = WorkspaceOperationState.SUCCEEDED,
                phase = WorkspacePhase.COMPLETE,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                operationPlan = plan,
                createdAt = now,
                updatedAt = now
            )
            workspaceStore.saveOperation(op)
            return op
        }

        // MANAGED_WORKTREE:
        val checkoutFile = File(ws.checkoutRoot)
        if (!checkoutFile.exists()) {
            throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_MISSING, "Managed checkout ${ws.checkoutRoot} does not exist")
        }

        val gitCommonDir = ws.gitCommonDirectory ?: gitDriver.getGitCommonDir(ws.checkoutRoot) ?: ws.checkoutRoot
        return runManagedOperation(operationId, gitCommonDir, listOf(ws.checkoutRoot)) { _ ->
            val latest = workspaceStore.getWorkspace(ws.id)
            if (latest == null || latest.generation != ws.generation || latest.state != ws.state) {
                throw MederiWorkspaceException(WorkspaceErrorCode.STALE_GENERATION, "Workspace changed before archive")
            }
            // 阶段 1: 标记 ARCHIVING
            val archivingWs = ws.copy(state = WorkspaceState.ARCHIVING, lastUsedAt = now)
            workspaceStore.saveWorkspace(archivingWs)

            // RF-04: 在第一个 git 副作用（快照/remove）之前持久化 RUNNING 操作日志与完整 ArchivePlan，
            // 崩溃后恢复可从日志 + 文件/Git 状态推算是否已 remove。
            var journalOp = WorkspaceOperation(
                id = operationId,
                projectId = ws.projectId,
                sessionId = ws.associatedSessionId,
                workspaceId = ws.id,
                kind = WorkspaceOperationKind.ARCHIVE,
                state = WorkspaceOperationState.RUNNING,
                phase = WorkspacePhase.SNAPSHOT,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                operationPlan = plan,
                createdAt = now,
                updatedAt = now
            )
            workspaceStore.saveOperation(journalOp)

            // 阶段 2: 捕获全量 PREIMAGE 快照（包含 ignored 文件）
            val snapshot = try {
                snapshotService.captureSnapshot(
                    checkoutRoot = ws.checkoutRoot,
                    workspaceId = ws.id,
                    reason = "archive_preimage",
                    captureType = SnapshotCaptureType.PREIMAGE
                )
            } catch (e: Exception) {
                // 快照失败，绝不删除目录！恢复原状态
                if (e is CancellationException) throw e
                workspaceStore.saveWorkspace(ws.copy(state = WorkspaceState.READY))
                workspaceStore.saveOperation(journalOp.copy(
                    state = WorkspaceOperationState.FAILED,
                    error = WorkspaceError((e as? MederiWorkspaceException)?.code ?: WorkspaceErrorCode.IO_ERROR,
                        e.message ?: "Snapshot capture failed"),
                    updatedAt = Instant.now().toString()
                ))
                throw e
            }

            // 快照已持久化：把 snapshotId 与 SNAPSHOT 完成记录写回日志
            journalOp = journalOp.copy(
                operationPlan = plan.copy(snapshotId = snapshot.snapshotId),
                sourceSnapshotId = snapshot.snapshotId,
                phase = WorkspacePhase.CLEAN_SOURCE,
                phaseRecords = journalOp.phaseRecords + WorkspacePhaseRecord(
                    phase = WorkspacePhase.SNAPSHOT,
                    startedAt = journalOp.createdAt,
                    finishedAt = Instant.now().toString()
                ),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(journalOp)

            // 核验前像完整性（第四轮复审 P1）: 删除前在管理意图内核验完整前像，若发生外部变动或读取失败，保留副本不强删
            val currentFingerprint = try {
                snapshotService.computeFingerprint(ws.checkoutRoot)
            } catch (e: Exception) {
                null
            }
            if (currentFingerprint == null || currentFingerprint != snapshot.contentFingerprint) {
                val reqWs = ws.copy(state = WorkspaceState.RECOVERY_REQUIRED, lastSnapshotId = snapshot.snapshotId, lastUsedAt = now)
                workspaceStore.saveWorkspace(reqWs)
                workspaceStore.saveOperation(journalOp.copy(
                    state = WorkspaceOperationState.RECOVERY_REQUIRED,
                    error = WorkspaceError(WorkspaceErrorCode.RECOVERY_REQUIRED, "Checkout changed after snapshot capture"),
                    updatedAt = Instant.now().toString()
                ))
                throw MederiWorkspaceException(
                    WorkspaceErrorCode.RECOVERY_REQUIRED,
                    "Checkout modified or unreadable after archive snapshot; preserving workspace checkout"
                )
            }

            // 阶段 3: 执行 git worktree remove --force <path>
            currentCoroutineContext().ensureActive()
            val removeRes = gitDriver.runGit(gitCommonDir, listOf("worktree", "remove", "--force", ws.checkoutRoot))
            if (removeRes.exitCode != 0) {
                // 如果 remove 失败且目录仍然存在
                if (checkoutFile.exists()) {
                    val reqWs = ws.copy(state = WorkspaceState.RECOVERY_REQUIRED, lastSnapshotId = snapshot.snapshotId, lastUsedAt = now)
                    workspaceStore.saveWorkspace(reqWs)
                    throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED, "git worktree remove failed: ${removeRes.stderr}")
                }
            }

            // 阶段 4: 验证 Git 记录已无且目录不存在
            if (checkoutFile.exists()) {
                val reqWs = ws.copy(state = WorkspaceState.RECOVERY_REQUIRED, lastSnapshotId = snapshot.snapshotId, lastUsedAt = now)
                workspaceStore.saveWorkspace(reqWs)
                throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED, "Directory still exists after worktree remove")
            }

            // worktree 已移除但数据库尚未提交 ARCHIVED：先持久 COMPLETE 阶段日志，
            // 崩溃后恢复扫描据此完成归档（避免"目录已删、无操作可恢复"）。
            journalOp = journalOp.copy(
                phase = WorkspacePhase.COMPLETE,
                phaseRecords = journalOp.phaseRecords + WorkspacePhaseRecord(
                    phase = WorkspacePhase.REMOVE_CHECKOUT,
                    startedAt = Instant.now().toString(),
                    finishedAt = Instant.now().toString()
                ),
                updatedAt = Instant.now().toString()
            )
            workspaceStore.saveOperation(journalOp)

            val updatedWs = ws.copy(
                state = WorkspaceState.ARCHIVED,
                lastSnapshotId = snapshot.snapshotId,
                generation = ws.generation + 1,
                lastUsedAt = now
            )
            workspaceStore.saveWorkspace(updatedWs)

            if (ws.associatedSessionId != null) {
                sessionStore.setArchived(ws.associatedSessionId, now)
            }

            val op = WorkspaceOperation(
                id = operationId,
                projectId = ws.projectId,
                sessionId = ws.associatedSessionId,
                workspaceId = ws.id,
                kind = WorkspaceOperationKind.ARCHIVE,
                state = WorkspaceOperationState.SUCCEEDED,
                phase = WorkspacePhase.COMPLETE,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                sourceSnapshotId = snapshot.snapshotId,
                operationPlan = plan.copy(snapshotId = snapshot.snapshotId),
                createdAt = now,
                updatedAt = now
            )
            workspaceStore.saveOperation(op)
            op
        }
    }

    /**
     * 恢复已归档的工作区：从快照还原。
     */
    suspend fun restoreWorkspace(
        workspaceId: String,
        input: RestoreWorkspaceInput
    ): WorkspaceOperation {
        val ws = workspaceStore.getWorkspace(workspaceId)
            ?: throw MederiNotFoundException("Workspace $workspaceId not found")

        if (ws.state != WorkspaceState.ARCHIVED) {
            throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Workspace $workspaceId is not archived (current state: ${ws.state})")
        }

        val snapshotId = ws.lastSnapshotId
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.SNAPSHOT_CORRUPT, "Workspace $workspaceId has no lastSnapshotId")

        val now = Instant.now().toString()
        val operationId = "op_rest_${UUID.randomUUID().toString().take(12)}"
        val idempotencyKey = "RESTORE:${input.requestId}"
        val requestHash = "${ws.id}:$snapshotId"

        // 确定恢复目标路径：原路径若不存在则复用原路径，否则分配新目录
        val currentRoot = getWorktreeSettings().rootPath
        val targetCheckoutRoot = if (!File(ws.checkoutRoot).exists()) {
            ws.checkoutRoot
        } else {
            File(currentRoot, "${ws.id}/checkout_${UUID.randomUUID().toString().take(6)}").canonicalPath
        }
        val gitCommonDir = ws.gitCommonDirectory
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_MISSING, "Cannot restore without the original Git common directory")
        return runManagedOperation(operationId, gitCommonDir, listOf(ws.checkoutRoot, targetCheckoutRoot)) {
            val latest = workspaceStore.getWorkspace(ws.id)
            if (latest?.generation != ws.generation || latest.state != WorkspaceState.ARCHIVED) {
                throw MederiWorkspaceException(WorkspaceErrorCode.STALE_GENERATION, "Workspace changed before restore")
            }
            val plan = WorkspaceOperationPlan.RestorePlan(
                requestHash = requestHash,
                workspaceId = ws.id,
                snapshotId = snapshotId,
                targetCheckoutPath = targetCheckoutRoot
            )

            // 标记 RESTORING
            val restoringWs = ws.copy(state = WorkspaceState.RESTORING, lastUsedAt = now)
            workspaceStore.saveWorkspace(restoringWs)

            // RF-04: 在 restoreToLinkedWorktree 副作用之前持久化 RUNNING 操作日志与完整 RestorePlan。
            val runningOp = WorkspaceOperation(
                id = operationId,
                projectId = ws.projectId,
                sessionId = ws.associatedSessionId,
                workspaceId = ws.id,
                kind = WorkspaceOperationKind.RESTORE,
                state = WorkspaceOperationState.RUNNING,
                phase = WorkspacePhase.CREATE_CHECKOUT,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                targetSnapshotId = snapshotId,
                operationPlan = plan,
                createdAt = now,
                updatedAt = now
            )
            workspaceStore.saveOperation(runningOp)

            // 执行还原：RF-06 恢复为原仓库的 linked worktree（共享 common-dir 对象库），而非独立 git init 仓库
            snapshotService.restoreToLinkedWorktree(snapshotId, targetCheckoutRoot, gitCommonDir)
            if (!verifyRestoreCompleteness(File(targetCheckoutRoot), snapshotId, gitCommonDir)) {
                workspaceStore.saveWorkspace(restoringWs.copy(state = WorkspaceState.RECOVERY_REQUIRED))
                workspaceStore.saveOperation(runningOp.copy(state = WorkspaceOperationState.RECOVERY_REQUIRED))
                throw MederiWorkspaceException(WorkspaceErrorCode.RECOVERY_REQUIRED, "Restored checkout does not match its snapshot")
            }

            val updatedWs = ws.copy(
                state = WorkspaceState.READY,
                checkoutRoot = targetCheckoutRoot,
                generation = ws.generation + 1,
                lastUsedAt = now
            )
            workspaceStore.saveWorkspace(updatedWs)

            if (ws.associatedSessionId != null) {
                sessionStore.setArchived(ws.associatedSessionId, null)
            }

            val op = WorkspaceOperation(
                id = operationId,
                projectId = ws.projectId,
                sessionId = ws.associatedSessionId,
                workspaceId = ws.id,
                kind = WorkspaceOperationKind.RESTORE,
                state = WorkspaceOperationState.SUCCEEDED,
                phase = WorkspacePhase.COMPLETE,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                targetSnapshotId = snapshotId,
                operationPlan = plan,
                createdAt = now,
                updatedAt = now
            )
            workspaceStore.saveOperation(op)
            op
        }
    }

    /**
     * 会话归档/解除归档操作。
     */
    suspend fun setConversationArchived(
        sessionId: String,
        archived: Boolean,
        requestId: String
    ): WorkspaceOperation {
        val session = sessionStore.get(sessionId)
            ?: throw MederiNotFoundException("Session $sessionId not found")

        val wsId = session.workspaceId
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_MISSING, "Session has no workspace")
        val ws = workspaceStore.getWorkspace(wsId)
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_MISSING, "Workspace $wsId not found")

        return if (archived) {
            val op = archiveWorkspace(ws.id, ArchiveWorkspaceInput(requestId = requestId))
            sessionStore.setArchived(sessionId, Instant.now().toString())
            op
        } else {
            val op = if (ws.state == WorkspaceState.ARCHIVED) {
                restoreWorkspace(ws.id, RestoreWorkspaceInput(requestId = requestId))
            } else {
                val now = Instant.now().toString()
                WorkspaceOperation(
                    id = "op_unarch_${UUID.randomUUID().toString().take(12)}",
                    projectId = session.projectId,
                    sessionId = session.id,
                    workspaceId = ws.id,
                    kind = WorkspaceOperationKind.RESTORE,
                    state = WorkspaceOperationState.SUCCEEDED,
                    phase = WorkspacePhase.COMPLETE,
                    idempotencyKey = "UNARCHIVE:$requestId",
                    requestHash = "${session.id}:unarchive",
                    operationPlan = WorkspaceOperationPlan.RestorePlan(
                        requestHash = "${session.id}:unarchive",
                        workspaceId = ws.id,
                        snapshotId = ws.lastSnapshotId ?: "",
                        targetCheckoutPath = ws.checkoutRoot
                    ),
                    createdAt = now,
                    updatedAt = now
                )
            }
            sessionStore.setArchived(sessionId, null)
            op
        }
    }

    /**
     * 设置会话固定状态。
     */
    suspend fun setConversationPinned(sessionId: String, pinned: Boolean): xyz.mederi.domain.model.Session {
        val session = sessionStore.get(sessionId)
            ?: throw MederiNotFoundException("Session $sessionId not found")
        sessionStore.setPinned(sessionId, pinned)
        return sessionStore.get(sessionId)!!
    }

    /**
     * 限额管理：清理超额受管副本。
     */
    suspend fun enforceQuota(projectId: String): List<WorkspaceOperation> {
        val settings = getWorktreeSettings()
        if (!settings.automaticCleanup) return emptyList()

        val allWs = workspaceStore.listWorkspaces(projectId)
        val managedWorktrees = allWs.filter { it.kind == WorkspaceKind.MANAGED_WORKTREE }

        if (managedWorktrees.size <= settings.maxManagedWorktrees) {
            return emptyList()
        }

        val excessCount = managedWorktrees.size - settings.maxManagedWorktrees
        val allSessions = sessionStore.list().associateBy { it.id }

        // 筛选可自动清理项：MANAGED + READY，非 pinned，非运行中/待批/迁移
        val candidates = managedWorktrees.filter { ws ->
            if (ws.state != WorkspaceState.READY) return@filter false
            val session = ws.associatedSessionId?.let { allSessions[it] }
            if (session?.pinned == true) return@filter false
            // 检查是否有未终态的操作占用
            val ops = workspaceStore.listOperations(projectId).filter { it.workspaceId == ws.id }
            if (ops.any { it.state in listOf(WorkspaceOperationState.RUNNING, WorkspaceOperationState.WAITING_APPROVAL, WorkspaceOperationState.CANCELLING) }) {
                return@filter false
            }
            true
        }.sortedBy { it.lastUsedAt }

        val operations = mutableListOf<WorkspaceOperation>()
        for (i in 0 until minOf(excessCount, candidates.size)) {
            val candidate = candidates[i]
            val op = archiveWorkspace(candidate.id, ArchiveWorkspaceInput(requestId = "quota_clean_${candidate.id}_${UUID.randomUUID().toString().take(8)}"))
            operations.add(op)
        }
        return operations
    }

    /**
     * 获取工作区元数据及能力集。
     */
    suspend fun getWorkspaceInfo(ws: Workspace): WorkspaceInfo {
        val repo = gitDriver.discover(ws.checkoutRoot)
        val capabilities = when (ws.kind) {
            WorkspaceKind.LOCAL -> {
                val caps = mutableSetOf(WorkspaceCapability.OPEN_FILES, WorkspaceCapability.OPEN_TERMINAL)
                if (repo.isGit) caps.add(WorkspaceCapability.HANDOFF)
                if (ws.associatedSessionId != null) caps.add(WorkspaceCapability.ARCHIVE)
                if (ws.pendingSelection?.environmentId != null) caps.add(WorkspaceCapability.RUN_ACTION)
                caps
            }
            WorkspaceKind.MANAGED_WORKTREE -> {
                when (ws.state) {
                    WorkspaceState.READY -> setOf(
                        WorkspaceCapability.OPEN_FILES,
                        WorkspaceCapability.OPEN_TERMINAL,
                        WorkspaceCapability.CREATE_BRANCH,
                        WorkspaceCapability.HANDOFF,
                        WorkspaceCapability.ARCHIVE,
                        WorkspaceCapability.RUN_ACTION
                    )
                    WorkspaceState.ARCHIVED -> setOf(WorkspaceCapability.RESTORE)
                    else -> emptySet()
                }
            }
        }
        return WorkspaceInfo(
            workspace = ws,
            headOid = repo.headOid,
            branch = repo.branch,
            dirty = repo.dirty,
            capabilities = capabilities,
            bytesOnDisk = null,
            error = null
        )
    }

    /**
     * 列出项目下的所有工作区信息。
     */
    suspend fun listWorkspaces(projectId: String): List<WorkspaceInfo> {
        return workspaceStore.listWorkspaces(projectId).map { getWorkspaceInfo(it) }
    }

    /**
     * 获取会话当前绑定的工作区信息。
     */
    suspend fun getConversationWorkspace(sessionId: String): ConversationWorkspaceInfo {
        val session = sessionStore.get(sessionId)
            ?: throw MederiNotFoundException("Session $sessionId not found")
        val wsId = session.workspaceId
            ?: throw MederiNotFoundException("Session $sessionId has no bound workspace")
        val activeWs = workspaceStore.getWorkspace(wsId)
            ?: throw MederiNotFoundException("Workspace $wsId not found")
        val activeInfo = getWorkspaceInfo(activeWs)
        val associatedInfo = session.associatedWorktreeId?.let { assocId ->
            workspaceStore.getWorkspace(assocId)?.let { getWorkspaceInfo(it) }
        }
        // T09：RECOVERY_REQUIRED 操作对会话仍须保持可操作（崩溃恢复后快照/getConversationWorkspace 均须暴露），
        // 故活跃态词表复用 [activeOperationStates]（含 RECOVERY_REQUIRED）。
        val op = workspaceStore.listOperations(activeWs.projectId).firstOrNull {
            ((it.sessionId == sessionId) ||
             (it.workspaceId == wsId && (it.sessionId == null || it.sessionId == sessionId)) ||
             (it.sourceWorkspaceId == wsId && it.sessionId == sessionId) ||
             (it.targetWorkspaceId == wsId && it.sessionId == sessionId)) &&
            (it.state in activeOperationStates ||
                (it.workspaceId == wsId && it.kind == WorkspaceOperationKind.CREATE &&
                    it.state == WorkspaceOperationState.FAILED &&
                    activeWs.state in setOf(WorkspaceState.REQUESTED, WorkspaceState.PREPARING)))
        }
        return ConversationWorkspaceInfo(active = activeInfo, associated = associatedInfo, operation = op)
    }

    /**
     * 在工作区创建并切换到新分支。
     */
    suspend fun createWorkspaceBranch(workspaceId: String, input: CreateWorkspaceBranchInput): WorkspaceInfo {
        val ws = workspaceStore.getWorkspace(workspaceId)
            ?: throw MederiNotFoundException("Workspace $workspaceId not found")
        if (ws.generation != input.expectedGeneration) {
            throw MederiWorkspaceException(WorkspaceErrorCode.STALE_OPERATION, "Generation mismatch: expected ${input.expectedGeneration}, actual ${ws.generation}")
        }
        val name = input.name
        if (name.isBlank() || name.startsWith("-") || name.startsWith("/") || name.endsWith("/") ||
            name.endsWith(".lock") || name.contains("..") || name.contains(" ") || name.contains("~") ||
            name.contains("^") || name.contains(":") || name.contains("?") || name.contains("*") ||
            name.contains("[") || name.contains("\\")
        ) {
            throw MederiWorkspaceException(WorkspaceErrorCode.UNSUPPORTED_REPOSITORY_STATE, "Invalid branch name: $name")
        }

        val checkoutDir = File(ws.checkoutRoot)
        val verify = gitDriver.runGit(checkoutDir.absolutePath, listOf("rev-parse", "--verify", "refs/heads/$name"))
        if (verify.exitCode == 0) {
            throw MederiWorkspaceException(WorkspaceErrorCode.UNSUPPORTED_REPOSITORY_STATE, "Branch '$name' already exists")
        }

        val gitCommonDir = ws.gitCommonDirectory ?: gitDriver.getGitCommonDir(ws.checkoutRoot) ?: ws.checkoutRoot
        return leaseManager.withManagementIntent(gitCommonDir, listOf(ws.checkoutRoot), permitOperationId = "branch_$name") { _ ->
            val curHeadRes = gitDriver.runGit(checkoutDir.absolutePath, listOf("rev-parse", "--verify", "HEAD^{commit}"))
            if (curHeadRes.exitCode != 0) {
                throw MederiWorkspaceException(WorkspaceErrorCode.UNSUPPORTED_REPOSITORY_STATE, "Cannot resolve HEAD commit")
            }
            val curHead = curHeadRes.stdoutString.trim()
            if (input.expectedHead.isNotBlank() && curHead != input.expectedHead) {
                throw MederiWorkspaceException(WorkspaceErrorCode.STALE_OPERATION, "HEAD mismatch: expected ${input.expectedHead}, actual $curHead")
            }

            val res = gitDriver.runGit(checkoutDir.absolutePath, listOf("checkout", "-b", name, curHead))
            if (res.exitCode != 0) {
                throw MederiWorkspaceException(WorkspaceErrorCode.UNSUPPORTED_REPOSITORY_STATE, "Failed to create branch: ${res.stderr}")
            }

            val updated = ws.copy(generation = ws.generation + 1, taskBranchName = name)
            workspaceStore.saveWorkspace(updated)
            getWorkspaceInfo(updated)
        }
    }

    /**
     * 获取指定操作。
     */
    suspend fun getOperation(id: String): WorkspaceOperation {
        return workspaceStore.getOperation(id)
            ?: throw MederiNotFoundException("Operation $id not found")
    }

    /**
     * 列出项目下的所有操作。
     */
    suspend fun listWorkspaceOperations(projectId: String): List<WorkspaceOperation> {
        return workspaceStore.listOperations(projectId)
    }

    /**
     * 列出工作区文件。
     */
    suspend fun listWorkspaceFiles(
        workspaceId: String,
        path: String = "",
        area: WorkspaceFileArea = WorkspaceFileArea.PROJECT
    ): List<WorkspaceFileEntry> {
        val ws = workspaceStore.getWorkspace(workspaceId)
            ?: throw MederiNotFoundException("Workspace $workspaceId not found")
        val root = if (area == WorkspaceFileArea.PROJECT) {
            File(ws.workingDirectory).canonicalFile
        } else {
            val proj = projectStore?.get(ws.projectId)
            if (proj != null) {
                File(proj.directory, ".mederi").canonicalFile
            } else {
                File(ws.checkoutRoot, ".mederi").canonicalFile
            }
        }

        val target = resolveFileTarget(root, path)
        enforceReadPolicy(ws.projectId, root, target)
        if (!target.exists() || !target.isDirectory) return emptyList()

        // 列举同样应用读策略：构建一次 policy，过滤掉被黑名单拒绝的条目（含指向拒绝路径的符号链接）。
        val remapped = remapDeniedForRoot(ws.projectId, root)
        val policy = if (remapped.isEmpty()) null else SandboxPaths(
            projectDirs = listOf(root.absolutePath),
            extraWritablePaths = emptyList(),
            tempDirs = emptyList(),
            deniedPaths = remapped,
        )

        return target.listFiles()?.sortedBy { it.name }?.mapNotNull { file ->
            val canonical = file.canonicalFile
            if (policy != null && !policy.allowsRead(canonical.toPath())) return@mapNotNull null
            val kind = when {
                Files.isSymbolicLink(file.toPath()) -> WorkspaceFileKind.SYMLINK
                file.isDirectory -> WorkspaceFileKind.DIRECTORY
                else -> WorkspaceFileKind.FILE
            }
            val rel = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            WorkspaceFileEntry(
                name = file.name,
                relativePath = rel,
                kind = kind,
                size = if (kind == WorkspaceFileKind.FILE) file.length() else null
            )
        } ?: emptyList()
    }

    /**
     * 读取工作区文件内容。
     */
    suspend fun readWorkspaceFile(
        workspaceId: String,
        path: String,
        offset: Long = 0,
        maxBytes: Int = 262144,
        area: WorkspaceFileArea = WorkspaceFileArea.PROJECT
    ): WorkspaceFileContent {
        val ws = workspaceStore.getWorkspace(workspaceId)
            ?: throw MederiNotFoundException("Workspace $workspaceId not found")
        val root = if (area == WorkspaceFileArea.PROJECT) {
            File(ws.workingDirectory).canonicalFile
        } else {
            val proj = projectStore?.get(ws.projectId)
            if (proj != null) {
                File(proj.directory, ".mederi").canonicalFile
            } else {
                File(ws.checkoutRoot, ".mederi").canonicalFile
            }
        }

        val target = resolveFileTarget(root, path)
        enforceReadPolicy(ws.projectId, root, target)
        if (!target.exists() || !target.isFile) {
            throw MederiNotFoundException("File not found: $path")
        }

        val readBytes = maxBytes.coerceIn(1, 1048576)
        val totalLen = target.length()
        if (offset >= totalLen) {
            return WorkspaceFileContent(
                relativePath = path,
                text = "",
                mime = "text/plain",
                truncated = false,
                nextOffset = offset
            )
        }

        val bytes = java.io.RandomAccessFile(target, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(minOf(readBytes.toLong(), totalLen - offset).toInt())
            val n = raf.read(buf)
            if (n <= 0) ByteArray(0) else if (n < buf.size) buf.copyOf(n) else buf
        }

        val isBinary = bytes.take(512).any { it == 0.toByte() }
        val mime = if (isBinary) "application/octet-stream" else "text/plain"
        val text = if (isBinary) null else String(bytes, Charsets.UTF_8)
        val nextOffset = offset + bytes.size
        val truncated = nextOffset < totalLen

        return WorkspaceFileContent(
            relativePath = path,
            text = text,
            mime = mime,
            truncated = truncated,
            nextOffset = nextOffset
        )
    }

    /**
     * 列出项目环境配置。
     */
    suspend fun listProjectEnvironments(projectId: String, workspaceId: String? = null): List<ProjectEnvironment> {
        val root = if (workspaceId != null) {
            val ws = workspaceStore.getWorkspace(workspaceId)
                ?: throw MederiNotFoundException("Workspace $workspaceId not found")
            if (ws.projectId != projectId) {
                throw MederiWorkspaceException(WorkspaceErrorCode.PATH_DENIED, "Workspace $workspaceId does not belong to project $projectId")
            }
            File(ws.workingDirectory)
        } else {
            val proj = projectStore?.get(projectId)
                ?: throw MederiNotFoundException("Project $projectId not found")
            File(proj.directory)
        }

        val envDir = File(root, ".mederi/environments")
        if (!envDir.exists() || !envDir.isDirectory) return emptyList()

        return envDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString(ProjectEnvironment.serializer(), f.readText()) }.getOrNull()
            } ?: emptyList()
    }

    /**
     * 保存项目环境配置。
     */
    suspend fun saveProjectEnvironment(
        projectId: String,
        environment: ProjectEnvironment,
        expectedVersion: Long,
        workspaceId: String? = null
    ): ProjectEnvironment {
        val root = if (workspaceId != null) {
            val ws = workspaceStore.getWorkspace(workspaceId)
                ?: throw MederiNotFoundException("Workspace $workspaceId not found")
            if (ws.projectId != projectId) {
                throw MederiWorkspaceException(WorkspaceErrorCode.PATH_DENIED, "Workspace $workspaceId does not belong to project $projectId")
            }
            File(ws.workingDirectory)
        } else {
            val proj = projectStore?.get(projectId)
                ?: throw MederiNotFoundException("Project $projectId not found")
            File(proj.directory)
        }

        val envDir = File(root, ".mederi/environments").apply { mkdirs() }
        val targetFile = File(envDir, "${environment.id}.json")
        if (targetFile.exists()) {
            val old = runCatching { json.decodeFromString(ProjectEnvironment.serializer(), targetFile.readText()) }.getOrNull()
            if (old != null && old.version != expectedVersion) {
                throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Environment version mismatch: expected $expectedVersion, actual ${old.version}")
            }
        }

        val updated = environment.copy(version = expectedVersion + 1)
        targetFile.writeText(json.encodeToString(ProjectEnvironment.serializer(), updated))
        return updated
    }

    /**
     * 运行工作区环境动作。
     */
    /**
     * R01: 为工作区操作构造真实权限上下文——SETUP/ACTION 脚本不得在 permissions=null 下裸跑。
     * 使用 sandboxManager 的项目级设置（deniedPaths/模式/产物根），让 ShellTools/CommandSandbox 后端生效。
     */
    private suspend fun buildWorkspacePermissions(ws: Workspace, explicitConversationId: String? = null): xyz.mederi.tools.sandbox.ExecutionPermissions? {
        val manager = sandboxManager ?: return null
        val projectDir = projectStore?.get(ws.projectId)?.directory ?: ws.checkoutRoot
        val artifactDir = java.io.File(projectDir, ".mederi").canonicalPath
        val verifiedRoot = VerifiedWorkspaceRoot.create(
            workspaceId = ws.id,
            projectId = ws.projectId,
            checkoutRoot = ws.checkoutRoot,
            projectRelativePath = ws.projectRelativePath,
            artifactDirectory = artifactDir,
            gitCommonDirectory = ws.gitCommonDirectory,
            generation = ws.generation,
        )
        // 旧调用方只有一个实际绑定时可确定归属。无绑定或共享副本上的歧义由审批入口明确拒绝。
        val realSessionId = explicitConversationId ?: sessionStore.list().singleOrNull {
            it.projectId == ws.projectId && it.workspaceId == ws.id
        }?.id
        return xyz.mederi.tools.sandbox.ExecutionPermissions(
            manager = manager,
            directories = listOf(ws.workingDirectory),
            conversationId = realSessionId,
            projectId = ws.projectId,
            verifiedWorkspaceRootProvider = { verifiedRoot },
        )
    }

    suspend fun runWorkspaceAction(workspaceId: String, input: WorkspaceActionInput): WorkspaceOperation {
        val ws = workspaceStore.getWorkspace(workspaceId)
            ?: throw MederiNotFoundException("Workspace $workspaceId not found")
        val envs = listProjectEnvironments(ws.projectId, workspaceId)
        val env = envs.find { it.id == input.environmentId }
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Environment ${input.environmentId} not found")
        if (env.version != input.environmentVersion) {
            throw MederiWorkspaceException(WorkspaceErrorCode.ENVIRONMENT_CHANGED, "Environment version mismatch: expected ${input.environmentVersion}, actual ${env.version}")
        }
        // R01: 生产入口必须传真实权限，不允许 permissions=null 裸跑
        val initiator = input.conversationId
        if (initiator != null) {
            val session = sessionStore.get(initiator)
            if (session == null || session.projectId != ws.projectId || session.workspaceId != ws.id) {
                throw MederiWorkspaceException(WorkspaceErrorCode.INVALID_REQUEST, "Action conversation is not bound to this workspace")
            }
        }
        val permissions = buildWorkspacePermissions(ws, initiator)
        return environmentService.executeAction(
            workspace = ws,
            actionInput = input,
            environment = env,
            permissions = permissions
        )
    }

    /**
     * 读取工作区操作日志。
     */
    fun getWorkspaceOperationLog(id: String, afterOffset: Long = 0, maxBytes: Int = 262144): LogChunk {
        return environmentService.readLog(id, afterOffset, maxBytes)
    }
}
