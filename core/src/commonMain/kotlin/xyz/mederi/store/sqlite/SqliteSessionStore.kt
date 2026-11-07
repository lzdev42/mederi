package xyz.mederi.store.sqlite

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.SessionStatus
import xyz.mederi.domain.model.TodoItem
import xyz.mederi.domain.model.decodeTodos
import xyz.mederi.domain.model.encodeTodos
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.store.SessionStore
import java.time.Instant
import java.util.Properties

/**
 * 基于 SQLDelight + SQLite 的 SessionStore 实现（data.db）。
 *
 * @param driver 数据库 driver（由 Mederi 装配层创建并共享）。
 */
class SqliteSessionStore(driver: app.cash.sqldelight.db.SqlDriver) : SessionStore {

    private val queries = MederiDataDatabase(driver).dataDatabaseQueries

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val envSerializer = MapSerializer(String.serializer(), String.serializer())

    override suspend fun list(): List<Session> = withContext(Dispatchers.IO) {
        queries.listSessions().executeAsList().map { row ->
            Session(
                id = row.id,
                projectId = row.project_id,
                title = row.title,
                status = SessionStatus.valueOf(row.status),
                agentMode = AgentMode.valueOf(row.agent_mode),
                aiModel = row.ai_model?.let { parseAIModel(it) },
                reasoningLevel = row.reasoning_level?.let { ReasoningLevel.valueOf(it) },
                apiKeyId = row.api_key_id,
                env = parseEnv(row.env),
                todos = parseTodos(row.todos),
                activeSkills = parseActiveSkills(row.active_skills),
                draftText = row.draft_text,
                createdAt = row.created_at,
                updatedAt = row.updated_at,
                workspaceId = row.workspace_id,
                associatedWorktreeId = row.associated_worktree_id,
                archivedAt = row.archived_at,
                pinned = row.pinned != 0L,
                subagentConfigs = parseSubagentConfigs(row.subagent_configs),
                subagentMaxConcurrent = row.subagent_max_concurrent?.toInt()
            )
        }
    }

    override suspend fun get(id: String): Session? = withContext(Dispatchers.IO) {
        queries.getSession(id).executeAsOneOrNull()?.let { row ->
            Session(
                id = row.id,
                projectId = row.project_id,
                title = row.title,
                status = SessionStatus.valueOf(row.status),
                agentMode = AgentMode.valueOf(row.agent_mode),
                aiModel = row.ai_model?.let { parseAIModel(it) },
                reasoningLevel = row.reasoning_level?.let { ReasoningLevel.valueOf(it) },
                apiKeyId = row.api_key_id,
                env = parseEnv(row.env),
                todos = parseTodos(row.todos),
                activeSkills = parseActiveSkills(row.active_skills),
                draftText = row.draft_text,
                createdAt = row.created_at,
                updatedAt = row.updated_at,
                workspaceId = row.workspace_id,
                associatedWorktreeId = row.associated_worktree_id,
                archivedAt = row.archived_at,
                pinned = row.pinned != 0L,
                subagentConfigs = parseSubagentConfigs(row.subagent_configs),
                subagentMaxConcurrent = row.subagent_max_concurrent?.toInt()
            )
        }
    }

    override suspend fun insert(session: Session): Unit = withContext(Dispatchers.IO) {
        queries.insertSession(
            id = session.id,
            project_id = session.projectId,
            title = session.title,
            status = session.status.name,
            agent_mode = session.agentMode.name,
            ai_model = session.aiModel?.let { serializeAIModel(it) },
            reasoning_level = session.reasoningLevel?.name,
            api_key_id = session.apiKeyId,
            env = serializeEnv(session.env),
            todos = session.todos.encodeTodos(),
            active_skills = session.activeSkills.encodeActiveSkills(),
            draft_text = session.draftText,
            created_at = session.createdAt,
            updated_at = session.updatedAt,
            workspace_id = session.workspaceId,
            associated_worktree_id = session.associatedWorktreeId,
            archived_at = session.archivedAt,
            pinned = if (session.pinned) 1L else 0L,
            subagent_configs = json.encodeToString(session.subagentConfigs),
            subagent_max_concurrent = session.subagentMaxConcurrent?.toLong()
        )
    }

