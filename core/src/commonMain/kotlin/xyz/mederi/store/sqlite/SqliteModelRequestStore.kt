package xyz.mederi.store.sqlite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.ModelRequestRecord
import xyz.mederi.store.ModelRequestStore
import xyz.mederi.store.db.DatabaseManager
import xyz.mederi.store.forLedger

class SqliteModelRequestStore(private val db: DatabaseManager) : ModelRequestStore {
    private val queries = db.database.dataDatabaseQueries
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun start(record: ModelRequestRecord): Unit = withContext(Dispatchers.IO) {
        val payload = record.usage?.let { json.encodeToString(it.forLedger()) }
        db.write {
            queries.insertModelRequest(
                record.ownerSessionId, record.requestId, record.executionSessionId, record.startedAt, payload,
            )
        }
    }

    override suspend fun complete(ownerSessionId: String, requestId: String, usage: Message): Unit = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(usage.forLedger())
        db.write { queries.completeModelRequest(payload, ownerSessionId, requestId) }
    }

    override suspend fun list(ownerSessionId: String): List<ModelRequestRecord> = withContext(Dispatchers.IO) {
        queries.listModelRequests(ownerSessionId).executeAsList().map {
            toRecord(it.request_id, it.owner_session_id, it.execution_session_id, it.started_at, it.usage_payload)
        }
    }

    override suspend fun listAll(): List<ModelRequestRecord> = withContext(Dispatchers.IO) {
        queries.listAllModelRequests().executeAsList().map {
            toRecord(it.request_id, it.owner_session_id, it.execution_session_id, it.started_at, it.usage_payload)
        }
    }

    override suspend fun delete(ownerSessionId: String) {
        db.write { queries.deleteModelRequests(ownerSessionId) }
    }

    private fun toRecord(
        requestId: String,
        ownerSessionId: String,
        executionSessionId: String,
        startedAt: String,
        usagePayload: String?,
    ): ModelRequestRecord = ModelRequestRecord(
        requestId, ownerSessionId, executionSessionId, startedAt,
        usagePayload?.let { json.decodeFromString<Message>(it) },
    )
}
