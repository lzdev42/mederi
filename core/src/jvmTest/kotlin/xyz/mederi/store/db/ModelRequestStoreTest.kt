package xyz.mederi.store.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import xyz.mederi.Mederi
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.ModelRequestRecord
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.store.InMemoryModelRequestStore
import xyz.mederi.store.ModelRequestStore
import xyz.mederi.store.sqlite.SqliteModelRequestStore
import xyz.mederi.store.sqlite.SqliteSessionStore
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelRequestStoreTest {
    @Test
    fun inMemoryRecordsFollowRequestStartOrder() = runBlocking {
        val store = InMemoryModelRequestStore()
        assertLedgerLifecycle(store)
        store.delete("parent")
        assertEquals(emptyList(), store.list("parent"))
    }

    @Test
    fun sqliteMigrationKeepsRequestsAcrossReopenAndDeletesWithOwner() = runBlocking {
        val file = File.createTempFile("mederi-model-requests", ".db")
        try {
            withDatabase(file) { db ->
                // 模拟升级前已存在会话且尚无请求表的 v5 数据库。
                SqliteSessionStore(db).insert(session())
                db.write {
                    db.driver.execute(null, "DROP TABLE model_requests", 0)
                    db.driver.execute(null, "PRAGMA user_version = 5", 0)
                }
                Mederi.migrateDataDb(db.driver)
                assertLedgerLifecycle(SqliteModelRequestStore(db))
            }
            withDatabase(file) { db ->
                val store = SqliteModelRequestStore(db)
                assertEquals(listOf("req-z", "req-a"), store.list("parent").map { it.requestId })
                assertEquals(42, store.list("parent").first().usage?.inputTokens)
                assertNull(store.list("parent").last().usage)
                SqliteSessionStore(db).delete("parent")
                assertEquals(emptyList(), store.list("parent"))
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun assertLedgerLifecycle(store: ModelRequestStore) {
        val startUsage = usage(modelRequestCompleted = false)
        val main = ModelRequestRecord("req-z", "parent", "parent", now, startUsage)
        val child = ModelRequestRecord("req-a", "parent", "ephemeral-child", now)
        store.start(main)
        store.start(child)
        store.start(main.copy(usage = null))
        assertEquals(listOf("req-z", "req-a"), store.list("parent").map { it.requestId })
        assertEquals("ephemeral-child", store.list("parent").last().executionSessionId)
        assertEquals(startUsage.copy(parts = emptyList()), store.list("parent").first().usage)
        assertEquals(emptyList(), store.list("ephemeral-child"))

        store.complete("parent", "req-z", usage(modelRequestCompleted = true).copy(inputTokens = 42))
        kotlinx.coroutines.coroutineScope {
            (1..8).map { tokens ->
                async {
                    store.complete("parent", "req-z", usage(modelRequestCompleted = true).copy(inputTokens = tokens))
                }
            }.awaitAll()
        }
        assertEquals(42, store.list("parent").first().usage?.inputTokens)
        assertNull(store.list("parent").last().usage)
    }

    private suspend fun withDatabase(file: File, block: suspend (DatabaseManager) -> Unit) {
        val properties = Properties().apply { setProperty("foreign_keys", "true") }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", properties)
        try {
            MederiDataDatabase.Schema.create(driver)
            val db = DatabaseManager(driver)
            try {
                block(db)
            } finally {
                db.drainAndClose(2_000)
            }
        } finally {
            driver.close()
        }
    }

    private fun session() = Session(
        id = "parent", projectId = "project", title = "", status = SessionStatus.IDLE,
        agentMode = AgentMode.AUTONOMOUS, aiModel = null, reasoningLevel = null, createdAt = now, updatedAt = now,
    )

    private fun usage(modelRequestCompleted: Boolean) = Message(
        id = "req-z", sessionId = "parent", role = MessageRole.ASSISTANT,
        parts = listOf(MessagePart.Text("private response text")), createdAt = now,
        modelRequestCompleted = modelRequestCompleted,
    )

    private companion object {
        const val now = "2026-11-13T00:00:00Z"
    }
}
