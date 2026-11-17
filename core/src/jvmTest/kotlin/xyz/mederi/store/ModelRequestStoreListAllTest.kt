package xyz.mederi.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.ModelRequestRecord
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.store.db.DatabaseManager
import xyz.mederi.store.sqlite.SqliteModelRequestStore
import xyz.mederi.store.sqlite.SqliteSessionStore
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModelRequestStoreListAllTest {
    @Test
    fun inMemoryListAllCoversEveryOwnerInStartOrder() = runBlocking {
        val store = InMemoryModelRequestStore()
        seedCrossOwnerLedger(store)
        assertCrossOwnerLedger(store)
        // InMemory 按 owner 首次写入顺序拼接，各 owner 内保持 start 顺序。
        assertEquals(
            listOf("req-a1", "req-a2", "req-b1", "req-b2", "req-c1"),
            store.listAll().map { it.requestId },
        )
    }

    @Test
    fun sqliteListAllCoversEveryOwnerInSequenceOrder() = runBlocking {
        val file = File.createTempFile("mederi-model-requests-list-all", ".db")
        try {
            val properties = Properties().apply { setProperty("foreign_keys", "true") }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", properties)
            try {
                MederiDataDatabase.Schema.create(driver)
                val db = DatabaseManager(driver)
                try {
                    // model_requests.owner_session_id 有外键约束，先建 owner 会话。
                    val sessions = SqliteSessionStore(db)
                    listOf("owner-a", "owner-b", "owner-c").forEach { sessions.insert(session(it)) }
                    val store = SqliteModelRequestStore(db)
                    seedCrossOwnerLedger(store)
                    assertCrossOwnerLedger(store)
                    // SQLite 按全库 sequence 升序返回，即跨 owner 的 start 先后。
                    assertEquals(
                        listOf("req-a1", "req-b1", "req-a2", "req-c1", "req-b2"),
                        store.listAll().map { it.requestId },
                    )
                } finally {
                    db.drainAndClose(2_000)
                }
            } finally {
                driver.close()
            }
        } finally {
            file.delete()
        }
    }

    /** 3 个 owner 共 5 条记录：A1、B1 以 null usage 发起后完成，C1 未完成。 */
    private suspend fun seedCrossOwnerLedger(store: ModelRequestStore) {
        store.start(record("req-a1", "owner-a"))
        store.start(record("req-b1", "owner-b"))
        store.start(record("req-a2", "owner-a", usage(inputTokens = 11)))
        store.start(record("req-c1", "owner-c"))
        store.start(record("req-b2", "owner-b", usage(inputTokens = 12)))
        store.complete("owner-a", "req-a1", usage(inputTokens = 42, providerName = "provider-x"))
        store.complete("owner-b", "req-b1", usage(inputTokens = 7, providerName = "provider-y"))
    }

    private suspend fun assertCrossOwnerLedger(store: ModelRequestStore) {
        val all = store.listAll()
        assertEquals(5, all.size)
        assertEquals(setOf("owner-a", "owner-b", "owner-c"), all.map { it.ownerSessionId }.toSet())

        val byId = all.associateBy { it.requestId }
        val completed = byId.getValue("req-a1").usage
        assertNotNull(completed)
        assertEquals(42, completed.inputTokens)
        assertEquals("provider-x", completed.providerName)
        assertEquals(7, assertNotNull(byId.getValue("req-b1").usage).inputTokens)
        // 发起时未带 usage 且未 complete 的记录保持 null；发起时已带 usage 的记录保留该值。
        assertNull(byId.getValue("req-c1").usage)
        assertEquals(11, assertNotNull(byId.getValue("req-a2").usage).inputTokens)

        // list(owner) 语义不回归：仍只返回该 owner 的记录。
        assertEquals(listOf("req-a1", "req-a2"), store.list("owner-a").map { it.requestId })
        assertEquals(listOf("req-b1", "req-b2"), store.list("owner-b").map { it.requestId })
        assertEquals(listOf("req-c1"), store.list("owner-c").map { it.requestId })
        assertEquals(emptyList(), store.list("owner-unknown"))
    }

    private fun record(
        requestId: String,
        ownerSessionId: String,
        usage: Message? = null,
    ) = ModelRequestRecord(requestId, ownerSessionId, ownerSessionId, now, usage)

    private fun session(id: String) = Session(
        id = id, projectId = "project", title = "", status = SessionStatus.IDLE,
        agentMode = AgentMode.AUTONOMOUS, aiModel = null, reasoningLevel = null, createdAt = now, updatedAt = now,
    )

    private fun usage(inputTokens: Int, providerName: String? = null) = Message(
        id = null, sessionId = "owner-a", role = MessageRole.ASSISTANT,
        parts = listOf(MessagePart.Text("private response text")), createdAt = now,
        inputTokens = inputTokens, providerName = providerName,
    )

    private companion object {
        const val now = "2026-11-14T00:00:00Z"
    }
}
