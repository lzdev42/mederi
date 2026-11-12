package xyz.mederi.store.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import xyz.mederi.Mederi
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.store.sqlite.SqliteHistoryStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ST3：淘汰语义（自旧"闲置定时器"改写为 LRU 容量淘汰，capacity=2 注入）：
 * ① 打开超容量会话 → 最久未访问被淘汰且重读完整重水合（DB 是 checkpoint，零数据丢失）
 * ② 写计入 touch——最近写过的会话不被淘汰（活跃 turn 保护）
 * ③ 淘汰后的写 = 惰性重水合 + 定向写，结果正确
 */
class DatabaseManagerEvictionTest {

    private fun message(id: String) = Message(
        id = id,
        sessionId = "n/a",
        role = MessageRole.USER,
        parts = listOf(MessagePart.Text("body of $id")),
        createdAt = "2026-11-09T00:00:00Z"
    )

    /** 建库 + store（LRU 容量注入：测试可观测的淘汰行为）。 */
    private fun newStore(dbFile: File, capacity: Int = 2): SqliteHistoryStore {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        MederiDataDatabase.Schema.create(driver)
        Mederi.migrateDataDb(driver)
        val db = DatabaseManager(driver)
        return SqliteHistoryStore(db = db, capacity = capacity)
    }

    @Test
    fun evictedSessionRehydratesCompletely() = runBlocking {
        val dbFile = File.createTempFile("mederi-dbm-st3a", ".db")
        dbFile.deleteOnExit()
        val store = newStore(dbFile)
        val sid = "sess_evict"

        repeat(50) { i -> store.append(sid, message("m_$i")) }
        // 打开另外 2 个会话（capacity=2）→ 最久未访问（sid）被淘汰
        store.load("other_1")
        store.load("other_2")
        assertFalse(store.isCached(sid), "最久未访问会话必须被淘汰出缓存")

        // 重读 → 完整重水合（DB 是 checkpoint，零丢失）
        val reloaded = store.load(sid)
        assertEquals(50, reloaded.size, "淘汰后 load 必须重水合全部 50 条")
        assertTrue(store.isCached(sid), "load 后重新驻留缓存")

        TestObservations.record("DBM_EVICTION_IDLE", "lru_evicted_then_hydrated_${reloaded.size}")
    }

    @Test
    fun writeTouchesSoActiveSessionNotEvicted() = runBlocking {
        val dbFile = File.createTempFile("mederi-dbm-st3b", ".db")
        dbFile.deleteOnExit()
        val store = newStore(dbFile)
        val sid = "sess_touch"

        store.append(sid, message("m_0"))   // 写入并驻留
        store.load("other_1")                // 第 2 个驻留
        store.append(sid, message("m_1"))    // 再写 sid（hydrate/swap 均计入 LRU touch）
        store.load("other_2")                // 第 3 个 → 超容量：被淘汰的是介入最旧的 other_1

        assertTrue(store.isCached(sid), "持续写入的会话不得被淘汰（写计入 touch）")
        assertFalse(store.isCached("other_1"), "被淘汰的是介入最旧的 other_1")

        TestObservations.record("DBM_EVICTION_WRITE_TOUCH", "write_touch_not_evicted_${store.isCached(sid)}")
    }

    @Test
    fun writeAfterEvictRehydratesAndInsertsCorrectly() = runBlocking {
        val dbFile = File.createTempFile("mederi-dbm-st3c", ".db")
        dbFile.deleteOnExit()
        val store = newStore(dbFile)
        val sid = "sess_rehydrate"

        repeat(20) { i -> store.append(sid, message("m_$i")) }
        store.load("other_1")
        store.load("other_2")
        assertFalse(store.isCached(sid), "预置淘汰")

        // 淘汰后写：惰性重水合 + 定向写（中部插入）
        store.updateHistory(sid) { h -> h.take(10) + message("m_mid") + h.drop(10) }

        val after = store.load(sid)
        assertEquals(21, after.size, "淘汰后写必须重水合 + 正确插入")
        assertEquals("m_mid", after[10].id, "插入位置正确（index 10）")
        assertEquals("m_9", after[9].id, "插入点前一条不变")
        assertEquals("m_10", after[11].id, "插入点后一条正确后移")

        TestObservations.record("DBM_WRITE_AFTER_EVICT", "write_after_evict_${after.size}_correct")
    }
}