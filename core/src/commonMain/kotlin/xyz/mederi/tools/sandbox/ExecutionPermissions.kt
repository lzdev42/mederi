package xyz.mederi.tools.sandbox

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.mederi.api.exception.MederiStateException
import xyz.mederi.workspace.VerifiedWorkspaceRoot
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 工具调用携带执行者及父会话身份；子代理共享 manager，授权卡始终发到父会话。 */
class ExecutionPermissions(
    val manager: SandboxManager,
    private val directories: List<String>,
    val conversationId: String?,
    private val executionSessionId: String = conversationId.orEmpty(),
    private val projectId: String,
    val verifiedWorkspaceRootProvider: () -> VerifiedWorkspaceRoot? = { null },
) {
    constructor(
        manager: SandboxManager,
        directories: List<String>,
        conversationId: String,
        executionSessionId: String = conversationId,
        projectId: String,
        verifiedWorkspaceRoot: VerifiedWorkspaceRoot?,
    ) : this(manager, directories, conversationId, executionSessionId, projectId, { verifiedWorkspaceRoot })

    data class Grant(
        val settings: SandboxSettings,
        val generation: Long,
        val paths: List<Path> = emptyList(),
        val workspaceGeneration: Long? = null,
        val workspaceId: String? = null,
    )

    fun resolvePath(raw: String): Path {
        require(raw.isNotBlank()) { "File path must not be empty" }
        val input = File(raw)
        if (input.isAbsolute) return input.canonicalFile.toPath()
        val ws = verifiedWorkspaceRootProvider()
        if (ws != null) {
            val file = File(ws.workingDirectory.toFile(), raw)
            return file.canonicalFile.toPath()
        }
        val file = directories.map { File(it, raw) }.firstOrNull { it.exists() }
            ?: File(directories.firstOrNull() ?: error("No project directory configured"), raw)
        return file.canonicalFile.toPath()
    }

    suspend fun authorizeWrite(tool: String, paths: List<Path>): Grant {
        val (settings, generation) = manager.snapshot(projectId)
        val ws = verifiedWorkspaceRootProvider()
        ws?.verifyIdentity()
        val canonical = paths.map { it.toFile().canonicalFile.toPath() }.distinct()
        val readPolicy = readPolicy(settings)
        if (canonical.any { !readPolicy.allowsRead(it) }) throw MederiStateException("Path is denied by this project's blacklist; approval cannot override it.")
        if (settings.mode == ExecutionPermissionMode.FULL_ACCESS) {
            return Grant(settings, generation, canonical, ws?.generation, ws?.workspaceId).also { checkGrant(it) }
        }
        val policy = SandboxPaths(
            directories,
            settings.extraWritablePaths,
            protectedDirectories = manager.protectedDirectories,
            deniedPaths = settings.deniedPaths,
            verifiedWorkspaceRoot = ws
        )
        val needsApproval = canonical.any { !policy.allowsWrite(it) }
        val approvedGeneration = if (needsApproval) manager.requestApproval(projectId, ExecutionApprovalRequest(
            id = UUID.randomUUID().toString(), conversationId = requireApprovalConversation(), executionSessionId = executionSessionId,
            toolName = tool, paths = canonical.map { it.toString() }, access = "outside",
        ), generation) else generation
        return Grant(settings, approvedGeneration, canonical, ws?.generation, ws?.workspaceId).also { checkGrant(it) }
    }

    suspend fun authorizeCommand(command: String, cwd: String, sandbox: String, tool: String = "bash"): Grant {
        require(sandbox in listOf("default", "network", "off")) { "sandbox must be default, network, or off" }
        val (settings, version) = manager.snapshot(projectId)
        val ws = verifiedWorkspaceRootProvider()
        ws?.verifyIdentity()
        val allowedByRule = CommandRules.allowsEscalation(command, settings)
        val approvedVersion = if (settings.mode != ExecutionPermissionMode.FULL_ACCESS && sandbox != "default" && !allowedByRule) {
            manager.requestApproval(projectId, ExecutionApprovalRequest(
                id = UUID.randomUUID().toString(), conversationId = requireApprovalConversation(), executionSessionId = executionSessionId,
                toolName = tool, command = command, cwd = cwd,
                access = if (sandbox == "network") "network" else "unsandboxed",
            ), version)
        } else version
        return Grant(settings, approvedVersion, workspaceGeneration = ws?.generation, workspaceId = ws?.workspaceId).also { checkGrant(it) }
    }

    private fun requireApprovalConversation(): String = conversationId
        ?: throw MederiStateException("This operation needs execution approval. Run it from a conversation bound to the workspace.")

    suspend fun readPolicy(): SandboxPaths = readPolicy(manager.getSettings(projectId))

    private fun readPolicy(settings: SandboxSettings): SandboxPaths {
        val ws = verifiedWorkspaceRootProvider()
        return SandboxPaths(
            projectDirs = directories,
            extraWritablePaths = emptyList(),
            tempDirs = emptyList(),
            deniedPaths = settings.deniedPaths,
            verifiedWorkspaceRoot = ws
        )
    }

    suspend fun checkRead(path: Path) {
        currentCoroutineContext().ensureActive()
        if (!readPolicy().allowsRead(path)) throw MederiStateException("Path is denied by this project's blacklist.")
    }

    suspend fun checkGrant(grant: Grant) {
        currentCoroutineContext().ensureActive()
        validateGrant(grant)
    }

    fun validateGrant(grant: Grant) {
        if (manager.generation(projectId) != grant.generation) throw MederiStateException("Execution permissions changed before execution; retry the operation.")
        val currentWs = verifiedWorkspaceRootProvider()
        if (grant.workspaceGeneration != null) {
            if (currentWs == null || currentWs.generation != grant.workspaceGeneration || currentWs.workspaceId != grant.workspaceId) {
                throw MederiStateException("Workspace changed or migrated after authorization; old grant is invalidated.")
            }
        }
        grant.paths.forEach { path ->
            require(path.toFile().canonicalFile.toPath() == path) { "Path changed after authorization: $path" }
            // 拒绝授权后被替换的符号链接；canonical 原路径中的 /tmp 别名已在上面消解。
            var current: Path? = path
            while (current != null) {
                require(!Files.isSymbolicLink(current)) { "Path changed to a symbolic link: $current" }
                current = current.parent
            }
        }
    }
}
