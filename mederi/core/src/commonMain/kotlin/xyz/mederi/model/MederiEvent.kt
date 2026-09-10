package xyz.mederi.domain.model

/**
 * 事件类型。
 */
enum class EventType {
    SESSION_CREATED,
    SESSION_UPDATED,
    MESSAGE_DELTA,
    MESSAGE_COMPLETED,
    MESSAGE_ERROR,
    TOOL_CALLED,
    TOOL_RESULT,
    QUESTION_REQUESTED,
    QUESTION_RESOLVED,
    PLAN_APPROVAL_REQUESTED,
    PLAN_APPROVAL_RESOLVED,
    PLAN_PROGRESS,

    /**
     * 环境态状态事件（不改变 session 状态机、不落库）：限流重试、供应商抖动等
     * "正在发生但不是业务结果"的事实。payload 约定：
     * scope=provider, code=RETRYING, message, attempt, maxAttempts。
     * UI 据此显示"重试中"；session status 不受影响（仍 RUNNING）。
     */
    STATUS
}

/**
 * Mederi 事件。
 *
 * 先最小化，只包含必要字段。
 *
 * @param type 事件类型。
 * @param sessionId Session ID。
 * @param messageId 消息 ID（可选）。
 * @param payload 附加数据。
 * @param timestamp ISO 8601 时间戳。
 */
data class MederiEvent(
    val type: EventType,
    val sessionId: String,
    val messageId: String? = null,
    val payload: Map<String, String> = emptyMap(),
    val timestamp: String
)
