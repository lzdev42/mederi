package xyz.mederi.domain.model

import kotlinx.serialization.Serializable
import xyz.mederi.provider.domain.model.ReasoningLevel

/**
 * 会话状态。
 */
enum class SessionStatus {
    IDLE, RUNNING, ERROR
}

/**
 * 持久会话容器。
 *
 * Session 是对话历史的容器，记录用户最后一次选择的配置。
 * 系统提示词由 agentMode 每轮现算，不在 Session 中冗余存储。
 *
 * Session 必须属于一个 Project。
 *
 * @param id Session ID（对应 Koog 的 sessionId）。
 * @param projectId 所属项目 ID。
 * @param title 标题。
 * @param status 当前状态。
 * @param agentMode 执行策略（APPROVAL / AUTONOMOUS）。
 * @param aiModel 用户最后一次选择的模型，可为 null（首次未选择时）。
 * @param reasoningLevel 用户最后一次选择的推理等级，可为 null。
 * @param apiKeyId 用户最后一次为该会话选定的 API Key ID，可为 null（null = 用该供应商默认 key）。
 * @param env 会话环境变量。
 * @param todos 无 Plan 任务的轻量 todo 列表（update_todo 工具维护，sessions.todos 列持久化）。
 * 有活跃 Plan 时此字段被 Plan 子任务投影取代（见 docs/todo-system-plan.md 真理源表）。
 * @param activeSkills 会话级已激活 skill 的 name 列表（load_skill/unload_skill 工具或 SessionApi 维护，
 *   sessions.active_skills 列持久化，唯一真理源）。激活的 skill 全文由 TurnExecutor 每轮注入系统提示词，
 *   不进对话历史。
 * @param draftText 用户已输入但未发送的草稿文本，可为 null（无草稿）。
 * @param createdAt ISO 8601 时间戳。
 * @param updatedAt ISO 8601 时间戳。
 */
@Serializable
data class Session(
    val id: String,
    val projectId: String,
    val title: String,
    val status: SessionStatus,
    val agentMode: AgentMode,
    val aiModel: AIModel?,
    val reasoningLevel: ReasoningLevel?,
    val apiKeyId: String? = null,
    val env: Map<String, String> = emptyMap(),
    val todos: List<TodoItem> = emptyList(),
    val activeSkills: List<String> = emptyList(),
    val draftText: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val workspaceId: String? = null,
    val associatedWorktreeId: String? = null,
    val archivedAt: String? = null,
    val pinned: Boolean = false,
    /** 未覆盖的角色动态使用系统默认；空配置表示显式继承父会话。 */
    val subagentConfigs: Map<SubagentRole, SubagentModelConfig> = emptyMap(),
    val subagentMaxConcurrent: Int? = null
)
