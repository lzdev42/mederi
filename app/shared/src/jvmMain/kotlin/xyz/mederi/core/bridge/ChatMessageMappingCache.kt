package xyz.mederi.core.bridge

import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart

/**
 * 按 domain Message 引用相等的消息映射缓存（带工具结果版本号失效）。
 *
 * 服务 [MederiEventAggregator.refreshPage] 的全量回查场景：长会话每次
 * SESSION_UPDATED / MESSAGE_COMPLETED / MESSAGE_ERROR （以及未知响应 ID 的 delta 回查）
 * 都会对全量历史重跑 `toChatMessage`，本缓存让未变化消息的映射只算一次。
 *
 * 正确性依据：
 * - 缓存键 = 引用相等（[java.util.IdentityHashMap]）：Message 是不可变 data class，
 *   引用相同 ⇒ 内容必然相同，避免每次回查做 data-class equals 的 O(parts) 比较。
 * - 版本号 = toolResultsById.size：`MederiModelMapper.toChatMessage(message, toolResultsById)`
 *   的产出依赖跨消息工具结果表（assistant 的 ToolCall 块要靠后续 user 消息的 ToolResult
 *   回填 input/output）。工具结果表在历史上是 append-only（persistToolResults 只 append
 *   新 ToolResult），新结果到达 → 版本单调 +1 → 全量重映射；ToolResult 内容/消息字段更新
 *   走 updateMessage（换新引用 → identity miss）；回滚/replace 产生全新引用 + 列表收缩
 *   （[lastListSize] 变小 → clear，防旧引用泄漏）。
 *
 * 单观察流内单实例（flow 收集者串行），不引入并发结构。
 */
internal class ChatMessageMappingCache {
    private val cache = java.util.IdentityHashMap<Message, Pair<Int, ChatMessage>>()
    private var lastListSize = -1

    fun mapAll(
        messages: List<Message>,
        toolResultsById: Map<String, MessagePart.ToolResult>,
        mapper: (Message, Map<String, MessagePart.ToolResult>) -> ChatMessage = MederiModelMapper::toChatMessage,
    ): List<ChatMessage> {
        // 历史收缩（回滚/截断）：旧引用全部作废，清缓存防泄漏
        if (messages.size < lastListSize) cache.clear()
        lastListSize = messages.size
        val revision = toolResultsById.size
        return messages.map { m ->
            cache[m]?.takeIf { it.first == revision }?.second
                ?: mapper(m, toolResultsById).also { cache[m] = revision to it }
        }
    }
}