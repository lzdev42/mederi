package xyz.mederi.core.contract

import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.SubagentState

/**
 * 把 SUBAGENT_* 事件应用到子代理缓存表上的纯逻辑（跨平台，与 [SnapshotReducer] 同风格）。
 *
 * 同一份聚合逻辑服务两端：JVM（desktop/server 进程内）与 wasmJs（遥控端）。
 * 调用方（ViewModel）持状态、按事件流逐个 apply——本 object 无状态。
 *
 * 聚合规则：
 * - SUBAGENT_STARTED → 以 agentId 为键新建 [SubagentState]（全量元数据来自事件 payload）
 * - SUBAGENT_COMPLETED / ERROR / STOPPED → 同键覆盖 status
 * - 其他事件原样返回（不处理）
 */
object SubagentTracker {

    fun apply(states: Map<String, SubagentState>, event: CoreEvent): Map<String, SubagentState> {
        val agentId = event.payload["agentId"] ?: return states
        return when (event.type) {
            CoreEventType.SUBAGENT_STARTED -> {
                states + (agentId to SubagentState(
                    agentId = agentId,
                    parentSessionId = event.sessionId,
                    role = event.payload["role"] ?: "EXECUTOR",
                    modelId = event.payload["modelId"] ?: "",
                    modelName = event.payload["modelName"] ?: "",
                    reasoningLevel = event.payload["reasoningLevel"],
                    task = event.payload["task"] ?: "",
                    briefing = event.payload["briefing"],
                    status = "RUNNING",
                    startedAt = event.timestamp
                ))
            }
            CoreEventType.SUBAGENT_PROGRESS -> {
                val activity = event.payload["activity"]
                val tool = event.payload["tool"]
                val delta = event.payload["delta"].orEmpty()
                val isMessage = event.payload["isMessage"] == "true"
                states.updated(agentId) { current ->
                    val newRecent = if (delta.isNotBlank()) {
                        val combined = (current.recentOutput.orEmpty() + delta)
                        if (combined.length > 1000) combined.takeLast(1000) else combined
                    } else current.recentOutput

                    val newLastMsg = if (isMessage && delta.isNotBlank()) {
                        extractLastSentence(current.lastMessage, delta)
                    } else current.lastMessage

                    current.copy(
                        currentActivity = activity ?: current.currentActivity,
                        // 活动切出工具调用（进入 THINKING/OUTPUT/终态）时清掉上次的工具名，
                        // 避免「正在调用工具: X」徽标在生成阶段残留上一个工具。
                        currentTool = when {
                            activity != null && activity != xyz.mederi.core.contract.models.SubagentActivity.TOOL_CALL -> null
                            tool != null -> tool
                            else -> current.currentTool
                        },
                        recentOutput = newRecent,
                        lastMessage = newLastMsg
                    )
                }
            }
            CoreEventType.SUBAGENT_COMPLETED -> {
                val completedAt = event.payload["completedAt"] ?: event.timestamp
                states.updated(agentId) { it.copy(status = "COMPLETED", currentActivity = null, completedAt = completedAt) }
            }
            CoreEventType.SUBAGENT_ERROR -> {
                val completedAt = event.payload["completedAt"] ?: event.timestamp
                states.updated(agentId) { it.copy(status = "ERROR", currentActivity = null, completedAt = completedAt) }
            }
            CoreEventType.SUBAGENT_STOPPED -> {
                val completedAt = event.payload["completedAt"] ?: event.timestamp
                states.updated(agentId) { it.copy(status = "STOPPED", currentActivity = null, completedAt = completedAt) }
            }
            CoreEventType.SUBAGENT_DISCARDED ->
                states - agentId
            else -> states
        }
    }

    private fun extractLastSentence(prev: String?, delta: String): String {
        val combined = (prev.orEmpty() + delta)
        val lines = combined.lines().filter { it.isNotBlank() }
        val lastLine = lines.lastOrNull().orEmpty().trim()
        return if (lastLine.length > 200) lastLine.takeLast(200) else lastLine
    }

    /** 终态事件先于 STARTED 到达（理论上不该发生）时安全跳过。 */
    private inline fun Map<String, SubagentState>.updated(
        agentId: String,
        transform: (SubagentState) -> SubagentState
    ): Map<String, SubagentState> {
        val current = this[agentId] ?: return this
        return this + (agentId to transform(current))
    }
}
