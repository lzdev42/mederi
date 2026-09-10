package xyz.mederi.store.sqlite

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.store.DiffStore
import xyz.mederi.tools.diff.FileChange
import xyz.mederi.tools.diff.TurnDiff

/**
 * 基于 SQLDelight + SQLite 的 DiffStore 实现（data.db）。
 *
 * @param driver 数据库 driver（由 Mederi 装配层创建并共享）。
 * @param json JSON 序列化器。
 */
class SqliteDiffStore(
    driver: SqlDriver,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) : DiffStore {

    private val queries = MederiDataDatabase(driver).dataDatabaseQueries
    private val fileChangeSerializer = ListSerializer(FileChange.serializer())

    override suspend fun save(diff: TurnDiff): Unit = withContext(Dispatchers.IO) {
        queries.insertDiff(
            session_id = diff.sessionId,
            message_id = diff.messageId,
            changes_json = json.encodeToString(fileChangeSerializer, diff.changes),
            unified_diff = diff.unifiedDiff,
            created_at = diff.createdAt
        )
    }

    override suspend fun list(sessionId: String): List<TurnDiff> = withContext(Dispatchers.IO) {
        queries.listDiffs(sessionId) { session_id, message_id, changes_json, unified_diff, created_at ->
            TurnDiff(
                sessionId = session_id,
                messageId = message_id,
                changes = json.decodeFromString(fileChangeSerializer, changes_json),
                unifiedDiff = unified_diff,
                createdAt = created_at
            )
        }.executeAsList()
    }

    override suspend fun get(sessionId: String, messageId: String?): TurnDiff? = withContext(Dispatchers.IO) {
        if (messageId == null) {
            queries.getLatestDiff(sessionId) { session_id, message_id, changes_json, unified_diff, created_at ->
                TurnDiff(
                    sessionId = session_id,
                    messageId = message_id,
                    changes = json.decodeFromString(fileChangeSerializer, changes_json),
                    unifiedDiff = unified_diff,
                    createdAt = created_at
                )
            }.executeAsOneOrNull()
        } else {
            queries.getDiffByMessage(sessionId, messageId) { session_id, message_id, changes_json, unified_diff, created_at ->
                TurnDiff(
                    sessionId = session_id,
                    messageId = message_id,
                    changes = json.decodeFromString(fileChangeSerializer, changes_json),
                    unifiedDiff = unified_diff,
                    createdAt = created_at
                )
            }.executeAsOneOrNull()
        }
    }

    override suspend fun delete(sessionId: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteDiffsBySession(sessionId)
    }
}
