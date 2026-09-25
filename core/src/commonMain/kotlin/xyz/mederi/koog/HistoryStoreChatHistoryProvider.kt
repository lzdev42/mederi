package xyz.mederi.infrastructure.koog

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message as KoogMessage
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.MessageStatus
import xyz.mederi.store.HistoryStore
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 原始消息诊断元数据（每个 turn 一份，随 provider 构造注入）。
 *
 * 落库的每条消息都打上"这次请求是谁发的"——原始消息查看器据此还原
 * 请求上下文（哪个模型、什么模式、什么项目），不用再猜。
 */
data class MessageDiagnostics(
    val providerId: String? = null,
    val modelId: String? = null,
    val modelName: String? = null,
    val reasoningLevel: String? = null,
    val agentMode: String? = null,
    val projectId: String? = null
)

/**
 * 工具调用计时登记表（每个 turn 一份）：EventHandler 记录每次工具调用的开始/结束，
 * store 回写时按 toolCallId 把 status/durationMs/error 回填到 ToolResult part 上。
 */
class TurnToolTimings {
    private val starts = java.util.concurrent.ConcurrentHashMap<String, java.time.Instant>()
    private val records = java.util.concurrent.ConcurrentHashMap<String, ToolTiming>()

    fun onStarting(toolCallId: String?) {
        if (toolCallId != null) starts[toolCallId] = Instant.now()
    }

    fun onCompleted(toolCallId: String?) {
        record(toolCallId, status = "completed", error = null)
    }

    fun onFailed(toolCallId: String?, errorMessage: String) {
        record(toolCallId, status = "failed", error = errorMessage.take(2000))
    }

    fun lookup(toolCallId: String?): ToolTiming? = toolCallId?.let { records[it] }

    private fun record(toolCallId: String?, status: String, error: String?) {
        val start = toolCallId?.let { starts.remove(it) } ?: return
        records[toolCallId] = ToolTiming(status, Duration.between(start, Instant.now()).toMillis(), error)
    }
}

data class ToolTiming(val status: String, val durationMs: Long, val error: String?)

/** 给落库消息打诊断元数据（只补空不覆盖——诊断字段是增强信息）。 */
internal fun xyz.mederi.domain.model.Message.withDiagnostics(d: MessageDiagnostics): xyz.mederi.domain.model.Message =
    copy(
        providerId = providerId ?: d.providerId,
        modelId = modelId ?: d.modelId,
        modelName = modelName ?: d.modelName,
        reasoningLevel = reasoningLevel ?: d.reasoningLevel,
        agentMode = agentMode ?: d.agentMode,
        projectId = projectId ?: d.projectId
    )

/**
 * Assistant 消息近似耗时：Koogy ResponseMetaInfo 的 timestamp 是响应创建时刻，
 * 与落库时刻之差包含流式传输全程——作为 LLM 请求耗时的下界估计够诊断用。
 */
internal fun xyz.mederi.domain.model.Message.withAssistantDuration(
    koog: KoogMessage,
    storedAt: Instant
): xyz.mederi.domain.model.Message =
    if (role != MessageRole.ASSISTANT) this
    else copy(durationMs = durationMs ?: run {
        val created = Instant.parse(koog.metaInfo.timestamp.toString())
        val delta = Duration.between(created, storedAt).toMillis()
        if (delta >= 0) delta else null
    })

/**
 * 按 toolCallId 回填工具执行状态与耗时（ToolResult part）。
 * 没有登记记录（如压缩回写的历史消息）保持原样。
 */
private fun xyz.mederi.domain.model.Message.withToolTimings(
    timings: TurnToolTimings?
): xyz.mederi.domain.model.Message {
    if (timings == null) return this
    val changed = parts.map { part ->
        if (part is MessagePart.ToolResult) {
            timings.lookup(part.id)?.let { t ->
                part.copy(status = t.status, durationMs = t.durationMs, error = t.error)
            } ?: part
        } else part
    }
    return if (changed == parts) this else copy(parts = changed)
}

