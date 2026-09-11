package xyz.mederi.plan

import xyz.mederi.domain.model.TodoItem
import xyz.mederi.domain.model.TodoStatus

/**
 * Plan 子任务 → Todo 投影：UI todo 面板与 PLAN_PROGRESS 事件 payload 共用的唯一映射。
 *
 * 真理源是 PlanStore（plans 目录 JSON），本函数只读不写。
 * 契约层 TodoItem.id 由投影边界（reducer / MederiModelMapper）合成，不在此产生。
 */
fun Plan.toTodoProjection(): List<TodoItem> = subtasks.map { st ->
    TodoItem(
        content = "Subtask ${st.index + 1}: ${st.name}",
        status = when (st.status) {
            SubtaskStatus.PENDING -> TodoStatus.PENDING
            SubtaskStatus.IN_PROGRESS -> TodoStatus.IN_PROGRESS
            SubtaskStatus.COMPLETED -> TodoStatus.COMPLETED
            SubtaskStatus.FAILED -> TodoStatus.FAILED
        }
    )
}
