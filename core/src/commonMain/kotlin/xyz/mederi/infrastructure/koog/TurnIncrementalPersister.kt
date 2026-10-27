package xyz.mederi.infrastructure.koog

import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.prompt.message.Message as KoogMessage
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.utils.time.KoogClock
import xyz.mederi.debug.DebugLog
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.store.HistoryStore
import java.time.Instant

/**
 * Turn 内增量持久化器。
 *
 * 背景：Koog ChatMemory 的 strategy 级 store 只在 turn 正常结束时执行
 * （interceptStrategyCompleted），turn 中途崩溃（限流重试耗尽、供应商断流等）时，
 * 本轮已产生的 assistant 消息与 tool results 消息全部丢失——已落盘的文件改动成为
 * "无历史的改动"，下一轮模型不知道自己做过什么（端到端实测两次：
 * sess_464f8a23 / sess_49d312fb）。
 *
 * 本类在两个锚点被调用（mederiSingleRunStrategy 的节点内）：
 * 1. [persistAssistant]：nodeCallLLM 拿到完整 response 后
 * 2. [persistToolResults]：nodeLLMSendToolResults 把 tool results append 进 prompt 后
 *
 * 诊断注入：增量落库即带 [MessageDiagnostics]（modelName/reasoningLevel/agentMode/耗时等）——
 * 否则 assistant 消息在 reconcile 时被"已存在"识别，诊断字段永远补不上，
 * UI footer（模型名/推理档/时长/完成时间）就只剩 createdAt 推导的完成时间。
 *
 * 重复写防护：HistoryStoreChatHistoryProvider.store()（strategy 级回写）按内容指纹
 * （role+parts，不含时间戳）reconcile，对齐已有历史后只追加新消息——
 * 本类提前落库的消息会被识别为已存在，不会重复。
 *
 * 失败语义：持久化失败记录并抛出，由运行时统一报错；未落库不得确认交付。
 */
class TurnIncrementalPersister(
    private val sessionId: String,
    private val historyStore: HistoryStore,
    private val diagnostics: MessageDiagnostics = MessageDiagnostics(),
    private val eventBus: kotlinx.coroutines.flow.MutableSharedFlow<MederiEvent>? = null,
    val turnId: String? = null,
    val includeImages: Boolean = true,
    val workStartedAt: Long? = null,
    private val responseBoundary: ((String, Boolean) -> Unit)? = null,
    private val consumeInput: (suspend (SteeringItem) -> Unit)? = null
) {
    val lastAssistantMessageId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    val currentResponseId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    fun beginResponse(): String {
        val id = "msg_${java.util.UUID.randomUUID()}"
        currentResponseId.set(id)
        responseBoundary?.invoke(id, true)
        return id
    }

    fun completeResponse(id: String) { responseBoundary?.invoke(id, false) }

    suspend fun consumeSteering(item: SteeringItem) { consumeInput?.invoke(item) }

    /**
     * 输入接收可早于本次响应落库，但在模型因果顺序上，尚未消费的输入位于
     * 当前响应/工具结果之后。原子插入，避免历史重开后变成“模型已答过插话”。
     */
    private suspend fun persistBeforePending(message: xyz.mederi.domain.model.Message) {
        historyStore.updateHistory(sessionId) { history ->
            val index = history.indexOfFirst { it.pendingSteering }.let { if (it < 0) history.size else it }
            history.take(index) + message + history.drop(index)
        }
    }

    /**
     * assistant 消息到达即落库（含 token 用量/finishReason 诊断 + 模型/模式/推理档/耗时）。
     *
     * @param durationMs 本次 LLM 请求的真实耗时（节点层对 requestLLMStreaming 前后计时，
     * 含推理+正文流式全程）。Koog 的 ResponseMetaInfo.timestamp 是**流结束/usage 到达时刻**
     * （非响应创建时刻），用它推算（storedAt - timestamp）≈ 0——所以必须显式传入。
     * null 时回退 metaInfo 推算（reconcile 兜底路径）。
     */
    suspend fun persistAssistant(response: KoogMessage.Assistant, durationMs: Long? = null) {
        runCatching {
            val base = KoogMessageMapper.fromKoogMessage(sessionId, response).withDiagnostics(diagnostics)
                .copy(turnId = turnId, isFinalAnswer = false, workStartedAt = workStartedAt)
            val msg = if (durationMs != null) {
                base.copy(durationMs = base.durationMs ?: durationMs.coerceAtLeast(0L))
            } else {
                base.withAssistantDuration(response, Instant.now())
            }
            persistBeforePending(msg)
            lastAssistantMessageId.set(msg.id)
            DebugLog.event("Persist", "assistant appended: id=${msg.id}, parts=${msg.parts.size}, " +
                "modelName=${msg.modelName}, reasoningLevel=${msg.reasoningLevel}, durationMs=${msg.durationMs}")
        }.onFailure { e ->
            DebugLog.error("Persist", "assistant persist failed: ${e.message}", e)
            throw e
        }
    }

    /** tool results user 消息落库（下一轮 LLM 崩溃时结果不丢） */
    suspend fun persistToolResults(results: ReceivedToolResults) {
        runCatching {
            val parts = results.toolResults.map(ReceivedToolResult::toMessagePart)
            val koogUser = KoogMessage.User(
                parts = parts,
                metaInfo = RequestMetaInfo.create(KoogClock.System)
            )
            val msg = KoogMessageMapper.fromKoogUserMessage(sessionId, koogUser)
                .withDiagnostics(diagnostics)
            persistBeforePending(msg)
            DebugLog.event("Persist", "tool results appended: ${parts.size} parts")
        }.onFailure { e ->
            DebugLog.error("Persist", "tool results persist failed: ${e.message}", e)
            throw e
        }
    }

    /** 用户中途引导（Steering）消息落库 */
    suspend fun persistSteeringUserMessage(text: String, id: String? = null, timestamp: Long? = null) {
        runCatching {
            val koogUser = KoogMessage.User(
                content = text,
                metaInfo = RequestMetaInfo.create(KoogClock.System),
                id = id
            )
            val baseMsg = KoogMessageMapper.fromKoogUserMessage(sessionId, koogUser)
                .withDiagnostics(diagnostics)
            val msg = if (timestamp != null && timestamp > 0) {
                baseMsg.copy(createdAt = java.time.Instant.ofEpochMilli(timestamp).toString())
            } else {
                baseMsg
            }
            historyStore.append(sessionId, msg)
            DebugLog.event("Persist", "steering message appended: id=${msg.id}, text='$text', createdAt=${msg.createdAt}")
            eventBus?.emit(MederiEvent(type = EventType.SESSION_UPDATED, sessionId = sessionId, timestamp = Instant.now().toString()))
        }.onFailure { e ->
            DebugLog.error("Persist", "steering message persist failed: ${e.message}", e)
        }
    }
}
