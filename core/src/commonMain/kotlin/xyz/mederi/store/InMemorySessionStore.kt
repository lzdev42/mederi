package xyz.mederi.store

import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.domain.model.TodoItem
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * 仅内存的 SessionStore 实现。
 *
 * 未指定 configDir 时使用。
 */
class InMemorySessionStore : SessionStore {

    private val sessions = mutableMapOf<String, Session>()

    override suspend fun list(): List<Session> =
        sessions.values.sortedByDescending { it.updatedAt }

    override suspend fun get(id: String): Session? = sessions[id]

    override suspend fun insert(session: Session) {
        sessions[session.id] = session
    }

    override suspend fun update(id: String, status: SessionStatus?, title: String?) {
        sessions[id] = sessions[id]?.let { s ->
            s.copy(
                status = status ?: s.status,
                title = title ?: s.title,
                updatedAt = java.time.Instant.now().toString()
            )
        } ?: return
    }

    override suspend fun updateAgentConfig(
        id: String,
        agentMode: AgentMode?,
        aiModel: AIModel?,
        reasoningLevel: ReasoningLevel?,
        apiKeyId: String?
    ) {
        sessions[id] = sessions[id]?.let { s ->
            s.copy(
                agentMode = agentMode ?: s.agentMode,
                aiModel = aiModel ?: s.aiModel,
                reasoningLevel = reasoningLevel ?: s.reasoningLevel,
                apiKeyId = apiKeyId ?: s.apiKeyId,
                updatedAt = java.time.Instant.now().toString()
            )
        } ?: return
    }

    override suspend fun updateTodos(id: String, todos: List<TodoItem>) {
        sessions[id] = sessions[id]?.let { s ->
            s.copy(
                todos = todos,
                updatedAt = java.time.Instant.now().toString()
            )
        } ?: return
    }

    override suspend fun delete(id: String) {
        sessions.remove(id)
    }
}
