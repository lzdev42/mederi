package xyz.mederi.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.channels.BufferOverflow
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.debug.DebugLog
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.FileDiff
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.infrastructure.koog.TurnExecutor
import xyz.mederi.mcp.engine.McpConnector
import xyz.mederi.project.ProjectManager
import xyz.mederi.store.DiffStore
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.RawMessageRecord
import xyz.mederi.store.SessionStore
import xyz.mederi.skills.SkillManager
import xyz.mederi.tools.diff.countChanges
import java.time.Instant
import java.util.UUID

/**
 * SessionManager 默认实现。
 *
 * 持有 [SessionStore] 和 [HistoryStore]，依赖 [ProjectManager]（创建时校验项目存在）。
 *
 * 内部创建 [TurnExecutor] 和事件总线 [MutableSharedFlow]，负责异步执行 Agent loop 并广播事件。
 *
 * @param sessionStore Session 元数据存储。
 * @param historyStore 对话历史存储。
 * @param projectManager 项目管理器。
 * @param providerManager 供应商管理器（供 TurnExecutor 使用）。
 */
class SessionManagerImpl(
    private val sessionStore: SessionStore,
    private val historyStore: HistoryStore,
    private val projectManager: ProjectManager,
    private val providerManager: xyz.mederi.provider.ProviderManager,
    private val diffStore: DiffStore? = null,
    private val eventBus: MutableSharedFlow<MederiEvent> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ),
    private val mcpConnector: McpConnector? = null,
    private val skills: SkillManager? = null
) : SessionManager {

    private val turnExecutor = TurnExecutor(
        sessionStore = sessionStore,
        historyStore = historyStore,
        eventBus = eventBus,
        providerManager = providerManager,
        projectManager = projectManager,
        diffStore = diffStore,
        mcpConnector = mcpConnector,
        skills = skills
    )

    override suspend fun list(): List<Session> = sessionStore.list()

    override suspend fun listByProject(projectId: String): List<Session> =
        sessionStore.list().filter { it.projectId == projectId }

    override suspend fun get(id: String): Session? = sessionStore.get(id)

    override suspend fun require(id: String): Session =
        sessionStore.get(id) ?: throw NoSuchElementException("Session not found: $id")

    override suspend fun rename(id: String, title: String): Session {
        require(title.isNotBlank()) { "Session title cannot be blank" }
        val existing = require(id)
        sessionStore.update(id, title = title)
        return sessionStore.get(id)!!
    }

    override suspend fun create(
        agentConfig: AgentConfig,
        projectId: String,
        title: String,
        env: Map<String, String>
    ): Session {
        // 校验项目存在
        projectManager.require(projectId)

        val now = Instant.now().toString()
        val session = Session(
            id = "sess_${UUID.randomUUID().toString().take(8)}",
            projectId = projectId,
            title = title.ifEmpty { "New Session" },
            status = SessionStatus.IDLE,
            agentMode = agentConfig.agentMode,
            workType = agentConfig.workType,
            aiModel = agentConfig.aiModel,
            reasoningLevel = agentConfig.reasoningLevel,
            env = env,
            createdAt = now,
            updatedAt = now
        )
        sessionStore.insert(session)
        eventBus.emit(
            MederiEvent(
                type = EventType.SESSION_CREATED,
                sessionId = session.id,
                timestamp = now
            )
        )
        return session
    }

    override suspend fun delete(id: String) {
        DebugLog.event("SessionMgr", "delete: sessionId=$id")
        turnExecutor.abortAndJoin(id)
        sessionStore.delete(id)
        historyStore.delete(id)
        diffStore?.delete(id)
    }

    override suspend fun abort(id: String) {
        turnExecutor.abort(id)
    }

    override suspend fun abortAndJoin(id: String) {
        turnExecutor.abortAndJoin(id)
    }

    override suspend fun rollbackToMessage(id: String, messageId: String) {
        DebugLog.event("SessionMgr", "rollbackToMessage: sessionId=$id, messageId=$messageId")
        // 必须等旧 turn 完全死透（cancel + join）再截断历史：abort() 只发取消信号不等死，
        // 旧 turn 的收尾落库（ChatMemory store 回写 / 增量持久化）若发生在 replace 之后，
        // 被删的旧消息会被原样重新落库，与重发消息叠加造成整个会话重复
        turnExecutor.abortAndJoin(id)
        val msgs = historyStore.load(id)
        val targetIndex = msgs.indexOfFirst { it.id == messageId }
        if (targetIndex < 0) {
            // 目标消息不存在（已删/已回滚/数据不一致）：不能静默 no-op——调用方会照常
            // 重发，"截断未发生 + 重发"= 历史重复。必须显式失败让调用方感知
            throw NoSuchElementException("Message not found in session $id: $messageId")
        }
        historyStore.replace(id, msgs.take(targetIndex))
        val now = Instant.now().toString()
        eventBus.emit(
            MederiEvent(
                type = EventType.MESSAGE_COMPLETED,
                sessionId = id,
                messageId = messageId,
                timestamp = now
            )
        )
    }

    override suspend fun sendMessage(id: String, request: SendMessageRequest) {
        DebugLog.event("SessionMgr", "sendMessage: sessionId=$id, agentMode=${request.agentConfig.agentMode}, parts=${request.parts.size}")
        turnExecutor.sendMessage(id, request)
    }

    override suspend fun resolveQuestion(id: String, questionId: String, answers: List<List<String>>) {
        turnExecutor.resolveQuestion(id, questionId, answers)
    }

    override suspend fun resolvePlanApproval(id: String, planId: String, approved: Boolean) {
        turnExecutor.resolvePlanApproval(id, planId, approved)
    }

    override suspend fun compressHistory(id: String) {
        turnExecutor.compressHistory(id)
    }

    override suspend fun listMessages(id: String): List<Message> =
        historyStore.load(id)

    override suspend fun listRawMessages(id: String): List<RawMessageRecord> =
        historyStore.listRaw(id)

    override suspend fun getMessage(id: String, messageId: String): Message {
        return historyStore.load(id).firstOrNull { it.id == messageId }
            ?: throw NoSuchElementException("Message not found: $messageId")
    }

    override suspend fun getFileDiffs(id: String, messageId: String?): List<FileDiff> {
        val diff = diffStore?.get(id, messageId) ?: return emptyList()
        return diff.changes.map { change ->
            val (additions, deletions) = countChanges(change.before, change.after)
            FileDiff(
                filePath = change.path,
                before = change.before ?: "",
                after = change.after ?: "",
                additions = additions,
                deletions = deletions
            )
        }
    }

    override fun events(sessionId: String): Flow<MederiEvent> {
        DebugLog.event("SessionMgr", "events() subscribed: sessionId=$sessionId")
        return eventBus.filter { it.sessionId == sessionId }
    }

    override fun events(): Flow<MederiEvent> = eventBus
}
