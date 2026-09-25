package xyz.mederi.session

import kotlinx.coroutines.flow.Flow
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.domain.model.FileDiff
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.Session
import xyz.mederi.store.RawMessageRecord

/**
 * 会话管理器。
 *
 * Session 的唯一真理源。所有 Session 的生命周期管理、消息收发、事件流都必须通过 SessionManager。
 *
 * 核心职责：
 * - Session CRUD（创建时校验 Project 和 Agent mode）
 * - 消息发送（委托 TurnExecutor 执行 Agent loop）
 * - 对话历史查询
 * - 事件流管理
 * - 级联删除：删除 Session 时级联删除其对话历史
 */
interface SessionManager {

    suspend fun list(): List<Session>
    suspend fun listByProject(projectId: String): List<Session>
    suspend fun get(id: String): Session?
    suspend fun require(id: String): Session

    suspend fun create(
        agentConfig: AgentConfig,
        projectId: String,
        title: String,
        env: Map<String, String>
    ): Session

    suspend fun rename(id: String, title: String): Session

    suspend fun delete(id: String)
    suspend fun abort(id: String)
    /** 中止并等待 turn 完全终止；对"进程重启残留的 RUNNING 状态"兜底复位为 IDLE。 */
    suspend fun abortAndJoin(id: String)

    suspend fun sendMessage(id: String, request: SendMessageRequest)
    suspend fun steerMessage(id: String, request: SendMessageRequest)
    suspend fun rollbackToMessage(id: String, messageId: String)
    suspend fun resolveQuestion(id: String, questionId: String, answers: List<List<String>>)
    suspend fun resolvePlanApproval(
        id: String,
        planId: String,
        approved: Boolean,
        aiModel: xyz.mederi.domain.model.AIModel? = null,
        reasoningLevel: xyz.mederi.provider.domain.model.ReasoningLevel? = null
    )
    suspend fun compressHistory(id: String)
    suspend fun listMessages(id: String): List<Message>
    suspend fun getMessage(id: String, messageId: String): Message

    /** 原始消息直读（调试用）：落库时的原始 JSON payload，不解析不映射。 */
    suspend fun listRawMessages(id: String): List<RawMessageRecord>

    /** 读取子代理任务汇报。 */
    fun getSubagentReport(agentId: String): xyz.mederi.tools.subagent.SubagentManager.SubagentReportData?

    suspend fun getFileDiffs(id: String, messageId: String? = null): List<FileDiff>

    fun events(sessionId: String): Flow<MederiEvent>
    fun events(): Flow<MederiEvent>
}
