package xyz.mederi.tools.subagent

import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent

/**
 * 子代理事件转发的有序聚合器。只合并同响应、同语义类型、相同元数据的文本增量。
 * 响应边界/类型切换/工具事件是屏障：先冲刷旧文本，再原样发送屏障，绝不重标记旧buffer。
 */
class SubagentProgressBatch(
    private val throttleMillis: Long = 120L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private var lastFlushAt = 0L
    private var pending: MederiEvent? = null

    fun add(event: MederiEvent): List<MederiEvent> {
        val out = mutableListOf<MederiEvent>()
        val kind = event.payload["type"]
        val coalescible = event.type == EventType.MESSAGE_DELTA && kind in setOf("text", "reasoning")
        if (!coalescible) {
            flushNow()?.let(out::add)
            out.add(event)
            lastFlushAt = nowMillis()
            return out
        }
        val previous = pending
        if (previous != null && (previous.messageId != event.messageId || previous.sessionId != event.sessionId ||
                previous.payload - "content" != event.payload - "content")) {
            flushNow()?.let(out::add)
        }
        val current = pending
        pending = if (current == null) event else current.copy(
            payload = current.payload + ("content" to (current.payload["content"].orEmpty() + event.payload["content"].orEmpty()))
        )
        flushIfDue()?.let(out::add)
        return out
    }

    fun flushIfDue(): MederiEvent? =
        if (nowMillis() - lastFlushAt >= throttleMillis) flushNow() else null

    fun flushNow(): MederiEvent? {
        val frame = pending ?: return null
        pending = null
        lastFlushAt = nowMillis()
        return frame
    }
}
