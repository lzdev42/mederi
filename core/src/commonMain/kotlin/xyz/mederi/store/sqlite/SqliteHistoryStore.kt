package xyz.mederi.store.sqlite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.MessageSummary
import xyz.mederi.store.RawMessageRecord
import xyz.mederi.store.db.DatabaseManager
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 基于 SQLDelight + SQLite 的 HistoryStore 实现（内存真理源）。
 *
 * **进程内真理源**：per-session 不可变消息列表缓存在本类；DB 是 checkpoint。
 * - 读：命中内存直接返回（不可变列表 + volatile 引用，并发安全）；未命中在
 *   [Dispatchers.IO] 上直接全量读 + 解码（**不排进单写队列**——冷读不再阻塞写、
 *   写也不再阻塞其它未命中读），per-session single-flight 锁合并并发未命中。
 * - 写：全部经单写队列，**定向写**——transform 在内存列表上执行，落库只写差异
 *   （公共前缀不动；中段删除 + 尾部平移 + 中段插入，单事务），
 *   commit 成功后原子换内存引用；失败内存不动、异常上抛
 *   （保留"未落库不得确认交付"）。
 * - **缓存淘汰**：accessOrder LRU，容量 [capacity]（默认 [LRU_CAPACITY]）。
 *   每次插入后 size 超容量即从最久未访问开始淘汰；淘汰仅丢内存工作集，
 *   DB 是 checkpoint——零数据丢失，重读即完整重水合。
 * - **seq 密集不变量**：seq = 列表位置（0..n-1 无空洞）；append 的 seq = 列表长度。
 *
 * **冷读（load 未命中）与写线程 hydrate 的竞态安全分析**：
 * 读线程未命中时经 [sessionLoadLocks]（per-session single-flight）读 DB + 解码，
 * 以 `cache.computeIfAbsent(sessionId) { SessionEntry(loaded) }` 放缓存；写线程在
 * 单写队列内 hydrate/swap 同样用 computeIfAbsent。并发正确性锚点是
 * **computeIfAbsent 不覆盖已有条目**：
 * 1. 若写线程已放入/更新条目，读线程的 computeIfAbsent 返回写线程版本——本线程的
 *    stale 读集被丢弃，绝不覆盖更长的列表（不会把已落库的新消息从内存抹掉）；
 * 2. 若读线程先放入，写线程随后在单写线程内 hydrate 命中该条目继续 append / swap
 *    换引用（单写线程串行性 + volatile 引用保证不丢消息）。
 * single-flight 只是省重复解码（性能优化），不是正确性来源。
 *
 * @param db 数据库管理器（由 Mederi 装配层创建并共享，写操作唯一串行入口）。
 * @param json JSON 序列化器，用于序列化 Message 对象。
 * @param capacity LRU 缓存容量（会话条数），超出后淘汰最久未访问（测试注入小容量）。
 */
