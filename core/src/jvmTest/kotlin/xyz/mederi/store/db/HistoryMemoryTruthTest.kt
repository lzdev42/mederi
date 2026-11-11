package xyz.mederi.store.db

import app.cash.sqldelight.Query as SqlDelightQuery
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import xyz.mederi.Mederi
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.MessageStatus
import xyz.mederi.store.sqlite.SqliteHistoryStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ST2：HistoryStore 内存真理源 + 定向写的行为断言。
 *
 * ① updateHistory 每工具轮落库代价 O(1)（定向写：尾部追加 1 操作；pending 前插入 2 操作）
 * ② 任意混合操作序列后，内存真理源 == DB 真相（重开实例逐条比对）
 * ③ 写失败内存不动、错误上抛（"未落库不得确认交付"），DB 事务回滚
 */
class HistoryMemoryTruthTest {

    private fun message(id: String, text: String = "body of $id") = Message(
        id = id,
        sessionId = "n/a",
        role = MessageRole.USER,
        parts = listOf(MessagePart.Text(text)),
        createdAt = "2026-11-09T00:00:00Z"
    )

    private fun newStore(dbFile: File): Triple<JdbcSqliteDriver, DatabaseManager, SqliteHistoryStore> {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        MederiDataDatabase.Schema.create(driver)
        Mederi.migrateDataDb(driver)
        val db = DatabaseManager(driver)
        return Triple(driver, db, SqliteHistoryStore(db))
    }