/**
 * 将 Mederi 的 HistoryStore 桥接到 Koog 的 ChatHistoryProvider。
 *
 * 核心设计：**对话历史只有一套（HistoryStore 全量保存，UI 可见），AI 视图是它的窗口**。
 *
 * - load：找到最后一条 SUMMARY（压缩标记）消息，只返回「标记本身 + 标记之后」的消息。
 *   标记之前的历史仍然保存在 HistoryStore 里、UI 照常显示，只是不进 prompt。
 *   SUMMARY 映射为 Assistant 消息发给 AI，模型由此知道上下文被压缩过、压缩了什么。
 * - store：reconcile 回写。识别出本次发生了压缩（首条是未落库的 TLDR）时，
 *   在历史对应位置**插入** SUMMARY 标记（不删除任何已有消息），再追加真正的新消息；
 *   没有压缩时只追加新消息。任何情况下都不用 AI 视图覆盖全量历史。
 *
 * System 消息不持久化（它是 Prompt 配置的一部分，不是对话历史）。
 *
 * [diagnostics] 注入原始消息诊断元数据（模型/供应商/模式/项目）——落库的每条消息
 * 都带上"这次请求是谁发的"，UI 原始消息查看器据此还原请求上下文。
 *
 * [includeImages] load 时是否保留用户消息中的图片（AI 视图按当前模型能力过滤）——
 * 默认 true 行为与原先完全一致；store 回写不受此参数影响：库中带图历史永远以库为准，
 * 无图 AI 视图的回显不会覆盖已落库消息（见 store 的 id 过滤）。
 */
