package xyz.mederi.store

import xyz.mederi.domain.model.Message

/**
 * 仅内存的 HistoryStore 实现。
 *
 * 未指定 configDir 时使用。
 */
class InMemoryHistoryStore : HistoryStore {

    private val messages = mutableMapOf<String, MutableList<Message>>()

    override suspend fun load(sessionId: String): List<Message> =
        messages[sessionId]?.toList() ?: emptyList()

    override suspend fun append(sessionId: String, message: Message) {
        messages.getOrPut(sessionId) { mutableListOf() }.add(message)
    }

    override suspend fun replace(sessionId: String, messages: List<Message>) {
        this.messages[sessionId] = messages.toMutableList()
    }

    override suspend fun delete(sessionId: String) {
        messages.remove(sessionId)
    }

    override suspend fun rollbackTo(sessionId: String, seq: Long) {
        messages[sessionId] = messages[sessionId]
            ?.filterIndexed { index, _ -> index <= seq }
            ?.toMutableList()
            ?: mutableListOf()
    }

    override suspend fun listSummary(sessionId: String): List<MessageSummary> =
        messages[sessionId]?.mapIndexed { index, message ->
            MessageSummary(
                seq = index.toLong(),
                messageId = message.id,
                role = message.role.name,
                content = message.parts.filterIsInstance<xyz.mederi.domain.model.MessagePart.Text>()
                    .firstOrNull()?.text ?: message.role.name.lowercase(),
                createdAt = message.createdAt
            )
        } ?: emptyList()

    override suspend fun listRaw(sessionId: String): List<RawMessageRecord> {
        // InMemory 无独立 payload 列——现场序列化，语义与 SQLite 实现一致（落库即序列化）
        val snapshot = messages[sessionId]?.toList() ?: return emptyList()
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        return snapshot.mapIndexed { index, message ->
            RawMessageRecord(
                seq = index.toLong(),
                messageId = message.id,
                role = message.role.name,
                payload = json.encodeToString(xyz.mederi.domain.model.Message.serializer(), message),
                createdAt = message.createdAt,
                modelId = message.modelId,
                durationMs = message.durationMs,
                finishReason = message.finishReason,
                status = message.status.name
            )
        }
    }
}
