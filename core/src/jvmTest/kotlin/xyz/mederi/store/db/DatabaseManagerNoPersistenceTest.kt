package xyz.mederi.store.db

import app.cash.sqldelight.db.QueryResult
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

/**
 * ST4：无持久化会话模式——注册后纯内存可读、共享 DB 零行。
 */
class DatabaseManagerNoPersistenceTest {

    private fun message(id: String) = Message(
        id = id,
        sessionId = "sub_x",
        role = MessageRole.USER,
        parts = listOf(MessagePart.Text("body of $id")),
        createdAt = "2026-11-09T00:00:00Z"
    )

    @Test
    fun noPersistenceSessionStaysInMemoryOnly() = runBlocking {
        val dbFile = File.createTempFile("mederi-dbm-st4", ".db")
        dbFile.deleteOnExit()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            MederiDataDatabase.Schema.create(driver)
            Mederi.migrateDataDb(driver)
            val db = DatabaseManager(driver)
            val store = SqliteHistoryStore(db)
            val ephemeral = EphemeralHistoryStore(store)

            // 无持久化会话注册：此后该会话纯内存
            ephemeral.registerEphemeral("sub_x")
            repeat(10) { i -> ephemeral.append("sub_x", message("m_$i")) }

            // 内存可读：10 条完整
            val inMemory = ephemeral.load("sub_x").size
            assertEquals(10, inMemory, "ephemeral 会话经内存后端完整可读")

            // 共享 DB 零行：该 session_id 从未落库
            val dbRows = driver.executeQuery(
                null,
                "SELECT COUNT(*) FROM message_history WHERE session_id = 'sub_x'",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else 0L) },
                0
            ).value
            assertEquals(0L, dbRows, "ephemeral 会话必须零行落库")

            TestObservations.record("DBM_NO_PERSIST_SESSION", "in_memory_${inMemory}_db_rows_${dbRows}")
        } finally {
            driver.close()
        }
    }
}
