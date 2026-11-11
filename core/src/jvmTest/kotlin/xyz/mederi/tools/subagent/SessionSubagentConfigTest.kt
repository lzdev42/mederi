package xyz.mederi.tools.subagent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import xyz.mederi.Mederi
import xyz.mederi.api.exception.MederiNotFoundException
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.domain.model.*
import xyz.mederi.provider.API_KEY_ROTATION_ID
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.store.InMemorySessionStore
import xyz.mederi.store.InMemorySettingsStore
import xyz.mederi.store.sqlite.SqliteSessionStore
import kotlin.test.*

class SessionSubagentConfigTest {
    private fun session(id: String) = Session(
        id, "project", "Chat $id", SessionStatus.IDLE, AgentMode.APPROVAL,
        null, null, draftText = "keep draft", createdAt = "2026-11-04T00:00:00Z",
        updatedAt = "2026-11-04T00:00:00Z"
    )

    @Test
    fun `unconfigured sessions follow defaults while overrides remain isolated and resettable`() = runBlocking {
        val sessions = InMemorySessionStore().also { it.insert(session("a")); it.insert(session("b")) }
        val manager = SubagentConfigManager(InMemorySettingsStore(), FakeProviderManager(emptyMap()), sessions)
        val global = SubagentModelConfig("global", ReasoningLevel.HIGH, "global-key")
        manager.set(SubagentRole.EXECUTOR, global)
        manager.setMaxConcurrentSubagents(3)
        assertEquals(global, manager.get(SubagentRole.EXECUTOR, "a"))
        assertTrue(manager.usesDefault(SubagentRole.EXECUTOR, "a"))
        assertEquals(3, manager.getMaxConcurrentSubagents("a"))

        val custom = SubagentModelConfig("local", ReasoningLevel.LOW, API_KEY_ROTATION_ID)
        manager.set(SubagentRole.EXECUTOR, custom, "a")
        manager.setMaxConcurrentSubagents(1, "a")
        manager.set(SubagentRole.EXECUTOR, global.copy(modelId = "new-default"))
        manager.setMaxConcurrentSubagents(5)
        assertEquals(custom, manager.get(SubagentRole.EXECUTOR, "a"))
        assertFalse(manager.usesDefault(SubagentRole.EXECUTOR, "a"))
        assertEquals(1, manager.getMaxConcurrentSubagents("a"))
        assertEquals("new-default", manager.get(SubagentRole.EXECUTOR, "b").modelId)
        assertEquals(5, manager.getMaxConcurrentSubagents("b"))
        assertEquals("keep draft", sessions.get("a")!!.draftText)

        manager.clear(SubagentRole.EXECUTOR, "a")
        manager.clearMaxConcurrentSubagents("a")
        assertEquals(manager.get(SubagentRole.EXECUTOR), manager.get(SubagentRole.EXECUTOR, "a"))
        assertTrue(manager.usesDefault(SubagentRole.EXECUTOR, "a"))
        assertTrue(manager.usesDefaultConcurrency("a"))
        assertEquals(5, manager.getMaxConcurrentSubagents("a"))
    }

    @Test
    fun `explicit empty role config inherits parent rather than system role config`() = runBlocking {
        val sessions = InMemorySessionStore().also { it.insert(session("a")) }
        val manager = SubagentConfigManager(InMemorySettingsStore(), FakeProviderManager(emptyMap()), sessions)
        val parent = AIModel("parent", "parent", "Parent")
        for (role in listOf(SubagentRole.EXECUTOR, SubagentRole.RESEARCHER)) {
            manager.set(role, SubagentModelConfig("global", ReasoningLevel.HIGH, "global-key"))
            manager.set(role, SubagentModelConfig(), "a")
            assertTrue(manager.get(role, "a").isInheriting)
            assertFalse(manager.usesDefault(role, "a"))
            assertEquals(parent to ReasoningLevel.LOW, manager.resolve(role, parent, ReasoningLevel.LOW, "a"))
            assertNull(manager.get(role, "a").apiKeyId)
        }
    }

    @Test
    fun `browser roles retain system defaults and reject session writes`() = runBlocking {
        val sessions = InMemorySessionStore().also { it.insert(session("a")) }
        val manager = SubagentConfigManager(InMemorySettingsStore(), FakeProviderManager(emptyMap()), sessions)
        val global = SubagentModelConfig("browser-global", ReasoningLevel.HIGH, "browser-key")
        for (role in listOf(SubagentRole.BROWSER_OPERATOR, SubagentRole.BROWSER_BRAIN)) {
            manager.set(role, global)
            assertEquals(global, manager.get(role, "a"))
            assertFailsWith<MederiValidationException> { manager.set(role, SubagentModelConfig(), "a") }
            assertFailsWith<MederiValidationException> { manager.clear(role, "a") }
        }
        assertEquals(setOf(SubagentRole.EXECUTOR, SubagentRole.RESEARCHER), manager.list("a").keys)
        assertTrue(sessions.get("a")!!.subagentConfigs.isEmpty())
    }

