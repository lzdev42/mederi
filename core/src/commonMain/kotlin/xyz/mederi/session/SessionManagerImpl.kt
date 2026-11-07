package xyz.mederi.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.channels.BufferOverflow
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.api.exception.MederiNotFoundException
import xyz.mederi.debug.DebugLog
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.FileDiff
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.infrastructure.koog.TurnExecutor
import xyz.mederi.infrastructure.koog.aiViewContextUsedTokens
import xyz.mederi.mcp.engine.McpConnector
import xyz.mederi.project.ProjectManager
import xyz.mederi.store.DiffStore
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.RawMessageRecord
import xyz.mederi.store.SessionStore
import xyz.mederi.skills.SkillManager
import xyz.mederi.skills.domain.SkillInfo
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
    private val sandboxManager: xyz.mederi.tools.sandbox.SandboxManager = xyz.mederi.tools.sandbox.SandboxManager(eventBus = eventBus),
    private val mcpConnector: McpConnector? = null,
    private val skills: SkillManager? = null,
    private val subagentConfigManager: xyz.mederi.tools.subagent.SubagentConfigManager? = null,
    private val compactionThresholdPercent: () -> Int = { 70 },
    /**
     * 宿主可注入浏览器任务运行目录，经 [TurnExecutor] 转交 BrowserTaskManager。
     */
    private val browserRuntimeProvider: (suspend () -> xyz.mederi.browser.BrowserRuntimeDirs?)? = null,
    /**
     * 执行期工作区解析器（生产装配注入）。非 null 时，TurnExecutor 解析会话绑定的工作区并以其实际
     * checkoutRoot/workingDirectory 执行，而非回退 `project.directory`。RF-02：managed 会话不再执行在原项目。
     */
    private val workspaceResolver: xyz.mederi.workspace.ExecutionWorkspaceResolver? = null,
    /** RF-02：会话级工作区准备回调（WorkspaceManager::prepareSessionWorkspace）。首次 send 命中非 READY 的 REQUESTED 工作区时先准备。 */
    private val workspacePreparer: (suspend (String) -> Boolean)? = null,
    /** LLM 传输实现可注入；运行时调度与测试共用同一真实 Koog 工具循环（RF-02 集成测试注入确定性 fake LLM）。 */
    private val llmClientFactory: (xyz.mederi.provider.domain.model.Provider, String) -> ai.koog.prompt.executor.clients.LLMClient = xyz.mederi.provider.infrastructure.koog.KoogClientFactory::create,
    /** RF-07：工作区执行租约（生产装配注入 WorkspaceManager.leaseManager；null = 不参与租约协调）。 */
    private val leaseManager: xyz.mederi.workspace.WorkspaceLeaseManager? = null,
) : SessionManager {

    private val turnExecutor = TurnExecutor(
        sandboxManager = sandboxManager,
        sessionStore = sessionStore,
        historyStore = historyStore,
        eventBus = eventBus,
        providerManager = providerManager,
        projectManager = projectManager,
        diffStore = diffStore,
        mcpConnector = mcpConnector,
        skills = skills,
        subagentConfigManager = subagentConfigManager,
        compactionThresholdPercent = compactionThresholdPercent,  // 新增
        browserRuntimeProvider = browserRuntimeProvider,
        workspaceResolver = workspaceResolver,
        workspacePreparer = workspacePreparer,
        llmClientFactory = llmClientFactory,
        leaseManager = leaseManager,
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
        val msgs = historyStore.load(id)
        val targetIndex = msgs.indexOfFirst { it.id == messageId }
        if (targetIndex < 0) {
            // 目标消息不存在（已删/已回滚/数据不一致）：不能静默 no-op——调用方会照常
            // 重发，"截断未发生 + 重发"= 历史重复。必须显式失败让调用方感知
            throw NoSuchElementException("Message not found in session $id: $messageId")
        }
        val remainingMsgs = msgs.take(targetIndex)

        // 提取保留历史中所有被创建或提及的子代理 ID
        val keptAgentIds = mutableSetOf<String>()
        val subagentRegex = Regex("""\bsub_[a-zA-Z0-9]{8}\b""")
        for (msg in remainingMsgs) {
            for (part in msg.parts) {
                when (part) {
                    is MessagePart.Text -> subagentRegex.findAll(part.text).forEach { keptAgentIds.add(it.value) }
                    is MessagePart.ToolCall -> subagentRegex.findAll(part.args).forEach { keptAgentIds.add(it.value) }
                    is MessagePart.ToolResult -> subagentRegex.findAll(part.output).forEach { keptAgentIds.add(it.value) }
                    else -> {}
                }
            }
        }

        // 必须等旧 turn 完全死透（cancel + join）再截断历史，同时对回滚点时尚未创立的子代理做无感丢弃（不通知 AI）
        turnExecutor.rollbackAndJoin(id, keptAgentIds)

        historyStore.replace(id, remainingMsgs)
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

    override suspend fun resumeWorkspaceInput(id: String, workspaceId: String) {
        turnExecutor.resumeWorkspaceInput(id, workspaceId)
    }

    override suspend fun steerMessage(id: String, request: SendMessageRequest) {
        DebugLog.event("SessionMgr", "steerMessage: sessionId=$id, agentMode=${request.agentConfig.agentMode}, parts=${request.parts.size}")
        turnExecutor.steerMessage(id, request)
    }

    override suspend fun updateAgentConfig(
        id: String,
        agentMode: xyz.mederi.domain.model.AgentMode?,
        aiModel: xyz.mederi.domain.model.AIModel?,
        reasoningLevel: xyz.mederi.provider.domain.model.ReasoningLevel?,
        apiKeyId: String?
    ) {
        DebugLog.event("SessionMgr", "updateAgentConfig: sessionId=$id, agentMode=$agentMode, aiModel=${aiModel?.id}, reasoningLevel=$reasoningLevel, apiKeyId=$apiKeyId")
        require(id)
        sessionStore.updateAgentConfig(id, agentMode, aiModel, reasoningLevel, apiKeyId)
    }

    override suspend fun updateComposeState(
        id: String,
        agentMode: xyz.mederi.domain.model.AgentMode,
        aiModel: xyz.mederi.domain.model.AIModel?,
        reasoningLevel: xyz.mederi.provider.domain.model.ReasoningLevel?,
        apiKeyId: String?,
        draftText: String
    ) {
        DebugLog.event("SessionMgr", "updateComposeState: sessionId=$id, agentMode=$agentMode, aiModel=${aiModel?.id}, reasoningLevel=$reasoningLevel, apiKeyId=$apiKeyId, draftTextLen=${draftText.length}")
        require(id)
        sessionStore.updateComposeState(id, agentMode, aiModel, reasoningLevel, apiKeyId, draftText)
    }

    override suspend fun listActiveSkills(id: String): List<SkillInfo> {
        val session = require(id)
        val all = skills?.list().orEmpty()
        val activeSet = session.activeSkills.toSet()
        return all.filter { it.name in activeSet }
    }

    override suspend fun activateSkill(id: String, skillName: String): SkillInfo {
        val session = require(id)
        val all = skills?.list().orEmpty()
        val info = all.find { it.name == skillName }
            ?: throw MederiNotFoundException("Skill not found: $skillName")
        val current = session.activeSkills.toMutableList()
        if (skillName !in current) current.add(skillName)  // 幂等
        sessionStore.updateActiveSkills(id, current)
        eventBus.emit(
            MederiEvent(
                type = EventType.SKILL_ACTIVATED,
                sessionId = id,
                payload = mapOf(
                    "skillName" to skillName,
                    "activeSkills" to kotlinx.serialization.json.Json.encodeToString(current)
                ),
                timestamp = java.time.Instant.now().toString()
            )
        )
        DebugLog.event("SessionMgr", "activateSkill: sessionId=$id, skill=$skillName, active=${current.size}")
        return info
    }

    override suspend fun deactivateSkill(id: String, skillName: String) {
        val session = require(id)
        val current = session.activeSkills.toMutableList()
        current.remove(skillName)  // 幂等：不在则 no-op
        sessionStore.updateActiveSkills(id, current)
        eventBus.emit(
            MederiEvent(
                type = EventType.SKILL_DEACTIVATED,
                sessionId = id,
                payload = mapOf(
                    "skillName" to skillName,
                    "activeSkills" to kotlinx.serialization.json.Json.encodeToString(current)
                ),
                timestamp = java.time.Instant.now().toString()
            )
        )
        DebugLog.event("SessionMgr", "deactivateSkill: sessionId=$id, skill=$skillName, active=${current.size}")
    }

    override suspend fun resolveQuestion(id: String, questionId: String, answers: List<List<String>>) {
        turnExecutor.resolveQuestion(id, questionId, answers)
    }

    override suspend fun recordPlanManualAcceptance(
        sessionId: String, planId: String, caseId: String, observed: String, evidence: String, confirmedBy: String
    ) =
        turnExecutor.recordPlanManualAcceptance(sessionId, planId, caseId, observed, evidence, confirmedBy)

    override suspend fun getPlanAcceptanceReport(id: String, planId: String): String =
        turnExecutor.getPlanAcceptanceReport(id, planId)

    override suspend fun resolvePlanApproval(
        id: String,
        planId: String,
        approved: Boolean
    ) {
        val resolved = turnExecutor.resolvePlanApproval(id, planId, approved)
        if (approved && !resolved) throw xyz.mederi.api.exception.MederiStateException(
            "This draft is no longer pending or its approved collector files changed."
        )
    }

    override suspend fun compressHistory(id: String) {
        turnExecutor.compressHistory(id)
    }

    override suspend fun listMessages(id: String): List<Message> =
        historyStore.load(id)

    override suspend fun contextUsedTokens(id: String): Int {
        val session = require(id)
        return aiViewContextUsedTokens(historyStore, id, session.aiModel?.supportsImages ?: true)
    }

    override suspend fun listRawMessages(id: String): List<RawMessageRecord> =
        historyStore.listRaw(id)

    override fun getSubagentReport(agentId: String): xyz.mederi.tools.subagent.SubagentManager.SubagentReportData? =
        turnExecutor.getSubagentReport(agentId)

    override fun listSubagentsForSession(sessionId: String): List<xyz.mederi.tools.subagent.SubagentManager.BackgroundAgent> =
        turnExecutor.listSubagentsForSession(sessionId)

    override fun stopSubagent(agentId: String, reason: String): String =
        turnExecutor.stopSubagent(agentId, reason)

    override fun listBackgroundTasksForSession(sessionId: String): List<xyz.mederi.tools.background.BackgroundTaskManager.BackgroundTaskEntry> =
        turnExecutor.listBackgroundTasksForSession(sessionId)

    override fun stopBackgroundTask(taskId: String): String? =
        turnExecutor.stopBackgroundTask(taskId)

    override fun stopBrowserTask(taskId: String): String? =
        turnExecutor.stopBrowserTask(taskId)

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
