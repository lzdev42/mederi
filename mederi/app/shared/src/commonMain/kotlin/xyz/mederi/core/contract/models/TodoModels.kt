package xyz.mederi.core.contract.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Todo 条目状态。@SerialName 对齐 core wire 格式（小写），
 * reducer 解码事件 payload 时由 kotlinx 直接映射，零手写转换。
 */
enum class TodoStatus {
    @SerialName("pending") Pending,
    @SerialName("in_progress") InProgress,
    @SerialName("completed") Completed,
    @SerialName("cancelled") Cancelled,

    /** 仅 Plan 子任务投影产生（verify_subtask 判 FAIL）；模型 update_todo 不产生。 */
    @SerialName("failed") Failed
}

@Serializable
data class TodoItem(
    val id: String,
    val content: String,
    val status: TodoStatus,
    val priority: String? = null,
)

/**
 * 事件 payload wire DTO：与 core 侧唯一编码点（encodeTodos）的 JSON 形状严格对齐
 * ——core TodoItem 只有 content+status 两字段。id/priority 由投影边界合成，不上 wire。
 */
@Serializable
internal data class TodoWireItem(
    val content: String,
    val status: TodoStatus = TodoStatus.Pending
)
