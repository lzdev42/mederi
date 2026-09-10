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
import xyz.mederi.domain.model.WorkType
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
                workType = row.work_type?.let { WorkType.valueOf(it) } ?: WorkType.CODE,
                aiModel = row.ai_model?.let { parseAIModel(it) },
                reasoningLevel = row.reasoning_level?.let { ReasoningLevel.valueOf(it) },
                env = parseEnv(row.env),
                createdAt = row.created_at,
                updatedAt = row.updated_at
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
                workType = row.work_type?.let { WorkType.valueOf(it) } ?: WorkType.CODE,
                aiModel = row.ai_model?.let { parseAIModel(it) },
                reasoningLevel = row.reasoning_level?.let { ReasoningLevel.valueOf(it) },
                env = parseEnv(row.env),
                createdAt = row.created_at,
                updatedAt = row.updated_at
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
            work_type = session.workType.name,
            ai_model = session.aiModel?.let { serializeAIModel(it) },
            reasoning_level = session.reasoningLevel?.name,
            env = serializeEnv(session.env),
            created_at = session.createdAt,
            updated_at = session.updatedAt
        )
    }

    override suspend fun update(id: String, status: SessionStatus?, title: String?): Unit = withContext(Dispatchers.IO) {
        val now = Instant.now().toString()
        when {
            status != null && title != null -> {
                queries.updateSessionStatus(status.name, now, id)
                queries.updateSessionTitle(title, now, id)
            }
            status != null -> queries.updateSessionStatus(status.name, now, id)
            title != null -> queries.updateSessionTitle(title, now, id)
        }
    }

    override suspend fun updateAgentConfig(
        id: String,
        agentMode: AgentMode?,
        workType: WorkType?,
        aiModel: AIModel?,
        reasoningLevel: ReasoningLevel?
    ): Unit = withContext(Dispatchers.IO) {
        queries.updateSessionAgentConfig(
            agentMode?.name,
            workType?.name,
            aiModel?.let { serializeAIModel(it) },
            reasoningLevel?.name,
            Instant.now().toString(),
            id
        )
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteSession(id)
    }

    private fun serializeEnv(env: Map<String, String>): String =
        json.encodeToString(envSerializer, env)

    private fun parseEnv(jsonStr: String): Map<String, String> {
        if (jsonStr.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString(envSerializer, jsonStr) }.getOrDefault(emptyMap())
    }

    private fun serializeAIModel(aiModel: AIModel): String =
        json.encodeToString(AIModel.serializer(), aiModel)

    private fun parseAIModel(jsonStr: String): AIModel =
        runCatching { json.decodeFromString(AIModel.serializer(), jsonStr) }.getOrThrow()
}
