package xyz.mederi.store.db

import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.domain.model.SubagentModelConfig
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.domain.model.TodoItem
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemorySessionStore
import xyz.mederi.store.SessionStore
import java.util.concurrent.ConcurrentHashMap

/**
 * 无持久化会话模式（B 的存储层抽象）：
 * 装饰共享存储，[registerEphemeral] 登记的会话路由到内嵌内存后端（永不落 delegate/DB），
 * 其余全部转发 delegate（共享持久存储）。
 *
 * 子代理 turn 经 TurnExecutor 包装注入 SubagentRunnerImpl——业务代码不再散落自建内存存储；
 * turn 结束时 [delete] 清理（overlay 回收 + 登记注销，对齐旧实现的 GC 生命周期）。
 * [InMemoryHistoryStore] / [InMemorySessionStore] 在此获得正当生产角色：ephemeral 模式的内存后端。
 */
class EphemeralHistoryStore(private val delegate: HistoryStore) : HistoryStore by delegate {

    private val ids = ConcurrentHashMap.newKeySet<String>()
    private val overlay = InMemoryHistoryStore()

    override fun registerEphemeral(sessionId: String) {
        ids.add(sessionId)
    }

    override suspend fun load(sessionId: String): List<Message> =
        if (sessionId in ids) overlay.load(sessionId) else delegate.load(sessionId)

    override suspend fun append(sessionId: String, message: Message) {
        if (sessionId in ids) overlay.append(sessionId, message) else delegate.append(sessionId, message)
    }

    override suspend fun updateMessage(sessionId: String, messageId: String, transform: (Message) -> Message) {
        if (sessionId in ids) overlay.updateMessage(sessionId, messageId, transform)
        else delegate.updateMessage(sessionId, messageId, transform)
    }

    override suspend fun updateHistory(sessionId: String, transform: (List<Message>) -> List<Message>) {
        if (sessionId in ids) overlay.updateHistory(sessionId, transform)
        else delegate.updateHistory(sessionId, transform)
    }

    override suspend fun replace(sessionId: String, messages: List<Message>) {
        if (sessionId in ids) overlay.replace(sessionId, messages) else delegate.replace(sessionId, messages)
    }

    override suspend fun delete(sessionId: String) {
        if (sessionId in ids) {
            overlay.delete(sessionId)
            ids.remove(sessionId) // 登记注销——清理即回收
        } else delegate.delete(sessionId)
    }

    override suspend fun rollbackTo(sessionId: String, seq: Long) {
        if (sessionId in ids) overlay.rollbackTo(sessionId, seq) else delegate.rollbackTo(sessionId, seq)
    }
}

/**
 * [EphemeralHistoryStore] 的 SessionStore 版：ephemeral 会话路由内存后端，其余转发共享存储。
 * list() 不 override——继承 delegate（ephemeral 会话不进会话列表，保持子代理会话隔离语义）。
 */
class EphemeralSessionStore(private val delegate: SessionStore) : SessionStore by delegate {

    private val ids = ConcurrentHashMap.newKeySet<String>()
    private val overlay = InMemorySessionStore()

    override fun registerEphemeral(sessionId: String) {
        ids.add(sessionId)
    }

    override suspend fun get(id: String): Session? =
        if (id in ids) overlay.get(id) else delegate.get(id)

    override suspend fun insert(session: Session) {
        if (session.id in ids) overlay.insert(session) else delegate.insert(session)
    }

    override suspend fun update(id: String, status: SessionStatus?, title: String?) {
        if (id in ids) overlay.update(id, status, title) else delegate.update(id, status, title)
    }

    override suspend fun updateAgentConfig(
        id: String,
        agentMode: AgentMode?,
        aiModel: AIModel?,
        reasoningLevel: ReasoningLevel?,
        apiKeyId: String?
    ) {
        if (id in ids) overlay.updateAgentConfig(id, agentMode, aiModel, reasoningLevel, apiKeyId)
        else delegate.updateAgentConfig(id, agentMode, aiModel, reasoningLevel, apiKeyId)
    }

    override suspend fun updateComposeState(
        id: String,
        agentMode: AgentMode,
        aiModel: AIModel?,
        reasoningLevel: ReasoningLevel?,
        apiKeyId: String?,
        draftText: String
    ) {
        if (id in ids) overlay.updateComposeState(id, agentMode, aiModel, reasoningLevel, apiKeyId, draftText)
        else delegate.updateComposeState(id, agentMode, aiModel, reasoningLevel, apiKeyId, draftText)
    }

    override suspend fun updateTodos(id: String, todos: List<TodoItem>) {
        if (id in ids) overlay.updateTodos(id, todos) else delegate.updateTodos(id, todos)
    }

    override suspend fun updateActiveSkills(id: String, activeSkills: List<String>) {
        if (id in ids) overlay.updateActiveSkills(id, activeSkills) else delegate.updateActiveSkills(id, activeSkills)
    }

    override suspend fun updateWorkspaceBinding(id: String, workspaceId: String?, associatedWorktreeId: String?) {
        if (id in ids) overlay.updateWorkspaceBinding(id, workspaceId, associatedWorktreeId)
        else delegate.updateWorkspaceBinding(id, workspaceId, associatedWorktreeId)
    }

    override suspend fun setArchived(id: String, archivedAt: String?) {
        if (id in ids) overlay.setArchived(id, archivedAt) else delegate.setArchived(id, archivedAt)
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        if (id in ids) overlay.setPinned(id, pinned) else delegate.setPinned(id, pinned)
    }

    override suspend fun updateSubagentConfigs(id: String, configs: Map<SubagentRole, SubagentModelConfig>) {
        if (id in ids) overlay.updateSubagentConfigs(id, configs) else delegate.updateSubagentConfigs(id, configs)
    }

    override suspend fun updateSubagentMaxConcurrent(id: String, count: Int?) {
        if (id in ids) overlay.updateSubagentMaxConcurrent(id, count)
        else delegate.updateSubagentMaxConcurrent(id, count)
    }

    override suspend fun delete(id: String) {
        if (id in ids) {
            overlay.delete(id)
            ids.remove(id) // 登记注销——清理即回收
        } else delegate.delete(id)
    }
}
