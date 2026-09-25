package xyz.mederi.store.sqlite

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.MessageSummary
import xyz.mederi.store.RawMessageRecord
import java.util.Properties

/**
 * 基于 SQLDelight + SQLite 的 HistoryStore 实现。
 *
 * 适合本地应用（桌面应用、开发环境）。零配置，单文件数据库。
 *
 * 对话历史完整存储：每条消息的完整 Message 对象以 JSON 序列化存储在 payload 列，
 * 同时提取关键字段（seq、role、content 摘要、created_at）到独立列用于查询和预览。
 *
 * 支持回退到特定消息点（rollbackTo）。
 *
 * @param dbPath 数据库文件路径，如 "/home/user/.mederi/data/mederi.db"。
 *               调用方负责路径展开和目录创建。
 * @param json JSON 序列化器，用于序列化 Message 对象。
 */
class SqliteHistoryStore(
    driver: app.cash.sqldelight.db.SqlDriver,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) : HistoryStore {

    private val queries = MederiDataDatabase(driver).dataDatabaseQueries

    override suspend fun load(sessionId: String): List<Message> = withContext(Dispatchers.IO) {
        queries.loadBySession(sessionId).executeAsList().map { row ->
            json.decodeFromString<Message>(row.payload)
        }
    }

    override suspend fun append(sessionId: String, message: Message): Unit = withContext(Dispatchers.IO) {
        // 读 maxSeq 和插入必须在同一事务中，避免并发 append 导致 seq 冲突
        queries.transaction {
            val nextSeq = (queries.maxSeq(sessionId).executeAsOneOrNull()?.MAX ?: -1L) + 1L
            queries.appendMessage(
                session_id = sessionId,
                seq = nextSeq,
                message_id = message.id,
                role = message.role.name,
                content = extractContent(message),
                payload = json.encodeToString(message),
                created_at = message.createdAt,
                model_id = message.modelId,
                duration_ms = message.durationMs?.toLong(),
                finish_reason = message.finishReason,
                status = message.status.name
            )
        }
    }

    override suspend fun replace(sessionId: String, messages: List<Message>): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            queries.deleteAllBySession(sessionId)
            messages.forEachIndexed { index, message ->
                queries.appendMessage(
                    session_id = sessionId,
                    seq = index.toLong(),
                    message_id = message.id,
                    role = message.role.name,
                    content = extractContent(message),
                    payload = json.encodeToString(message),
                    created_at = message.createdAt,
                    model_id = message.modelId,
                    duration_ms = message.durationMs?.toLong(),
                    finish_reason = message.finishReason,
                    status = message.status.name
                )
            }
        }
    }

    override suspend fun delete(sessionId: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteBySession(sessionId)
    }

    override suspend fun rollbackTo(sessionId: String, seq: Long): Unit = withContext(Dispatchers.IO) {
        queries.rollbackTo(sessionId, seq)
    }

    override suspend fun listSummary(sessionId: String): List<MessageSummary> = withContext(Dispatchers.IO) {
        queries.listSummary(sessionId).executeAsList().map { row ->
            MessageSummary(
                seq = row.seq,
                messageId = row.message_id,
                role = row.role,
                content = row.content,
                createdAt = row.created_at
            )
        }
    }

    override suspend fun listRaw(sessionId: String): List<RawMessageRecord> = withContext(Dispatchers.IO) {
        queries.listRaw(sessionId).executeAsList().map { row ->
            RawMessageRecord(
                seq = row.seq,
                messageId = row.message_id,
                role = row.role,
                payload = row.payload,
                createdAt = row.created_at,
                modelId = row.model_id,
                durationMs = row.duration_ms,
                finishReason = row.finish_reason,
                status = row.status
            )
        }
    }

    /**
     * 从消息的 parts 中提取摘要文本。
     * 取第一个文本类型片段的内容，用于列表预览。
     * 如果没有文本片段，返回角色名作为占位符。
     */
    private fun extractContent(message: Message): String {
        return message.parts
            .filterIsInstance<MessagePart.Text>()
            .firstOrNull()
            ?.text
            ?: message.role.name.lowercase()
    }
}