class SqliteHistoryStore(
    private val db: DatabaseManager,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
    private val capacity: Int = LRU_CAPACITY,
) : HistoryStore {

    init {
        require(capacity > 0) { "capacity must be > 0, got $capacity" }
    }

    private val queries = db.database.dataDatabaseQueries

    /** 定向写测试观测钩子（internal，生产路径零成本）：记录逻辑写操作名，测试 clear 后计数。 */
    internal val writeOpLog = ConcurrentLinkedQueue<String>()

    /** per-session 缓存条目：不可变消息列表（写穿透后原子换引用）。 */
    private class SessionEntry(@Volatile var messages: List<Message>)

    /** cache（LinkedHashMap）全部结构性访问的串行化对象锁——访问极短，一把锁足够。 */
    private val cacheLock = Any()

    /** accessOrder LRU 缓存：get/computeIfAbsent 命中会把条目移到链表尾（最近使用）。 */
    private val cache = LinkedHashMap<String, SessionEntry>(16, 0.75f, true)

    /** per-session single-flight：并发冷读未命中会话时只解码一次（性能优化，非正确性）。 */
    private val sessionLoadLocks = ConcurrentHashMap<String, Mutex>()

    private companion object {
        /** LRU 缓存容量（会话条数）：超出后从最久未访问开始淘汰。 */
        const val LRU_CAPACITY = 8
    }

    /** 测试钩子：缓存驻留观测。 */
    internal fun isCached(sessionId: String): Boolean = synchronized(cacheLock) { cache.containsKey(sessionId) }

    override suspend fun load(sessionId: String): List<Message> {
        // 快路径：命中内存直接返回（get 在 accessOrder 模式会移动到链表尾 = touch，须在锁内）
        synchronized(cacheLock) {
            val hit = cache[sessionId]
            if (hit != null) return hit.messages
        }
        // 未命中：per-session single-flight + 双检（等待期间可能已被其它读/写水合）
        return sessionLoadLocks.computeIfAbsent(sessionId) { Mutex() }.withLock {
            synchronized(cacheLock) {
                val hit = cache[sessionId]
                if (hit != null) return hit.messages
            }
            // 冷读：全量读 + 解码，经 IO 线程直接执行——不占单写队列
            // （与写线程并存由 sqlite busy_timeout 兜底；hydrate 写路径读库语义完全不变）
            val loaded = withContext(Dispatchers.IO) {
                queries.loadBySession(sessionId).executeAsList().map { row ->
                    json.decodeFromString<Message>(row.payload)
                }
            }
            synchronized(cacheLock) {
                // 竞态安全锚点：computeIfAbsent 不覆盖已有条目（见类 KDoc）
                val entry = cache.computeIfAbsent(sessionId) { SessionEntry(loaded) }
                evictOverCapacityLocked()
                entry.messages
            }
        }
    }

    /**
     * 水合（写线程上执行）：缓存命中 → touch + 返回；未命中 → 全量读 + 解码 → 放缓存。
     * 与读线程 load 的竞态安全分析见类 KDoc。
     */
    private fun hydrate(sessionId: String): List<Message> {
        synchronized(cacheLock) {
            val hit = cache[sessionId]
            if (hit != null) return hit.messages
        }
        val loaded = queries.loadBySession(sessionId).executeAsList().map { row ->
            json.decodeFromString<Message>(row.payload)
        }
        synchronized(cacheLock) {
            val entry = cache.computeIfAbsent(sessionId) { SessionEntry(loaded) }
            evictOverCapacityLocked()
            return entry.messages
        }
    }

    /** commit 成功后原子换缓存引用（必须只在单写线程调用；computeIfAbsent 命中即 touch）。 */
    private fun swap(sessionId: String, messages: List<Message>) {
        synchronized(cacheLock) {
            cache.computeIfAbsent(sessionId) { SessionEntry(emptyList()) }.messages = messages
            evictOverCapacityLocked()
        }
    }

    /**
     * LRU 容量淘汰（必须在 [cacheLock] 内调用）：size 超容量时从最久未访问开始迭代删除
     * （accessOrder 链头 = 最久未访问）。淘汰仅丢内存工作集，DB 是 checkpoint——零数据丢失。
     */
    private fun evictOverCapacityLocked() {
        while (cache.size > capacity) {
            val it = cache.entries.iterator()
            it.next()
            it.remove()
        }
    }

    override suspend fun append(sessionId: String, message: Message): Unit = db.write {
        val list = hydrate(sessionId)
        // seq 密集不变量：seq = 列表长度（0..n-1 无空洞）
        queries.transaction {
            queries.appendMessage(
                session_id = sessionId,
                seq = list.size.toLong(),
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
        writeOpLog.add("insert")
        swap(sessionId, list + message)
    }

    override suspend fun updateMessage(sessionId: String, messageId: String, transform: (Message) -> Message): Unit = db.write {
        val list = hydrate(sessionId)
        val idx = list.indexOfFirst { it.id == messageId }
        check(idx >= 0) { "Message not found: $messageId" }
        val updated = transform(list[idx])
        queries.updateMessagePayload(json.encodeToString(updated), updated.status.name, sessionId, messageId)
        writeOpLog.add("update-payload")
        swap(sessionId, list.toMutableList().apply { set(idx, updated) })
    }

    override suspend fun updateHistory(sessionId: String, transform: (List<Message>) -> List<Message>): Unit = db.write {
        val before = hydrate(sessionId)
        val after = transform(before)
        if (after != before) applyDiff(sessionId, before, after)
    }

    /**
     * 定向写：公共前缀 p / 公共后缀 s 不动；旧中段范围删除；
     * 尾部两阶段负偏移平移（SQLite 单语句 PK 上移必冲突，负值区无冲突）；
     * 新中段逐条插入。单事务 commit 成功后换内存引用。
     */
    private fun applyDiff(sessionId: String, old: List<Message>, new: List<Message>) {
        var p = 0
        while (p < old.size && p < new.size && old[p] == new[p]) p++
        var s = 0
        while (s < old.size - p && s < new.size - p &&
            old[old.size - 1 - s] == new[new.size - 1 - s]
        ) s++
        val oldRegionSize = old.size - p - s
        val newRegionSize = new.size - p - s
        val delta = newRegionSize - oldRegionSize

        val ops = mutableListOf<String>()
        queries.transaction {
            // 1. 范围删除旧中段
            if (oldRegionSize > 0) {
                queries.deleteRegion(sessionId, p.toLong(), (p + oldRegionSize).toLong())
                ops.add("delete-range")
            }
            // 2. 尾部平移（delta≠0 且后缀非空）：两阶段负偏移
            if (delta != 0 && s > 0) {
                queries.shiftToNegative(sessionId, (old.size - s).toLong())
                queries.shiftFromNegative(delta.toLong(), sessionId)
                ops.add("shift")
            }
            // 3. 新中段逐条插入（seq = p..p+newRegionSize-1）
            for (i in 0 until newRegionSize) {
                val m = new[p + i]
                queries.appendMessage(sessionId, (p + i).toLong(), m.id, m.role.name,
                    extractContent(m), json.encodeToString(m), m.createdAt,
                    m.modelId, m.durationMs, m.finishReason, m.status.name)
            }
            if (newRegionSize > 0) ops.add("insert")
        }
        // commit 成功后才记观测 + 换引用（失败抛异常，内存不动、日志不记）
        writeOpLog.addAll(ops)
        swap(sessionId, new)
    }

    override suspend fun replace(sessionId: String, messages: List<Message>): Unit = db.write {
        queries.transaction {
            queries.deleteAllBySession(sessionId)
            messages.forEachIndexed { index, message ->
                queries.appendMessage(sessionId, index.toLong(), message.id, message.role.name,
                    extractContent(message), json.encodeToString(message), message.createdAt,
                    message.modelId, message.durationMs, message.finishReason, message.status.name)
            }
        }
        writeOpLog.add("delete-all")
        swap(sessionId, messages)
    }

    override suspend fun delete(sessionId: String): Unit = db.write {
        queries.deleteBySession(sessionId)
        synchronized(cacheLock) { cache.remove(sessionId) }
    }

    override suspend fun rollbackTo(sessionId: String, seq: Long):Unit = db.write {
        queries.rollbackTo(sessionId, seq)
        // 缓存命中时读 pre-DELETE 列表再截断；未命中时 DELETE 已生效，水合即截断后的真相
        val current = synchronized(cacheLock) { cache[sessionId]?.messages } ?: hydrate(sessionId)
        swap(sessionId, current.take(seq.toInt() + 1))
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