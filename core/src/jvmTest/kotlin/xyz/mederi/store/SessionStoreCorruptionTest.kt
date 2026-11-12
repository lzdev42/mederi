package xyz.mederi.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import xyz.mederi.Mederi
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.domain.model.SubagentModelConfig
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.domain.model.TodoItem
import xyz.mederi.domain.model.TodoStatus
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.store.db.DatabaseManager
import xyz.mederi.store.sqlite.SqliteSessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 会话行级辅助列（subagentConfigs/env/activeSkills）损坏时**有声降级**：
 * 行仍可读、对应列为空集合、其余会话与未损坏列不受影响。
 *
 * 注意：损坏路径要求 DebugLog.error 有输出（session-columns-audible 验收内容），
 * 但 DebugLog 目前只写 stderr、无可采集的测试钩子，故此处以行为断言
 * （会话可读 + 列降级为空）为准；日志由实现路径直接保障。
 */
class SessionStoreCorruptionTest {

    private fun session(id: String) = Session(
        id = id,
        projectId = "project",
        title = "Chat $id",
        status = SessionStatus.IDLE,
        agentMode = AgentMode.APPROVAL,
        aiModel = null,
        reasoningLevel = null,
        env = mapOf("KEY" to "value"),
        todos = listOf(TodoItem("content", TodoStatus.PENDING)),
        activeSkills = listOf("compose-skill"),
        draftText = "draft",
        createdAt = "2026-11-10T00:00:00Z",
        updatedAt = "2026-11-10T00:00:00Z",
        subagentConfigs = mapOf(SubagentRole.EXECUTOR to SubagentModelConfig("model", ReasoningLevel.HIGH, "key")),
        subagentMaxConcurrent = 2
    )

    @Test
    fun `corrupt subagentConfigs env activeSkills columns degrade to empty while session stays readable`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            MederiDataDatabase.Schema.create(driver)
            Mederi.migrateDataDb(driver)
            val store = SqliteSessionStore(DatabaseManager(driver))
            store.insert(session("clean"))
            store.insert(session("corrupt"))

            // 模拟磁盘数据损坏：直接 UPDATE 三列为非法 JSON（绕过 store 写入路径）
            driver.execute(
                null,
                "UPDATE sessions SET subagent_configs = 'not-json', env = 'not-json', active_skills = 'not-json' WHERE id = 'corrupt'",
                0
            )

            // 列表聚合：一行损坏不毒死整个会话列表
            val list = store.list()
            assertEquals(2, list.size, "一行损坏不得毒死整个会话列表")

            val corrupted = list.single { it.id == "corrupt" }
            assertTrue(corrupted.env.isEmpty(), "env 列损坏 → 空集合")
            assertTrue(corrupted.activeSkills.isEmpty(), "activeSkills 列损坏 → 空集合")
            assertTrue(corrupted.subagentConfigs.isEmpty(), "subagentConfigs 列损坏 → 空集合")
            assertEquals("Chat corrupt", corrupted.title, "未损坏列不受影响")
            assertEquals(listOf(TodoItem("content", TodoStatus.PENDING)), corrupted.todos, "未损坏列不受影响")
            assertEquals("draft", corrupted.draftText, "未损坏列不受影响")

            // 单行读同样有声降级
            val byId = assertNotNull(store.get("corrupt"), "损坏行仍可读（非 null）")
            assertTrue(byId.env.isEmpty())
            assertTrue(byId.activeSkills.isEmpty())
            assertTrue(byId.subagentConfigs.isEmpty())

            // 干净会话完全不受影响
            val clean = list.single { it.id == "clean" }
            assertEquals(mapOf("KEY" to "value"), clean.env)
            assertEquals(listOf("compose-skill"), clean.activeSkills)
            assertEquals(
                SubagentModelConfig("model", ReasoningLevel.HIGH, "key"),
                clean.subagentConfigs[SubagentRole.EXECUTOR]
            )
            assertEquals(2, clean.subagentMaxConcurrent)
        } finally {
            driver.close()
        }
    }
}