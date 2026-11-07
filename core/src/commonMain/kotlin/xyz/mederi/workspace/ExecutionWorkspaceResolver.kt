package xyz.mederi.workspace

import java.io.File
import java.time.Instant
import xyz.mederi.api.exception.MederiNotFoundException
import xyz.mederi.api.exception.MederiWorkspaceException
import xyz.mederi.store.ProjectStore
import xyz.mederi.store.SessionStore
import xyz.mederi.store.WorkspaceStore

/**
 * 执行期工作区解析器。
 * 唯一真理源为 WorkspaceStore / SessionStore。
 * 校验当前工作区状态是否达到 READY，非 READY 或缺失时硬阻断，绝不隐式回退 LOCAL。
 */
class ExecutionWorkspaceResolver(
    private val workspaceStore: WorkspaceStore,
    private val projectStore: ProjectStore,
    private val sessionStore: SessionStore,
    private val gitDriver: GitWorkspaceDriver = GitWorkspaceDriver()
) {
    /**
     * 解析指定会话的执行工作区不可变快照。
     */
    suspend fun resolve(sessionId: String): ExecutionWorkspace {
        val session = sessionStore.get(sessionId)
            ?: throw MederiNotFoundException("Session $sessionId not found")
        val project = projectStore.get(session.projectId)
            ?: throw MederiNotFoundException("Project ${session.projectId} not found")
        val artifactDir = File(project.directory, ".mederi").absolutePath

        val wsId = session.workspaceId
        if (wsId == null) {
            // 旧会话兼容：惰性绑定 LOCAL
            val localWsId = "ws_local_${project.id}"
            var localWs = workspaceStore.getWorkspace(localWsId)
            if (localWs == null) {
                localWs = Workspace(
                    id = localWsId,
                    projectId = project.id,
                    kind = WorkspaceKind.LOCAL,
                    state = WorkspaceState.READY,
                    checkoutRoot = project.directory,
                    projectRelativePath = "",
                    generation = 1,
                    createdAt = Instant.now().toString(),
                    lastUsedAt = Instant.now().toString()
                )
                workspaceStore.saveWorkspace(localWs)
            }
            sessionStore.updateWorkspaceBinding(session.id, localWs.id, null)
            val gitDir = gitDriver.getGitCommonDir(localWs.checkoutRoot)
            return ExecutionWorkspace(
                projectId = project.id,
                workspaceId = localWs.id,
                checkoutRoot = localWs.checkoutRoot,
                workingDirectory = localWs.workingDirectory,
                artifactDirectory = artifactDir,
                gitCommonDirectory = gitDir,
                generation = localWs.generation
            )
        }

        // managed 缺失绝不当 LOCAL
        val ws = workspaceStore.getWorkspace(wsId)
            ?: throw MederiWorkspaceException(WorkspaceErrorCode.WORKSPACE_MISSING, "Workspace $wsId not found")

        if (ws.state != WorkspaceState.READY) {
            throw MederiWorkspaceException(
                WorkspaceErrorCode.WORKSPACE_BUSY,
                "Workspace $wsId is in state ${ws.state}, not READY for execution"
            )
        }

        val checkoutFile = File(ws.checkoutRoot)
        if (!checkoutFile.exists() || !checkoutFile.isDirectory) {
            throw MederiWorkspaceException(
                WorkspaceErrorCode.WORKSPACE_MISSING,
                "Workspace checkout root ${ws.checkoutRoot} does not exist on disk"
            )
        }

        val gitDir = gitDriver.getGitCommonDir(ws.checkoutRoot)
        return ExecutionWorkspace(
            projectId = project.id,
            workspaceId = ws.id,
            checkoutRoot = ws.checkoutRoot,
            workingDirectory = ws.workingDirectory,
            artifactDirectory = artifactDir,
            gitCommonDirectory = gitDir,
            generation = ws.generation
        )
    }

    /** 仅准备阶段可接纳待恢复输入。迁移、归档等其他忙碌状态不会开启准备。 */
    suspend fun isAwaitingPreparation(sessionId: String): Boolean {
        val session = sessionStore.get(sessionId) ?: return false
        val workspace = session.workspaceId?.let { workspaceStore.getWorkspace(it) } ?: return false
        return workspace.projectId == session.projectId &&
            workspace.kind == WorkspaceKind.MANAGED_WORKTREE &&
            workspace.state in setOf(WorkspaceState.REQUESTED, WorkspaceState.PREPARING)
    }

    /**
     * 判断当前会话是否处于可执行状态。
     */
    suspend fun isExecutable(sessionId: String): Boolean {
        return try {
            val session = sessionStore.get(sessionId) ?: return false
            val wsId = session.workspaceId ?: return true
            val ws = workspaceStore.getWorkspace(wsId) ?: return false
            ws.state == WorkspaceState.READY && File(ws.checkoutRoot).isDirectory
        } catch (_: Exception) {
            false
        }
    }
}
