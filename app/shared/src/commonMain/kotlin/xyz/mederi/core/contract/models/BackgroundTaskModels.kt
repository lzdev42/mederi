package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

/**
 * 后台任务信息（BackgroundTaskManager 的 Command / Timer 两种后台任务的 UI 契约 DTO）。
 *
 * 数据源 = BACKGROUND_TASK_* 事件流（STARTED 建对象 → COMPLETED/ERROR/STOPPED 置终态）。
 * 字段语义：
 * - [taskType] = "command" | "timer"
 * - [status] = "running" | "completed" | "error" | "stopped"
 * - [command] 仅 command 型；[description] / [durationSeconds] 仅 timer 型
 * - [pid] / [exitCode] 仅 command 型（exitCode 在终态时有效）
 * - [startedAt] / [completedAt] 均为 ISO 8601 时间戳
 */
@Serializable
data class BackgroundTaskInfo(
    val taskId: String,
    val taskType: String,    // "command" or "timer"
    val status: String,      // "running", "completed", "error", "stopped"
    val command: String? = null,      // for command type
    val description: String? = null, // for timer type
    val pid: Long? = null,           // for command type
    val exitCode: Int? = null,       // for command type, when completed
    val startedAt: String,           // ISO 8601
    val completedAt: String? = null, // ISO 8601, when finished
    val durationSeconds: Int? = null // for timer type
)
