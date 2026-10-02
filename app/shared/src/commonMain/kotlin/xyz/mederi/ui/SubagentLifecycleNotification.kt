package xyz.mederi.ui

import xyz.mederi.core.contract.models.SubagentState

/**
 * 子智能体生命周期浮动通知状态模型 (SubagentNotificationState)
 *
 * 挂载在工作区输入框顶部顶层浮层 (WorkspaceFloatingOverlay)，用于在长对话或多轮交互中，
 * 实时提示用户子智能体的状态变化（派发中、运行中、已完成、失败、已取消）。
 *
 * 设计约定：
 * 1. 纯状态变化通知，不堆叠长文本（详细简报与汇报在右侧概览查看）；
 * 2. 状态变化产生新 notificationKey，用户不点 X 则常驻保持；
 * 3. 当状态发生变化（如 RUNNING -> COMPLETED），即使此前已点 X 关闭 RUNNING 态，
 *    也会因为 key 变化而重新唤起 COMPLETED 完成态通知，防止漏掉任务完成结果。
 */
data class SubagentNotificationState(
    val notificationKey: String,
    val agentId: String,
    val role: String,
    val status: String,
    val task: String = "",
    val startedAt: String = "",
) {
    val isRunning: Boolean get() = status.equals("RUNNING", ignoreCase = true)
    val isCompleted: Boolean get() = status.equals("COMPLETED", ignoreCase = true)
    val isFailed: Boolean get() = status.equals("ERROR", ignoreCase = true)
    val isStopped: Boolean get() = status.equals("STOPPED", ignoreCase = true)
}

/**
 * 计算当前会话应当展示的子智能体生命周期通知列表（积木式堆叠）。
 *
 * 当多个子智能体同时创建或运行任务时，每个活跃未关闭的任务都作为一块积木展示，
 * 避免单个 latest 将其他并行任务遮蔽覆盖。
 *
 * @param subagents 当前会话的子代理列表（按 startedAt 升序）
 * @param dismissedKeys 用户手动点击 X 关闭的通知 key 集合
 * @param maxCount 最多展示的通知积木条数（默认 4 条，避免占据过多垂直空间）
 * @return 应当展示的生命周期通知积木列表
 */
fun deriveActiveSubagentNotifications(
    subagents: List<SubagentState>,
    dismissedKeys: Set<String>,
    maxCount: Int = 4,
): List<SubagentNotificationState> {
    if (subagents.isEmpty()) return emptyList()

    return subagents
        .mapNotNull { subagent ->
            val key = "${subagent.agentId}_${subagent.status.uppercase()}"
            if (dismissedKeys.contains(key)) null
            else SubagentNotificationState(
                notificationKey = key,
                agentId = subagent.agentId,
                role = subagent.role,
                status = subagent.status,
                task = subagent.task,
                startedAt = subagent.startedAt
            )
        }
        .takeLast(maxCount)
}

/**
 * 判断当前会话是否有子智能体正在工作。
 */
fun hasRunningSubagents(subagents: List<SubagentState>): Boolean {
    return subagents.any { it.status.equals("RUNNING", ignoreCase = true) }
}

/**
 * 判断是否应当展示“当前有子智能体在工作”的单一浮动通知条。
 * 只有在当前有子智能体处于运行态、且用户未手动点击关闭时才展示。
 */
fun shouldShowSubagentRunningBanner(
    subagents: List<SubagentState>,
    isDismissed: Boolean,
): Boolean {
    return hasRunningSubagents(subagents) && !isDismissed
}

/**
 * 兼容单体获取形式：返回最新一条活跃通知，若无则返回 null。
 */
fun deriveActiveSubagentNotification(
    subagents: List<SubagentState>,
    dismissedKeys: Set<String>,
): SubagentNotificationState? = deriveActiveSubagentNotifications(subagents, dismissedKeys).lastOrNull()


