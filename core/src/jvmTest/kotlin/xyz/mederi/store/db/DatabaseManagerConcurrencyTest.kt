package xyz.mederi.store.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import xyz.mederi.Mederi
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.MessageStatus
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.store.sqlite.SqliteHistoryStore
import xyz.mederi.store.sqlite.SqliteSessionStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ST1 并发写压测（原 bug 回归锚点）：
 *
 * 单写队列下 8 协程并发混合写（append + updateMessage）跨 2 个 session，
 * 零 SQLITE_BUSY 且全部落库——用第二个 DatabaseManager 实例重开同一文件对账。
 *
 * 旧实现（4 个 store 直写共享 driver、零锁、busy_timeout=0）在此负载下
 * 会抛 [org.sqlite.SQLiteException]（database is locked）或 seq 主键冲突。
 */
class DatabaseManagerConcurrencyTest {

    private fun session(id: String) = Session(
        id = id, projectId = "proj_1", title = "T $id", status = SessionStatus.IDLE,
        agentMode = AgentMode.AUTONOMOUS, aiModel = null, reasoningLevel = null,
        createdAt = "2026-11-09T00:00:00Z", updatedAt = "2026-11-09T00:00:00Z"
    )

    private fun message(id: String) = Message(
        id = id,
        sessionId = "n/a",
        role = MessageRole.USER,
        parts = listOf(MessagePart.Text("body of $id")),
        createdAt = "2026-11-09T00:00:00Z"
    )

    @Test
    fun `parallel writes never SQLITE_BUSY and all durable`() = runBlocking {
        val dbFile = File.createTempFile("mederi-dbm-conc", ".db")
        dbFile.deleteOnExit()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        MederiDataDatabase.Schema.create(driver)
        Mederi.migrateDataDb(driver)
        val db = DatabaseManager(driver)
        val historyStore = SqliteHistoryStore(db)
        val sessionStore = SqliteSessionStore(db)
        sessionStore.insert(session("sess_a"))
        sessionStore.insert(session("sess_b"))

        // 8 协程 × (50 append + 25 updateMessage)，并发提交（每协程只碰自己的消息，行级无冲突）
        (0 until 8).map { i ->
            async(Dispatchers.Default) {
                val sid = if (i % 2 == 0) "sess_a" else "sess_b"
                repeat(50) { n -> historyStore.append(sid, message("m_${i}_$n")) }
                repeat(25) { n ->
                    historyStore.updateMessage(sid, "m_${i}_$n") { it.copy(status = MessageStatus.ERROR) }
                }
            }
        }.awaitAll() // 任一异常（含 SQLITE_BUSY / 主键冲突）直接判败

        // 重开第二个实例对账：磁盘真相完整（写穿透，DB 是 checkpoint）
        driver.close()
        val reopenDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            MederiDataDatabase.Schema.create(reopenDriver) // IF NOT EXISTS 幂等
            Mederi.migrateDataDb(reopenDriver)
            val reopenDb = DatabaseManager(reopenDriver)
            val reopenHistory = SqliteHistoryStore(reopenDb)
            val countA = reopenHistory.load("sess_a").size
            val countB = reopenHistory.load("sess_b").size
            assertEquals(200, countA, "sess_a = 4 协程 × 50 append")
            assertEquals(200, countB, "sess_b = 4 协程 × 50 append")
            // updateMessage 的字段变更已持久化（每协程前 25 条被改状态）
            val updated = reopenHistory.load("sess_a").first { it.id == "m_0_0" }
            assertEquals(MessageStatus.ERROR, updated.status)
            // 断言全过后记录实测值（真实观测：awaitAll 零异常 + 重开实例计数），非硬编码
            TestObservations.record("DBM_PARALLEL_WRITES", "0_exceptions_sess_a_${countA}_sess_b_${countB}")
        } finally {
            reopenDriver.close()
        }
    }

    @Test
    fun `busy timeout pragma applied`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val db = DatabaseManager(driver)
            val value = db.driver.executeQuery(
                null,
                "PRAGMA busy_timeout",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
                0
            ).value
            assertEquals(5000L, value, "busy_timeout 跨进程兜底必须生效")
            TestObservations.record("DBM_BUSY_TIMEOUT_PRAGMA", value.toString())
        } finally {
            driver.close()
        }
    }
}
