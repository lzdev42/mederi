package xyz.mederi.store

import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.domain.model.TodoItem
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * Session 元数据存储接口。
 *
 * 管理 sessions 表的 CRUD，不包含对话历史（历史由 HistoryStore 管理）。
 */
interface SessionStore {

    /**
     * 查询所有会话（按更新时间降序）。
     */
    suspend fun list(): List<Session>

    /**
     * 按 ID 查询会话。
     */
    suspend fun get(id: String): Session?

    /**
     * 插入会话。
     */
    suspend fun insert(session: Session)

    /**
     * 更新会话状态/标题。
     */
    suspend fun update(id: String, status: SessionStatus? = null, title: String? = null)

    /**
     * 更新会话的 Agent 配置（agentMode / aiModel / reasoningLevel / apiKeyId）。
     * 各参数 null = 不修改对应列（与 SQL 侧 COALESCE 语义一致）。
     */
    suspend fun updateAgentConfig(
        id: String,
        agentMode: AgentMode? = null,
        aiModel: AIModel? = null,
        reasoningLevel: ReasoningLevel? = null,
        apiKeyId: String? = null
    )

    /**
     * 全量覆盖写会话 compose state（agentMode / aiModel / reasoningLevel / apiKeyId / draftText）。
     * 与 [updateAgentConfig] 不同：不做 COALESCE，null 直接写 null（清空语义）。
     * 用于输入框设置统一保存（UI 任意设置变化时全量写当前值）。
     */
    suspend fun updateComposeState(
        id: String,
        agentMode: AgentMode,
        aiModel: AIModel?,
        reasoningLevel: ReasoningLevel?,
        apiKeyId: String?,
        draftText: String
    )

    /**
     * 更新会话的 todo 列表（整体替换；无 Plan 任务的轻量进度跟踪）。
     * 真理源 = sessions.todos 列；事件/快照/提示词段都是它的投影。
     */
    suspend fun updateTodos(id: String, todos: List<TodoItem>)

    /**
     * 更新会话已激活 skill 列表（整体替换；会话级激活力真理源 = sessions.active_skills 列）。
     * 激活的 skill 全文由 TurnExecutor 注入系统提示词，不进对话历史。
     */
    suspend fun updateActiveSkills(id: String, activeSkills: List<String>)

    /**
     * 更新会话的工作区绑定。
     */
    suspend fun updateWorkspaceBinding(id: String, workspaceId: String?, associatedWorktreeId: String?)

    /**
     * 更新会话归档状态。
     */
    suspend fun setArchived(id: String, archivedAt: String?)

    /**
     * 更新会话固定状态。
     */
    suspend fun setPinned(id: String, pinned: Boolean)

    /** 仅写子代理角色覆盖，保留会话其他字段。 */
    suspend fun updateSubagentConfigs(id: String, configs: Map<xyz.mederi.domain.model.SubagentRole, xyz.mederi.domain.model.SubagentModelConfig>)

    /** null 恢复系统默认并发上限。 */
    suspend fun updateSubagentMaxConcurrent(id: String, count: Int?)

    /**
     * 删除会话。
     */
    suspend fun delete(id: String)

    /** 无持久化会话注册（子代理会话）：注册后该会话纯内存、永不落盘。默认 no-op（实现自选持久化策略）。 */
    fun registerEphemeral(sessionId: String) {}
}