class HistoryStoreChatHistoryProvider(
    private val historyStore: HistoryStore,
    private val diagnostics: MessageDiagnostics = MessageDiagnostics(),
    private val toolTimings: TurnToolTimings? = null,
    private val includeImages: Boolean = true
) : ChatHistoryProvider {

    override suspend fun load(conversationId: String): List<KoogMessage> {
        val window = aiViewWindow(historyStore, conversationId)
        return KoogMessageMapper.toKoogMessages(window, includeImages)
    }

    override suspend fun store(conversationId: String, messages: List<KoogMessage>) {
        val storedAt = Instant.now()
        val incoming = messages.mapNotNull { msg ->
            when (msg) {
                is KoogMessage.Assistant -> KoogMessageMapper.fromKoogMessage(conversationId, msg)
                is KoogMessage.User -> KoogMessageMapper.fromKoogUserMessage(conversationId, msg)
                is KoogMessage.System -> null
            }
                ?.withDiagnostics(diagnostics)
                ?.withAssistantDuration(msg, storedAt)
                ?.withToolTimings(toolTimings)
        }
        if (incoming.isEmpty()) return

        val existing = historyStore.load(conversationId)

        // ── 已有 id 过滤（AI 视图回显不覆盖已落库消息）：
        //    库是含全量字段（图片等）的真理源。includeImages=false 时 load 返回的
        //    无图 AI 视图在 store 回写时若参与 reconcile，指纹与库中带图版不同，
        //    常规对齐会失败 → 走整表 replace，把带图历史替换成无图版本。
        //    这里先按 id 剔除已在库中的回显消息，只对真正的新消息做回写——
        //    已落库消息永远以库中版本为准，无图 AI 视图不得覆盖历史。
        val existingIds = existing.mapTo(java.util.HashSet<String?>()) { it.id }
        val freshIncoming = incoming.filter { it.id !in existingIds }
        if (freshIncoming.isEmpty()) return

        val freshIncomingSigs = freshIncoming.map { signature(it) }
        val existingSigs = existing.map { signature(it) }

        // ── 压缩回写：首条是 TLDR 且它不在已有历史里（本次新生成的总结）
        val first = freshIncoming.first()
        val firstText = first.parts.filterIsInstance<MessagePart.Text>().firstOrNull()?.text.orEmpty()
        val tldrLike = first.role == MessageRole.ASSISTANT && firstText.startsWith(TLDR_PREFIX)

        if (tldrLike) {
            val alreadyMarked = existing.any {
                it.role == MessageRole.SUMMARY &&
                    it.parts.filterIsInstance<MessagePart.Text>().firstOrNull()?.text == firstText
            }
            if (!alreadyMarked) {
                // 本次新生成的压缩：freshIncoming[1..] 只有未落库的新消息（回显已按 id 过滤），
                // 若其前缀与已有历史尾部重合（内容恰好相同的罕见场景）则视为对齐，
                // 在对齐点插入 SUMMARY 标记，再追加未落库的新消息；已有历史一条不删。
                var k = minOf(freshIncoming.size - 1, existing.size)
                while (k > 0) {
                    val head = freshIncomingSigs.subList(1, 1 + k)
                    val tail = existingSigs.takeLast(k)
                    if (head == tail) break
                    k--
                }
                val markerAt = existing.size - k
                val marker = summaryMarker(conversationId, firstText)
                val merged = existing.take(markerAt) + marker +
                    existing.drop(markerAt) + freshIncoming.drop(1 + k)
                historyStore.replace(conversationId, merged)
                return
            }
            // TLDR 已在早前的 store 调用中落库（同一 run 压缩后的后续回写）：
            // 跳过 TLDR 头，对齐已有历史尾部，只追加新消息
            val rest = freshIncomingSigs.drop(1)
            var k = minOf(rest.size, existingSigs.size)
            while (k > 0 && rest.take(k) != existingSigs.takeLast(k)) k--
            for (msg in freshIncoming.drop(1 + k)) {
                historyStore.append(conversationId, msg)
            }
            return
        }

        // ── 常规回写：freshIncoming 应与已有历史的一段对齐（无标记时从头对齐，
        //    有标记时 freshIncoming 首条就是被映射回来的 SUMMARY），对齐后只追加新消息。
        val alignStart = if (existingSigs.isNotEmpty() && freshIncomingSigs.first() == existingSigs.first()) {
            0
        } else {
            existingSigs.lastIndexOf(freshIncomingSigs.first())
        }
        if (alignStart >= 0) {
            var i = 0
            var j = alignStart
            while (i < freshIncoming.size && j < existing.size &&
                freshIncomingSigs[i] == existingSigs[j]
            ) {
                i++
                j++
            }
            // i 之后的是本轮新增消息（j 已到已有历史末尾或内容不再匹配）
            for (msg in freshIncoming.drop(i)) {
                historyStore.append(conversationId, msg)
            }
            return
        }

        // ── 对齐失败（异常状态，如运行期间历史被外部清空/内容不匹配）：
        //    退回整体替换，但必须 existing + freshIncoming——只用 freshIncoming 会删掉
        //    库中全部旧消息；existing 已在库、freshIncoming 保证不在库（id 已去重），
        //    拼接无重复，且库中带图版本原样保留。
        historyStore.replace(conversationId, existing + freshIncoming)
    }

    /**
     * 消息内容指纹：用于 store 回写时与已有历史逐条对齐。
     *
     * 只取能跨 Koog 往返稳定保留的内容（文本/工具调用/工具结果/推理/图片 URL），
     * 忽略 id、时间戳、token 统计等每次往返都会变化的字段；
     * File 片段在 Koog 映射中会被丢弃，同样不参与指纹。
     */
    private fun signature(message: Message): String = buildString {
        append(message.role.name)
        for (part in message.parts) {
            when (part) {
                is MessagePart.Text -> append("|t:").append(part.text)
                is MessagePart.ToolCall -> append("|c:").append(part.tool).append(':').append(part.args)
                is MessagePart.ToolResult -> append("|r:").append(part.tool).append(':').append(part.output)
                is MessagePart.Reasoning -> append("|n:").append(part.content.joinToString("\u0001"))
                is MessagePart.Image -> append("|i:").append(part.url)
                is MessagePart.File -> Unit
            }
        }
    }

    private fun summaryMarker(sessionId: String, tldrText: String): Message = Message(
        id = "msg_${UUID.randomUUID().toString().take(8)}",
        sessionId = sessionId,
        role = MessageRole.SUMMARY,
        parts = listOf(MessagePart.Text(tldrText)),
        status = MessageStatus.COMPLETED,
        createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    )

    companion object {
        /** 压缩总结的首行前缀，与 MederiCompressionStrategy.SUMMARY_PROMPT 的输出约定一致 */
        const val TLDR_PREFIX = "TLDR:"
        /**
         * AI 视图窗口：找到最后一条 SUMMARY（压缩标记），返回「标记本身 + 标记之后」的消息；
         * 无标记则返回全量历史。标记之前的历史保留在库里（UI 可见），只是不发给 AI。
         * 所有需要「发送给 AI 的内容」的地方（ChatMemory load、token 估算等）都应使用本函数。
         */
        suspend fun aiViewWindow(historyStore: HistoryStore, sessionId: String): List<Message> {
            val all = historyStore.load(sessionId)
            val markerIndex = all.indexOfLast { it.role == MessageRole.SUMMARY }
            return if (markerIndex >= 0) all.drop(markerIndex) else all
        }

        /**
         * 插入一条压缩标记（不生成总结，用于 new_context 等只需截断 AI 视图的场景）。
         * 标记之前的历史保留在库里、UI 可见，只是不再发送给 AI。
         */
        suspend fun insertMarker(historyStore: HistoryStore, sessionId: String, note: String) {
            historyStore.append(
                sessionId,
                Message(
                    id = "msg_${UUID.randomUUID().toString().take(8)}",
                    sessionId = sessionId,
                    role = MessageRole.SUMMARY,
                    parts = listOf(MessagePart.Text(note)),
                    status = MessageStatus.COMPLETED,
                    createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                )
            )
        }
    }
}
