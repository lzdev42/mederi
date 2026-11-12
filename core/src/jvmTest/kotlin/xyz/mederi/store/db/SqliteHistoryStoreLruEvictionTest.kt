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
 * ST6：LRU 容量淘汰（B 方案），capacity=2 注入：
 * ① 打开 capacity+1 个会话 → 最久未访问被淘汰（isCached == false），其余驻留；
 *    重读完整重水合（DB 是 checkpoint，零数据丢失）。
 * ② touch 旧会话后再开新会话 → 被淘汰的是次旧而非 touch 过的。
 */
class SqliteHistoryStoreLruEvictionTest {

    private fun message(id: String) = Message(
        id = id,
        sessionId = "n/a",
        role = MessageRole.USER,
        parts = listOf(MessagePart.Text("body of $id")),
        createdAt = "2026-11-09T00:00:00Z"
    )

    /** 建库 + store（LRU 小容量注入）。 */
    private fun newStore(dbFile: File, capacity: Int = 2): SqliteHistoryStore {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        MederiDataDatabase.Schema.create(driver)
        Mederi.migrateDataDb(driver)
        return SqliteHistoryStore(DatabaseManager(driver), capacity = capacity)
    }

    @Test
    fun oldestSessionEvictedWhenCapacityExceeded() = runBlocking {
        val dbFile = File.createTempFile("mederi-dbm-lru-a", ".db")
        dbFile.deleteOnExit()
        val store = newStore(dbFile, capacity = 2)
        val sid = "s1"

        // s1 先驻留且有 5 条消息；打开 s2、s3（capacity=2）→ 最久未访问 s1 被淘汰
        repeat(5) { i -> store.append(sid, message("m_$i")) }
        store.load("s2")
        store.load("s3")

        assertFalse(store.isCached("s1"), "最旧会话 s1 必须被淘汰")
        assertTrue(store.isCached("s2"), "s2 必须驻留")
        assertTrue(store.isCached("s3"), "最新 s3 必须驻留")

        // 淘汰仅丢内存工作集：重读完整重水合（零数据丢失）
        val reloaded = store.load(sid)
        assertEquals(5, reloaded.size, "淘汰后重读必须完整重水合全部 5 条")

        TestObservations.record("SQLH_STORE_LRU_OLDEST", "evicted_s1_rehydrated_${reloaded.size}")
    }

    @Test
    fun touchedSessionSurvivesEviction() = runBlocking {
        val dbFile = File.createTempFile("mederi-dbm-lru-b", ".db")
        dbFile.deleteOnExit()
        val store = newStore(dbFile, capacity = 2)

        store.load("s1")           // 驻留 s1（最旧）
        store.load("s2")           // 驻留 s2
        store.load("s1")           // touch s1 → LRU 序变为 s2, s1
        store.load("s3")           // 超容量 → 必须淘汰 s2（次旧），s1 因 touch 存活

        assertFalse(store.isCached("s2"), "被淘汰的是次旧 s2，而非 touch 过的 s1")
        assertTrue(store.isCached("s1"), "touch 过的 s1 必须保留")
        assertTrue(store.isCached("s3"), "s3 必须驻留")

        TestObservations.record("SQLH_STORE_LRU_TOUCH", "touched_kept_evicted_s2")
    }
}