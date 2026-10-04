package xyz.mederi.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Todo 条目状态。
 *
 * [FAILED] 预留给 Plan 验证结果语义（verify_subtask 判 FAIL）；update_todo 工具参数不接受它——
 * 模型 todo 没有"失败"语义。
 */
@Serializable
enum class TodoStatus {
    @SerialName("pending") PENDING,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("failed") FAILED
}

/**
 * 轻量 todo 条目（无 Plan 任务的工作进度跟踪）。
 *
 * 唯一序列化形状：sessions.todos 列落库与 TODO_UPDATED 事件 payload 共用本类型，
 * payload key 统一为 "todos"。禁止任何其他 JSON 形状（防真理源碎片）。
 */
@Serializable
data class TodoItem(
    val content: String,
    val status: TodoStatus
)

/**
 * Todo 列表编解码（唯一 Json 配置）：sessions.todos 列与事件 payload 共用，
 * encode/decode 必须对称。禁止在任何其他地方另行定义 Todo 的 Json 序列化。
 */
private val todoListSerializer = ListSerializer(TodoItem.serializer())
private val todoJson = Json { encodeDefaults = true }

fun List<TodoItem>.encodeTodos(): String = todoJson.encodeToString(todoListSerializer, this)

/** 解析失败返回 null（调用方安全降级），空列表是合法值（= 清空）。 */
fun decodeTodos(raw: String): List<TodoItem>? =
    runCatching { todoJson.decodeFromString(todoListSerializer, raw) }.getOrNull()
