package xyz.mederi.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.ModelRequestRecord

interface ModelRequestStore {
    /** 重复的请求 ID 保留首次记录。 */
    suspend fun start(record: ModelRequestRecord)

    /** 原子补齐已发起请求的用量，重复完成保留首次结果。 */
    suspend fun complete(ownerSessionId: String, requestId: String, usage: Message)

    suspend fun list(ownerSessionId: String): List<ModelRequestRecord>
    /** 全库跨 owner 列出全部请求账本记录（跨会话累计统计数据源）。 */
    suspend fun listAll(): List<ModelRequestRecord>
    suspend fun delete(ownerSessionId: String)
}

class InMemoryModelRequestStore : ModelRequestStore {
    private data class Entry(val record: ModelRequestRecord, val completed: Boolean = false)

    private val lock = Mutex()
    private val sessions = mutableMapOf<String, LinkedHashMap<String, Entry>>()

    override suspend fun start(record: ModelRequestRecord) = lock.withLock {
        val requests = sessions.getOrPut(record.ownerSessionId) { linkedMapOf() }
        requests.putIfAbsent(record.requestId, Entry(record.copy(usage = record.usage?.forLedger())))
        Unit
    }

    override suspend fun complete(ownerSessionId: String, requestId: String, usage: Message) = lock.withLock {
        val requests = sessions[ownerSessionId] ?: return@withLock
        val entry = requests[requestId] ?: return@withLock
        if (!entry.completed) {
            requests[requestId] = Entry(entry.record.copy(usage = usage.forLedger()), completed = true)
        }
    }

    override suspend fun list(ownerSessionId: String): List<ModelRequestRecord> = lock.withLock {
        sessions[ownerSessionId]?.values?.map { it.record }.orEmpty()
    }

    override suspend fun listAll(): List<ModelRequestRecord> = lock.withLock {
        sessions.values.flatMap { requests -> requests.values.map { it.record } }
    }

    override suspend fun delete(ownerSessionId: String) = lock.withLock {
        sessions.remove(ownerSessionId)
        Unit
    }
}

/** 账本只保留诊断和用量字段，不持久化模型正文、工具参数或其它会话内容。 */
internal fun Message.forLedger(): Message = copy(
    parts = emptyList(),
    turnDiffSummary = null,
    pendingWorkspaceInput = null,
)
