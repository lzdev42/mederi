package xyz.mederi.api

import kotlinx.coroutines.flow.Flow
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.FileDiff
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.WorkType
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * Session API。
 */
interface SessionApi {

    suspend fun list(): List<Session>
    suspend fun create(request: CreateSessionRequest): Session
    suspend fun get(id: String): Session
    suspend fun rename(id: String, request: RenameSessionRequest): Session
    suspend fun delete(id: String)
    suspend fun abort(id: String)
    /** 中止并等待 turn 完全终止；对进程重启残留的 RUNNING 状态兜底复位为 IDLE（供启动清理用）。 */
    suspend fun abortAndJoin(id: String)

    suspend fun sendMessage(sessionId: String, request: SendMessageRequest)
    suspend fun rollbackToMessage(sessionId: String, messageId: String)
    suspend fun resolveQuestion(sessionId: String, questionId: String, answers: List<List<String>>)
    suspend fun resolvePlanApproval(sessionId: String, planId: String, approved: Boolean)
    suspend fun compressHistory(sessionId: String)
    suspend fun listMessages(sessionId: String): List<Message>
    suspend fun getMessage(sessionId: String, messageId: String): Message

    /** 原始消息直读（调试用）：落库时的原始 JSON payload，不解析不映射。 */
    suspend fun listRawMessages(sessionId: String): List<RawMessageDto>

    suspend fun getFileDiffs(sessionId: String, messageId: String? = null): List<FileDiff>

    fun events(sessionId: String): Flow<MederiEvent>
    fun events(): Flow<MederiEvent>
}

/**
 * Agent 运行时配置。
 *
 * @param agentMode 执行策略（APPROVAL / AUTONOMOUS），决定系统提示词。
 * @param workType 工作用途（WORK / CODE），决定提示词引导。
 * @param aiModel 选用的模型。null 时继承 Session 记住的模型。
 * @param reasoningLevel 推理等级。null 时继承 Session 记住的等级。
 */
data class AgentConfig(
    val agentMode: AgentMode,
    val workType: WorkType = WorkType.CODE,
    val aiModel: AIModel? = null,
    val reasoningLevel: ReasoningLevel? = null
)

/**
 * 创建 Session 请求。
 *
 * @param agentConfig Agent 运行时配置，agentMode 必填，aiModel 可选。
 * @param projectId 项目 ID。
 * @param title 标题。
 * @param env 会话环境变量。
 */
data class CreateSessionRequest(
    val agentConfig: AgentConfig,
    val projectId: String,
    val title: String = "",
    val env: Map<String, String> = emptyMap()
)

/**
 * 重命名 Session 请求。
 *
 * @param title 新标题，不能为空。
 */
data class RenameSessionRequest(
    val title: String
)

/**
 * 发送消息请求。
 *
 * @param agentConfig 本次消息使用的 Agent 配置，必填，不能为 null。
 * @param parts 用户消息内容片段。
 */
data class SendMessageRequest(
    val agentConfig: AgentConfig,
    val parts: List<MessagePart>,
    /**
     * 本次消息选定的供应商 API Key 的 ID。null = 用该供应商默认 key。
     * 唯一真理源 = 每次发送携带的 apiKeyId，本 turn 内所有操作（主链路/压缩/子代理/浏览器）继承。
     */
    val apiKeyId: String? = null
)

/**
 * 原始消息记录（调试用）：[payload] 是消息落库时的原始 JSON 字符串，原样透传不解析。
 * UI 的"原始消息"查看器直接展示这个 JSON——存储层即真相，渲染层只是投影。
 */
data class RawMessageDto(
    val seq: Long,
    val messageId: String?,
    val role: String,
    val payload: String,
    val createdAt: String
)
