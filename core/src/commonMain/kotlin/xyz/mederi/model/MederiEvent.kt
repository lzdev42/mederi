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
     * 会话 todo 列表更新（update_todo 工具 / Plan 子任务投影共用）。
     * payload: todos = List<TodoItem> 的 JSON（key 统一为 "todos"），可选 explanation。
     * 真理源在持久化层（sessions.todos 列 / PlanStore），本事件只是投影通知。
     */
    TODO_UPDATED,

    /**
     * 环境态状态事件（不改变 session 状态机、不落库）：限流重试、供应商抖动等
     * "正在发生但不是业务结果"的事实。payload 约定：
     * scope=provider, code=RETRYING, message, attempt, maxAttempts, delayMs（重试延迟毫秒，可选）。
     * UI 据此显示"重试中"；session status 不受影响（仍 RUNNING）。
     */
    STATUS,

    /**
     * 浏览器任务生命周期事件（异步，主代理通过 run_browser_task 工具派发）。
     * payload 约定（key 统一）：taskId, status(STARTED/RUNNING/COMPLETED/ERROR/STOPPED),
     * step?, thought?, results?, message?。UI 浏览器任务面板消费；主代理只经工具查 status。
     */
    BROWSER_TASK_STARTED,
    BROWSER_TASK_STEP,
    BROWSER_TASK_COMPLETED,
    BROWSER_TASK_ERROR,
    BROWSER_TASK_STOPPED,

    /**
     * 子代理生命周期事件（异步，spawn_agent / spawn_researcher 派发）。
     * 与 BROWSER_TASK_* 同模式：UI 子代理面板消费，父代理只经 agent_status 查状态。
     * sessionId = 父会话 ID；payload 约定（key 统一 camelCase）：
     * - STARTED: agentId, role(EXECUTOR/RESEARCHER), modelId, modelName, reasoningLevel,
     *   task(主代理派发的命令), briefing?(可选)
     * - PROGRESS（子代理流式动态）: agentId, role, activity(THINKING/TOOL_CALL/OUTPUT，取值见
     *   SubagentActivity 词表，契约层有镜像 + 契约测试锁死), tool?(工具名，工具活动时),
     *   delta?(文本增量，流式视窗用), isMessage?(是否正文帧)
     * - COMPLETED / ERROR / STOPPED: agentId, role, status, reportPath?, result?, planId?, subtaskIndex?, completedAt?
     */
    SUBAGENT_STARTED,
    SUBAGENT_PROGRESS,
    SUBAGENT_COMPLETED,
    SUBAGENT_ERROR,
    SUBAGENT_STOPPED,

    /**
     * 子代理无感丢弃事件（对话回退时，目标消息之后创立的子代理被关闭且无感丢弃）。
     * payload: agentId。UI 从缓存列表中移除，主会话不转化 event_message、不通知 AI。
     */
    SUBAGENT_DISCARDED
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
