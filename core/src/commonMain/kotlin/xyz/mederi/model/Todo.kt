package xyz.mederi.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Todo 结构化详情（工作记忆，跨回合给 AI 参考，UI 不展示）。七字段全可选。
 */
@Serializable
data class TodoDetail(
    /** 背景上下文 */
    val background: String? = null,
    /** 现象或需求描述 */
    val requirement: String? = null,
    /** 思路、为什么这么实现、核心逻辑 */
    val approach: String? = null,
    /** 作用范围（文件列表、模块、URL等） */
    val scope: String? = null,
    /** 完成判断标准（如何确认做完了） */
    val done: String? = null,
    /** 用户强调的注意事项与硬性约束 */
    val notes: String? = null,
    /** 已试过但失败的路径（防止反复走弯路） */
    val attempts: String? = null,
)

/**
 * Todo 条目状态。
 *
 * update_todo 与 Plan 子任务投影共用此状态。允许模型标记 [FAILED]（纠错 todo 模式：
 * 当某项任务无法按预期完成时标记 FAILED，随后新建纠错 todo 继续推进）。
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
 * [content] 充当标题展示，[detail] 是给 AI 的工作记忆（UI 不渲染）。
 */
@Serializable
data class TodoItem(
    val content: String,
    val status: TodoStatus,
    val detail: TodoDetail? = null,
)

/**
 * Todo 列表编解码（唯一 Json 配置）：sessions.todos 列与事件 payload 共用，
 * encode/decode 必须对称。禁止在任何其他地方另行定义 Todo 的 Json 序列化。
 */
private val todoListSerializer = ListSerializer(TodoItem.serializer())
private val todoJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun List<TodoItem>.encodeTodos(): String = todoJson.encodeToString(todoListSerializer, this)

/** 解析失败返回 null（调用方安全降级），空列表是合法值（= 清空）。 */
fun decodeTodos(raw: String): List<TodoItem>? =
    runCatching { todoJson.decodeFromString(todoListSerializer, raw) }.getOrNull()
