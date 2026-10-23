package xyz.mederi.core.contract

import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.SubagentBlock
import xyz.mederi.core.contract.models.SubagentState
import xyz.mederi.core.contract.models.ToolCallState

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
                    // 结构化块累积：把 SUBAGENT_PROGRESS 里被丢弃的 reasoning/tool/text 结构补回来
                    var newBlocks = current.blocks
                    // 1) THINKING delta 非空 → Reasoning 块
                    if (activity == xyz.mederi.core.contract.models.SubagentActivity.THINKING && delta.isNotBlank()) {
                        val last = newBlocks.lastOrNull()
                        newBlocks = if (last is SubagentBlock.Reasoning)
                            newBlocks.dropLast(1) + last.copy(text = last.text + delta)
                        else newBlocks + SubagentBlock.Reasoning(delta)
                    }
                    // 2) TOOL_CALL 且 tool 非空 → ToolCall 块
                    else if (activity == xyz.mederi.core.contract.models.SubagentActivity.TOOL_CALL && !tool.isNullOrBlank()) {
                        val last = newBlocks.lastOrNull()
                        when {
                            // 上一块是同名工具仍在 Running → 不重复追加（同一工具调用的重复进度）
                            last is SubagentBlock.ToolCall && last.name == tool && last.state is ToolCallState.Running -> {}
                            // 上一块是不同工具仍在 Running → 收敛为 Completed，再追加新 Running
                            last is SubagentBlock.ToolCall && last.state is ToolCallState.Running -> {
                                newBlocks = newBlocks.dropLast(1) +
                                    last.copy(state = ToolCallState.Completed(input = emptyMap(), output = "")) +
                                    SubagentBlock.ToolCall(tool, ToolCallState.Running(input = emptyMap()))
                            }
                            else -> newBlocks = newBlocks + SubagentBlock.ToolCall(tool, ToolCallState.Running(input = emptyMap()))
                        }
                    }
                    // 3) OUTPUT isMessage delta 非空 → Text 块
                    else if (activity == xyz.mederi.core.contract.models.SubagentActivity.OUTPUT && isMessage && delta.isNotBlank()) {
                        val last = newBlocks.lastOrNull()
                        newBlocks = if (last is SubagentBlock.Text)
                            newBlocks.dropLast(1) + last.copy(text = last.text + delta)
                        else newBlocks + SubagentBlock.Text(delta)
                    }

                    val newOutput = if (isMessage && delta.isNotBlank()) {
                        current.outputText.orEmpty() + delta
                    } else current.outputText

                    current.copy(
                        currentActivity = activity ?: current.currentActivity,
                        // 活动切出工具调用（进入 THINKING/OUTPUT/终态）时清掉上次的工具名，
                        // 避免「正在调用工具: X」徽标在生成阶段残留上一个工具。
                        currentTool = when {
                            activity != null && activity != xyz.mederi.core.contract.models.SubagentActivity.TOOL_CALL -> null
                            tool != null -> tool
                            else -> current.currentTool
                        },
                        outputText = newOutput,
                        blocks = newBlocks
                    )
                }
            }
            CoreEventType.SUBAGENT_COMPLETED -> {
                val completedAt = event.payload["completedAt"] ?: event.timestamp
                states.updated(agentId) {
                    it.copy(status = "COMPLETED", currentActivity = null, completedAt = completedAt, blocks = it.blocks.mapLastToolCallCompleted())
                }
            }
            CoreEventType.SUBAGENT_ERROR -> {
                val completedAt = event.payload["completedAt"] ?: event.timestamp
                states.updated(agentId) {
                    it.copy(status = "ERROR", currentActivity = null, completedAt = completedAt, blocks = it.blocks.mapLastToolCallCompleted())
                }
            }
            CoreEventType.SUBAGENT_STOPPED -> {
                val completedAt = event.payload["completedAt"] ?: event.timestamp
                states.updated(agentId) {
                    it.copy(status = "STOPPED", currentActivity = null, completedAt = completedAt, blocks = it.blocks.mapLastToolCallCompleted())
                }
            }
            CoreEventType.SUBAGENT_DISCARDED ->
                states - agentId
            else -> states
        }
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

/** 把最后一个 Running 态 ToolCall 块收敛为 Completed（终态时该工具已结束）。 */
private fun List<SubagentBlock>.mapLastToolCallCompleted(): List<SubagentBlock> {
    val idx = indexOfLast { it is SubagentBlock.ToolCall && it.state is ToolCallState.Running }
    if (idx < 0) return this
    val tc = this[idx] as SubagentBlock.ToolCall
    return subList(0, idx) + tc.copy(state = ToolCallState.Completed(input = emptyMap(), output = "")) + subList(idx + 1, size)
}
