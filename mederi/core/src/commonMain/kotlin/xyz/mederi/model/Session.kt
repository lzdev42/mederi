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
 * @param env 会话环境变量。
 * @param todos 无 Plan 任务的轻量 todo 列表（update_todo 工具维护，sessions.todos 列持久化）。
 * 有活跃 Plan 时此字段被 Plan 子任务投影取代（见 docs/todo-system-plan.md 真理源表）。
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
    val env: Map<String, String> = emptyMap(),
    val todos: List<TodoItem> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)
