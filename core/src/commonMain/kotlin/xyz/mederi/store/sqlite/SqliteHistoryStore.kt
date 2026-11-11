package xyz.mederi.store.sqlite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.MessageSummary
import xyz.mederi.store.RawMessageRecord
import xyz.mederi.store.db.DatabaseManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 基于 SQLDelight + SQLite 的 HistoryStore 实现（内存真理源）。
 *
 * **进程内真理源**：per-session 不可变消息列表缓存在本类；DB 是 checkpoint。
 * - 读：命中内存直接返回（无队列、并发安全——不可变列表 + volatile 引用）；
 *   未命中经 [DatabaseManager.write] 单写队列水合（天然 single-flight）。
 * - 写：全部经单写队列，**定向写**——transform 在内存列表上执行，落库只写差异
 *   （公共前缀不动；中段删除 + 尾部平移 + 中段插入，单事务），
 *   commit 成功后原子换内存引用；失败内存不动、异常上抛
 *   （保留"未落库不得确认交付"）。
 * - **seq 密集不变量**：seq = 列表位置（0..n-1 无空洞）；append 的 seq = 列表长度。
 *
 * @param db 数据库管理器（由 Mederi 装配层创建并共享，写操作唯一串行入口）。
 * @param json JSON 序列化器，用于序列化 Message 对象。
 */
class SqliteHistoryStore(
    private val db: DatabaseManager,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
    /** 事件时钟（测试注入 fake clock 用；生产默认系统时钟）。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** 生产默认开启周期淘汰扫描；测试关掉（手动调 evictIdleOnce 断言）。 */
    evictionEnabled: Boolean = true,
) : HistoryStore {

    private val queries = db.database.dataDatabaseQueries

    /** 定向写测试观测钩子（internal，生产路径零成本）：记录逻辑写操作名，测试 clear 后计数。 */
    internal val writeOpLog = ConcurrentLinkedQueue<String>()

    /** per-session 缓存条目：不可变消息列表（写穿透后原子换引用）+ 最近触摸时间（淘汰用）。 */
    private class SessionEntry(@Volatile var messages: List<Message>, @Volatile var lastTouch: Long)

    private val cache = ConcurrentHashMap<String, SessionEntry>()

    private companion object {
        /** 淘汰扫描间隔（生产周期循环）。 */
        const val EVICTION_SCAN_INTERVAL_MS = 10 * 60_000L

        /** 闲置阈值：超过此时长未读未写的会话缓存被淘汰（DB 是 checkpoint，零数据丢失）。 */
        const val EVICTION_IDLE_MS = 10 * 60_000L
    }

    private val evictionScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    init {
        if (evictionEnabled) {
            evictionScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(EVICTION_SCAN_INTERVAL_MS)
                    // DatabaseManager 关闭后 db.write 抛 IllegalStateException——退出循环（自然生命周期）
                    try {
                        evictIdleOnce()
                    } catch (e: IllegalStateException) {
                        break
                    }
                }
            }
        }
    }

    /**
     * 淘汰闲置超时（>[EVICTION_IDLE_MS] 未读未写）的会话缓存。
     * 经单写队列与 hydrate/swap 串行（结构消除竞态）；淘汰 = 丢工作集，DB 是 checkpoint，零数据丢失。
     */
    internal suspend fun evictIdleOnce() {
        db.write {
            val cutoff = clock() - EVICTION_IDLE_MS
            cache.entries.filter { it.value.lastTouch < cutoff }
                .map { it.key }.forEach { cache.remove(it) }
        }
    }

    /** 测试钩子：缓存驻留观测。 */
    internal fun isCached(sessionId: String): Boolean = cache.containsKey(sessionId)

    override suspend fun load(sessionId: String): List<Message> {
        // 快路径：命中内存直接返回（volatile 不可变引用，并发读安全）
        cache[sessionId]?.let { entry ->
            entry.lastTouch = clock()
            return entry.messages
        }
        // 未命中：经单写队列水合（天然 single-flight——并发未命中的第二个排队者复查缓存）
        return db.write { hydrate(sessionId) }
    }

    /**
     * 水合（必须在 [DatabaseManager.write] 单写线程内执行）：
     * 缓存命中 → touch + 返回；未命中 → 全量读 + 解码 → 放缓存。
     */
    private fun hydrate(sessionId: String): List<Message> {
        cache[sessionId]?.let { entry ->
            entry.lastTouch = clock()
            return entry.messages
        }
        val loaded = queries.loadBySession(sessionId).executeAsList().map { row ->
            json.decodeFromString<Message>(row.payload)
        }
        return cache.computeIfAbsent(sessionId) { SessionEntry(loaded, clock()) }.messages
    }

    /** commit 成功后原子换缓存引用（必须只在单写线程调用）。 */
    private fun swap(sessionId: String, messages: List<Message>) {
        cache.computeIfAbsent(sessionId) { SessionEntry(emptyList(), clock()) }
            .apply { this.messages = messages; this.lastTouch = clock() }
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
        cache.remove(sessionId)
    }

    override suspend fun rollbackTo(sessionId: String, seq: Long):Unit = db.write {
        queries.rollbackTo(sessionId, seq)
        // 缓存命中时读 pre-DELETE 列表再截断；未命中时 DELETE 已生效，水合即截断后的真相
        val current = cache[sessionId]?.messages ?: hydrate(sessionId)
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