    @Test
    fun toolRoundUpdateHistoryIsTargetedWrite() = runBlocking {
        val dbFile = File.createTempFile("mederi-dbm-st2a", ".db")
        dbFile.deleteOnExit()
        val (driver, _, store) = newStore(dbFile)
        val sid = "sess_o1"
        try {
            repeat(100) { i -> store.append(sid, message("m_$i")) }

            // 场景一：纯追加（无 pending）——仅 1 条 INSERT
            store.writeOpLog.clear()
            store.updateHistory(sid) { it + message("m_new") }
            val ops1 = store.writeOpLog.toList()
            assertEquals(1, ops1.size, "tail append must be exactly 1 write op, got $ops1")
            assertEquals("insert", ops1[0])

            // 场景二：pending 前插入——1 条 INSERT + 1 次尾部平移（逻辑操作）
            repeat(3) { i -> store.append(sid, message("p_$i").copy(pendingSteering = true)) }
            store.writeOpLog.clear()
            store.updateHistory(sid) { h ->
                val idx = h.indexOfFirst { it.pendingSteering }
                h.take(idx) + message("m_mid") + h.drop(idx)
            }
            val ops2 = store.writeOpLog.toList().sorted()
            assertEquals(listOf("insert", "shift"), ops2, "pending insert must be exactly insert+shift, got $ops2")
            val c1 = ops1.size
            val c2 = ops2.size
            TestObservations.record(
                "DBM_TOOL_ROUND_O1",
                "tail_insert_${c1}stmt_pending_insert_${c2}stmts"
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun memoryEqualsDbAfterMixedOps() = runBlocking {
        val dbFile = File.createTempFile("mederi-dbm-st2b", ".db")
        dbFile.deleteOnExit()
        val (driver, _, store) = newStore(dbFile)
        val sid = "sess_mixed"
        try {
            repeat(20) { i -> store.append(sid, message("m_$i")) }
            // append ×3
            store.append(sid, message("a_1"))
            store.append(sid, message("a_2"))
            store.append(sid, message("a_3"))
            // updateMessage ×2（改状态）
            store.updateMessage(sid, "m_5") { it.copy(status = MessageStatus.ERROR) }
            store.updateMessage(sid, "m_6") { it.copy(status = MessageStatus.ERROR) }
            // updateHistory 中部插入 1 条（idx=10）
            store.updateHistory(sid) { h -> h.take(10) + message("m_mid") + h.drop(10) }
            // rollbackTo(15)：保留 seq ≤ 15（前 16 条）
            store.rollbackTo(sid, 15)
            // replace(前 12 条)
            val kept = store.load(sid).take(12)
            store.replace(sid, kept)

            val memory = store.load(sid)
            // 重开第二个实例（同一文件）水合 → 与内存逐条比对
            val reopenDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
            try {
                MederiDataDatabase.Schema.create(reopenDriver) // IF NOT EXISTS 幂等
                Mederi.migrateDataDb(reopenDriver)
                val reopenStore = SqliteHistoryStore(DatabaseManager(reopenDriver))
                val fromDb = reopenStore.load(sid)
                assertEquals(memory.size, fromDb.size, "size mismatch: memory=${memory.size}, db=${fromDb.size}")
                memory.zip(fromDb).forEachIndexed { i, (m, d) ->
                    assertEquals(m, d, "message at $i diverges: memory=$m, db=$d")
                }
                assertEquals(memory, fromDb)
            } finally {
                reopenDriver.close()
            }
            TestObservations.record("DBM_MEMORY_EQUALS_DB", "memory_equals_db_true")
        } finally {
            driver.close()
        }
    }

    @Test
    fun writeFailureLeavesMemoryUnchanged() = runBlocking {
        val dbFile = File.createTempFile("mederi-dbm-st2c", ".db")
        dbFile.deleteOnExit()
        val realDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        MederiDataDatabase.Schema.create(realDriver)
        Mederi.migrateDataDb(realDriver)
        // 故障注入：armed 后首次 message_history INSERT 抛错（applyDiff 中段插入路径）
        val faultDriver = FaultInjectingDriver(realDriver)
        val db = DatabaseManager(faultDriver)
        val store = SqliteHistoryStore(db)
        val sid = "sess_fail"
        try {
            repeat(5) { i -> store.append(sid, message("m_$i")) }
            val snapshot = store.load(sid)

            faultDriver.armed = true
            assertFailsWith<RuntimeException> { store.updateHistory(sid) { it + message("boom") } }

            // 内存不动（写失败不换引用）
            assertEquals(snapshot, store.load(sid), "memory must be unchanged after failed write")
            // DB 不动（事务回滚）：真 driver 重开同一文件
            val reopenDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
            try {
                MederiDataDatabase.Schema.create(reopenDriver)
                Mederi.migrateDataDb(reopenDriver)
                val reopenStore = SqliteHistoryStore(DatabaseManager(reopenDriver))
                assertEquals(snapshot, reopenStore.load(sid), "db must be unchanged after failed write (tx rollback)")
            } finally {
                reopenDriver.close()
            }
            TestObservations.record("DBM_WRITE_FAIL_ATOMIC", "fail_memory_unchanged_true")
        } finally {
            realDriver.close()
        }
    }

    /** 故障注入 driver（参考 WorkspaceInvariantIntegrationTest 同名模式）：armed 后首次 message_history 写语句抛错。 */
    private class FaultInjectingDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver {
        @Volatile var armed = false

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?
        ): QueryResult<R> = delegate.executeQuery(identifier, sql, mapper, parameters, binders)

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?
        ): QueryResult<Long> {
            if (armed && sql.contains("message_history")) {
                armed = false
                throw RuntimeException("injected write failure: $sql")
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }

        override fun newTransaction(): QueryResult<Transacter.Transaction> = delegate.newTransaction()
        override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()
        override fun addListener(vararg queryKeys: String, listener: SqlDelightQuery.Listener) =
            delegate.addListener(*queryKeys, listener = listener)
        override fun removeListener(vararg queryKeys: String, listener: SqlDelightQuery.Listener) =
            delegate.removeListener(*queryKeys, listener = listener)
        override fun notifyListeners(vararg queryKeys: String) = delegate.notifyListeners(*queryKeys)
        override fun close() = delegate.close()
    }
}