    @Test
    fun `sqlite persists role overrides and concurrency independently of compose state`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            MederiDataDatabase.Schema.create(driver)
            Mederi.migrateDataDb(driver)
            val db = xyz.mederi.store.db.DatabaseManager(driver)
            val store = SqliteSessionStore(db)
            store.insert(session("persist"))
            val settings = InMemorySettingsStore()
            val providers = FakeProviderManager(emptyMap())
            val manager = SubagentConfigManager(settings, providers, store)
            val expected = SubagentModelConfig("local", ReasoningLevel.MAX, API_KEY_ROTATION_ID)
            manager.set(SubagentRole.RESEARCHER, expected, "persist")
            manager.set(SubagentRole.EXECUTOR, SubagentModelConfig(), "persist")
            manager.setMaxConcurrentSubagents(7, "persist")
            store.updateComposeState("persist", AgentMode.AUTONOMOUS, null, null, "main-key", "new draft")

            val reopened = SqliteSessionStore(db)
            val rereadManager = SubagentConfigManager(settings, providers, reopened)
            assertEquals(expected, rereadManager.get(SubagentRole.RESEARCHER, "persist"))
            assertFalse(rereadManager.usesDefault(SubagentRole.EXECUTOR, "persist"))
            assertEquals(7, rereadManager.getMaxConcurrentSubagents("persist"))
            assertEquals(expected, reopened.list().single().subagentConfigs[SubagentRole.RESEARCHER])
            assertEquals("new draft", reopened.get("persist")!!.draftText)
            reopened.insert(reopened.get("persist")!!.copy(id = "copy"))
            assertEquals(expected, reopened.get("copy")!!.subagentConfigs[SubagentRole.RESEARCHER])
            assertEquals(7, reopened.get("copy")!!.subagentMaxConcurrent)
            rereadManager.clear(SubagentRole.RESEARCHER, "persist")
            rereadManager.clearMaxConcurrentSubagents("persist")
            assertNull(reopened.get("persist")!!.subagentMaxConcurrent)
            assertFalse(reopened.get("persist")!!.subagentConfigs.containsKey(SubagentRole.RESEARCHER))
        } finally { driver.close() }
    }

    @Test
    fun `v4 database migrates without losing sessions and accepts persistent overrides`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, """
                CREATE TABLE sessions (
                    id TEXT NOT NULL PRIMARY KEY, project_id TEXT NOT NULL,
                    title TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'IDLE',
                    agent_mode TEXT NOT NULL DEFAULT 'AUTONOMOUS', ai_model TEXT, reasoning_level TEXT,
                    api_key_id TEXT, env TEXT NOT NULL DEFAULT '{}', todos TEXT NOT NULL DEFAULT '[]',
                    active_skills TEXT NOT NULL DEFAULT '[]', draft_text TEXT, created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL, workspace_id TEXT, associated_worktree_id TEXT,
                    archived_at TEXT, pinned INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent(), 0)
            driver.execute(null, """
                INSERT INTO sessions(id, project_id, title, draft_text, created_at, updated_at, pinned)
                VALUES ('old-chat', 'project', 'Existing chat', 'unsent text',
                        '2026-11-01T00:00:00Z', '2026-11-01T00:00:00Z', 1)
            """.trimIndent(), 0)
            driver.execute(null, "PRAGMA user_version = 4", 0)
            Mederi.migrateDataDb(driver)
            val db = xyz.mederi.store.db.DatabaseManager(driver)
            val store = SqliteSessionStore(db)
            val existing = assertNotNull(store.get("old-chat"))
            assertEquals("Existing chat", existing.title)
            assertEquals("unsent text", existing.draftText)
            assertTrue(existing.pinned)
            assertTrue(existing.subagentConfigs.isEmpty())
            assertNull(existing.subagentMaxConcurrent)
            val manager = SubagentConfigManager(InMemorySettingsStore(), FakeProviderManager(emptyMap()), store)
            val config = SubagentModelConfig(apiKeyId = "new-key")
            manager.set(SubagentRole.EXECUTOR, config, "old-chat")
            manager.setMaxConcurrentSubagents(4, "old-chat")
            val reread = assertNotNull(SqliteSessionStore(db).get("old-chat"))
            assertEquals(config, reread.subagentConfigs[SubagentRole.EXECUTOR])
            assertEquals(4, reread.subagentMaxConcurrent)
            assertEquals("unsent text", reread.draftText)
        } finally { driver.close() }
    }

    @Test
    fun `parallel role updates retain each role and missing sessions fail`() = runBlocking {
        val sessions = InMemorySessionStore().also { it.insert(session("a")) }
        val manager = SubagentConfigManager(InMemorySettingsStore(), FakeProviderManager(emptyMap()), sessions)
        listOf(SubagentRole.EXECUTOR, SubagentRole.RESEARCHER).map { role -> async(Dispatchers.Default) { manager.set(role, SubagentModelConfig(role.name), "a") } }.awaitAll()
        assertEquals(2, sessions.get("a")!!.subagentConfigs.size)
        assertFailsWith<MederiNotFoundException> { manager.get(SubagentRole.EXECUTOR, "missing") }
        assertFailsWith<MederiNotFoundException> { manager.set(SubagentRole.EXECUTOR, SubagentModelConfig(), "missing") }
        assertFailsWith<MederiNotFoundException> { manager.setMaxConcurrentSubagents(1, "missing") }
        assertFailsWith<MederiNotFoundException> { manager.clearMaxConcurrentSubagents("missing") }
        Unit
    }
}
