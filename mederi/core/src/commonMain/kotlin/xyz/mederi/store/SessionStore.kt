package xyz.mederi.store

import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.domain.model.TodoItem
import xyz.mederi.domain.model.WorkType
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
     * 更新会话的 Agent 配置（agentMode / workType / aiModel / reasoningLevel）。
     */
    suspend fun updateAgentConfig(
        id: String,
        agentMode: AgentMode? = null,
        workType: WorkType? = null,
        aiModel: AIModel? = null,
        reasoningLevel: ReasoningLevel? = null
    )

    /**
     * 更新会话的 todo 列表（整体替换；无 Plan 任务的轻量进度跟踪）。
     * 真理源 = sessions.todos 列；事件/快照/提示词段都是它的投影。
     */
    suspend fun updateTodos(id: String, todos: List<TodoItem>)

    /**
     * 删除会话。
     */
    suspend fun delete(id: String)
}