    override suspend fun update(id: String, status: SessionStatus?, title: String?): Unit = withContext(Dispatchers.IO) {
        val now = Instant.now().toString()
        when {
            status != null && title != null -> {
                queries.updateSessionStatus(status.name, now, id)
                queries.updateSessionTitle(title, id)
            }
            status != null -> queries.updateSessionStatus(status.name, now, id)
            title != null -> queries.updateSessionTitle(title, id)
        }
    }

    override suspend fun updateAgentConfig(
        id: String,
        agentMode: AgentMode?,
        aiModel: AIModel?,
        reasoningLevel: ReasoningLevel?,
        apiKeyId: String?
    ): Unit = withContext(Dispatchers.IO) {
        queries.updateSessionAgentConfig(
            agentMode?.name,
            aiModel?.let { serializeAIModel(it) },
            reasoningLevel?.name,
            apiKeyId,
            Instant.now().toString(),
            id
        )
    }

    override suspend fun updateComposeState(
        id: String,
        agentMode: AgentMode,
        aiModel: AIModel?,
        reasoningLevel: ReasoningLevel?,
        apiKeyId: String?,
        draftText: String
    ): Unit = withContext(Dispatchers.IO) {
        queries.updateSessionComposeState(
            agentMode.name,
            aiModel?.let { serializeAIModel(it) },
            reasoningLevel?.name,
            apiKeyId,
            draftText,
            id
        )
    }

    override suspend fun updateTodos(id: String, todos: List<TodoItem>): Unit = withContext(Dispatchers.IO) {
        queries.updateSessionTodos(todos.encodeTodos(), id)
    }

    override suspend fun updateActiveSkills(id: String, activeSkills: List<String>): Unit = withContext(Dispatchers.IO) {
        queries.updateSessionActiveSkills(activeSkills.encodeActiveSkills(), id)
    }

    override suspend fun updateWorkspaceBinding(id: String, workspaceId: String?, associatedWorktreeId: String?): Unit = withContext(Dispatchers.IO) {
        queries.updateSessionWorkspaceBinding(workspaceId, associatedWorktreeId, Instant.now().toString(), id)
    }

    override suspend fun setArchived(id: String, archivedAt: String?): Unit = withContext(Dispatchers.IO) {
        queries.updateSessionArchivedAt(archivedAt, Instant.now().toString(), id)
    }

    override suspend fun setPinned(id: String, pinned: Boolean): Unit = withContext(Dispatchers.IO) {
        queries.updateSessionPinned(if (pinned) 1L else 0L, Instant.now().toString(), id)
    }

    override suspend fun updateSubagentConfigs(id: String, configs: Map<xyz.mederi.domain.model.SubagentRole, xyz.mederi.domain.model.SubagentModelConfig>): Unit = withContext(Dispatchers.IO) {
        queries.updateSessionSubagentConfigs(json.encodeToString(configs), id)
    }

    override suspend fun updateSubagentMaxConcurrent(id: String, count: Int?): Unit = withContext(Dispatchers.IO) {
        queries.updateSessionSubagentMaxConcurrent(count?.toLong(), id)
    }

    private fun parseSubagentConfigs(raw: String): Map<xyz.mederi.domain.model.SubagentRole, xyz.mederi.domain.model.SubagentModelConfig> =
        runCatching { json.decodeFromString<Map<xyz.mederi.domain.model.SubagentRole, xyz.mederi.domain.model.SubagentModelConfig>>(raw) }.getOrDefault(emptyMap())

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteSession(id)
    }

    private fun serializeEnv(env: Map<String, String>): String =
        json.encodeToString(envSerializer, env)

    private fun parseEnv(jsonStr: String): Map<String, String> {
        if (jsonStr.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString(envSerializer, jsonStr) }.getOrDefault(emptyMap())
    }

    private fun parseTodos(jsonStr: String): List<TodoItem> =
        decodeTodos(jsonStr).orEmpty()

    private fun parseActiveSkills(jsonStr: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(jsonStr) }.getOrDefault(emptyList())

    private fun List<String>.encodeActiveSkills(): String =
        json.encodeToString(this)

    private fun serializeAIModel(aiModel: AIModel): String =
        json.encodeToString(AIModel.serializer(), aiModel)

    private fun parseAIModel(jsonStr: String): AIModel =
        runCatching { json.decodeFromString(AIModel.serializer(), jsonStr) }.getOrThrow()
}
