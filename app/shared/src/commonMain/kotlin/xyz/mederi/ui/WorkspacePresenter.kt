package xyz.mederi.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.mederi.core.contract.dto.ConversationState
import xyz.mederi.core.contract.models.RepositoryInfo
import xyz.mederi.core.contract.models.WorkspaceInfo
import xyz.mederi.core.contract.models.WorkspaceKind
import xyz.mederi.core.contract.models.WorkspaceOperation
import xyz.mederi.core.contract.models.WorkspaceSelection

/**
 * WorkspacePresenter: 管理按 session/workspace 投影，operation 只读。
 * 针对未创建对话管理 draft WorkspaceSelection；创建之后状态以 Core Session 为真理源。
 */
class WorkspacePresenter {
    var draftSelection by mutableStateOf(WorkspaceSelection())
        private set

    fun updateDraftKind(kind: WorkspaceKind) {
        draftSelection = draftSelection.copy(kind = kind)
    }

    fun updateDraftBaseRef(baseRef: String?) {
        draftSelection = draftSelection.copy(baseRef = baseRef)
    }

    fun updateDraftCarryLocalChanges(carry: Boolean) {
        draftSelection = draftSelection.copy(carryLocalChanges = carry)
    }

    fun updateDraftEnvironmentId(environmentId: String?) {
        draftSelection = draftSelection.copy(environmentId = environmentId)
    }

    fun resetDraft() {
        draftSelection = WorkspaceSelection(
            kind = WorkspaceKind.LOCAL,
            baseRef = null,
            carryLocalChanges = false,
            environmentId = null
        )
    }

    /**
     * 解析给定会话/草稿的当前生效工作区信息。
     * 未创建会话返回 null（由 draftSelection 表现）；已有会话以 snapshot.workspaceInfo 为唯一真理源。
     */
    fun resolveActiveWorkspace(
        conversationId: String?,
        snapshot: ConversationState?
    ): WorkspaceInfo? {
        if (conversationId == null || snapshot == null) return null
        if (snapshot.conversation.id != conversationId) return null
        return snapshot.workspaceInfo?.active
    }

    /**
     * 解析当前会话活跃的操作（只读，且核验所属 sessionId 与 projectId）。
     * 若 owner 为 null 或与当前会话不匹配，严禁重定向或借用操作。
     */
    fun resolveActiveOperation(
        conversationId: String?,
        snapshot: ConversationState?
    ): WorkspaceOperation? {
        if (conversationId == null || snapshot == null) return null
        if (snapshot.conversation.id != conversationId) return null
        val op = snapshot.activeWorkspaceOperation ?: snapshot.workspaceInfo?.operation ?: return null

        // 核验操作所属会话：若 sessionId 为空或不等于当前会话，严禁在当前会话展示或借用
        if (op.sessionId == null || op.sessionId != conversationId) {
            return null
        }
        // 核验项目归属
        if (op.projectId != snapshot.conversation.projectId) {
            return null
        }
        return op
    }

    /**
     * 计算选择器的短标签与完整悬停提示信息。
     */
    fun resolveSelectorLabels(
        conversationId: String?,
        snapshot: ConversationState?,
        draft: WorkspaceSelection,
        repo: RepositoryInfo?
    ): Pair<String, String> {
        val active = resolveActiveWorkspace(conversationId, snapshot)
        return if (active != null) {
            if (active.workspace.kind == WorkspaceKind.LOCAL) {
                val branch = active.branch ?: repo?.branch ?: "HEAD"
                "Local" to "Local ($branch)"
            } else {
                val branch = active.branch ?: active.workspace.taskBranchName ?: "worktree"
                branch to "Worktree ($branch)"
            }
        } else {
            if (draft.kind == WorkspaceKind.LOCAL) {
                val branch = repo?.branch ?: "HEAD"
                val env = draft.environmentId ?: "default"
                "Local" to "Local ($branch) - env: $env"
            } else {
                val branch = draft.baseRef ?: repo?.branch ?: "HEAD"
                val env = draft.environmentId ?: "default"
                val carry = if (draft.carryLocalChanges) "with uncommitted changes" else "clean"
                branch to "Worktree ($branch, $carry) - env: $env"
            }
        }
    }
}
