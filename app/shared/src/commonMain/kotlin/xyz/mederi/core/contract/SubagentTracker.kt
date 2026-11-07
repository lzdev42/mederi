package xyz.mederi.core.contract

import xyz.mederi.core.contract.models.CoreEvent
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.SubagentBlock
import xyz.mederi.core.contract.models.SubagentState
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.TokenUsage
import xyz.mederi.core.contract.models.CostSummary
import xyz.mederi.core.contract.dto.ConversationState
import kotlinx.serialization.json.Json
import kotlin.time.Instant

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
                val existing = states[agentId]
                states + (agentId to SubagentState(
                    agentId = agentId,
                    parentSessionId = event.sessionId,
                    role = event.payload["role"] ?: "EXECUTOR",
                    modelId = event.payload["modelId"] ?: "",
                    modelName = event.payload["modelName"] ?: "",
                    reasoningLevel = event.payload["reasoningLevel"],
                    task = event.payload["task"] ?: "",
                    briefing = event.payload["briefing"],
                    status = existing?.status ?: "RUNNING",
                    startedAt = existing?.startedAt ?: event.payload["startedAt"] ?: event.timestamp,
                    currentActivity = existing?.currentActivity,
                    currentTool = existing?.currentTool,
                    outputText = existing?.outputText,
                    completedAt = existing?.completedAt,
                    blocks = existing?.blocks ?: emptyList(),
                    messages = existing?.messages ?: emptyList(),
                    progressSequence = existing?.progressSequence ?: 0L,
                    errorMessage = existing?.errorMessage, errorId = existing?.errorId,
                    errorDiagnostic = existing?.errorDiagnostic, statusHint = existing?.statusHint,
                ))
            }
            CoreEventType.SUBAGENT_PROGRESS -> {
                val childEventJson = event.payload["eventJson"]
                if (childEventJson != null) {
                    val childEvent = runCatching { wireJson.decodeFromString<CoreEvent>(childEventJson) }.getOrNull()
                        ?: return states // malformed structured frame cannot be reinterpreted as prose
                    val sequence = event.payload["sequence"]?.toLongOrNull()
                    return states.updated(agentId) { current ->
                        if (sequence != null && sequence <= current.progressSequence) current
                        else applyChildEvent(current, childEvent).copy(progressSequence = sequence ?: current.progressSequence)
                    }
                }
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
                    it.copy(status = "COMPLETED", currentActivity = null, statusHint = null, completedAt = completedAt, blocks = it.blocks.mapLastToolCallCompleted(), messages = it.messages.map { message ->
                        message.copy(isStreaming = false, workCompletedAt = message.workCompletedAt ?: parseEpoch(completedAt))
                    })
                }
            }
            CoreEventType.SUBAGENT_ERROR -> {
                val completedAt = event.payload["completedAt"] ?: event.timestamp
                states.updated(agentId) {
                    it.copy(status = "ERROR", currentActivity = null, completedAt = completedAt,
                        errorMessage = event.payload["error"] ?: it.errorMessage ?: event.payload["result"],
                        errorId = event.payload["errorId"] ?: it.errorId,
                        errorDiagnostic = event.payload["fullDiagnostic"] ?: it.errorDiagnostic,
                        statusHint = null, blocks = it.blocks.mapLastToolCallCompleted(), messages = it.messages.map { message ->
                        message.copy(isStreaming = false, workCompletedAt = message.workCompletedAt ?: parseEpoch(completedAt))
                    })
                }
            }
            CoreEventType.SUBAGENT_STOPPED -> {
                val completedAt = event.payload["completedAt"] ?: event.timestamp
                states.updated(agentId) {
                    it.copy(status = "STOPPED", currentActivity = null, statusHint = null, completedAt = completedAt, blocks = it.blocks.mapLastToolCallCompleted(), messages = it.messages.map { message ->
                        message.copy(isStreaming = false, workCompletedAt = message.workCompletedAt ?: parseEpoch(completedAt))
                    })
                }
            }
            CoreEventType.SUBAGENT_DISCARDED ->
                states - agentId
            else -> states
        }
    }

    private fun parseEpoch(value: String): Long? = runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()

    private val wireJson = Json { ignoreUnknownKeys = true }

    /** 初次水合/重连与实时事件使用同一个投影，不能把已收的消息降级成扁平正文。 */
    fun replay(subagent: SubagentState, events: List<CoreEvent>): SubagentState =
        settleTerminal(events.fold(subagent.copy(messages = emptyList())) { current, event -> applyChildEvent(current, event) }
            .copy(progressSequence = events.size.toLong()))

    /**
     * snapshot是权威成员表（空列表也有效，不能复活已DISCARDED成员）。同ID的内容序列与
     * 生命周期分别单调合并：旧水合不能覆盖新流式，旧RUNNING不能覆盖已知终态。
     */
    fun reconcileSnapshots(snapshot: List<SubagentState>, previous: List<SubagentState>): List<SubagentState> {
        val previousById = previous.associateBy { it.agentId }
        return snapshot.map { incoming ->
            val old = previousById[incoming.agentId] ?: return@map settleTerminal(incoming)
            val content = when {
                old.progressSequence > incoming.progressSequence -> old
                old.progressSequence == incoming.progressSequence && incoming.messages.isEmpty() && old.messages.isNotEmpty() -> old
                else -> incoming
            }
            val lifecycle = when {
                isTerminal(old.status) && !isTerminal(incoming.status) -> old
                isTerminal(incoming.status) && !isTerminal(old.status) -> incoming
                isTerminal(old.status) && isTerminal(incoming.status) -> {
                    val oldEnd = old.completedAt?.let(::parseEpoch)
                    val newEnd = incoming.completedAt?.let(::parseEpoch)
                    if (oldEnd != null && (newEnd == null || oldEnd > newEnd)) old else incoming
                }
                else -> incoming
            }
            settleTerminal(incoming.copy(
                messages = content.messages,
                blocks = content.blocks,
                progressSequence = content.progressSequence,
                currentActivity = content.currentActivity,
                currentTool = content.currentTool,
                outputText = content.outputText,
                errorMessage = content.errorMessage ?: old.errorMessage,
                errorId = content.errorId ?: old.errorId,
                errorDiagnostic = content.errorDiagnostic ?: old.errorDiagnostic,
                statusHint = content.statusHint,
                status = lifecycle.status,
                completedAt = lifecycle.completedAt,
            ))
        }
    }

    private fun isTerminal(status: String): Boolean = status in setOf("COMPLETED", "ERROR", "STOPPED")

    private fun settleTerminal(state: SubagentState): SubagentState {
        if (!isTerminal(state.status)) return state
        val endedAt = state.completedAt?.let(::parseEpoch)
        return state.copy(currentActivity = null, currentTool = null, statusHint = null, messages = state.messages.map { message ->
            message.copy(isStreaming = false, workCompletedAt = message.workCompletedAt ?: endedAt)
        })
    }

    private fun applyChildEvent(current: SubagentState, event: CoreEvent): SubagentState {
        if (event.sessionId != current.agentId) return current
        val startedAt = runCatching { Instant.parse(current.startedAt).toEpochMilliseconds() }.getOrNull() ?: 0L
        val state = ConversationState(
            conversation = Conversation(id = current.agentId, projectId = null, title = current.task,
                status = if (current.status == "RUNNING") ConversationStatus.Working else ConversationStatus.Idle,
                createdAt = startedAt, updatedAt = startedAt),
            messages = current.messages, tokenUsage = TokenUsage(), cost = CostSummary(),
            errorMessage = current.errorMessage, errorId = current.errorId,
            errorDiagnostic = current.errorDiagnostic, statusHint = current.statusHint)
        val next = ConversationStateReducer.apply(state, event)
        val activity = when (event.type) {
            CoreEventType.TOOL_CALLED -> "TOOL_CALL"
            CoreEventType.TOOL_RESULT -> "THINKING"
            CoreEventType.MESSAGE_COMPLETED, CoreEventType.MESSAGE_ERROR -> null
            CoreEventType.MESSAGE_DELTA -> when (event.payload["type"]) {
                "text" -> "OUTPUT"
                "tool_call" -> "TOOL_CALL"
                "reasoning", "response_started" -> "THINKING"
                else -> current.currentActivity
            }
            else -> current.currentActivity
        }
        val tool = if (activity == "TOOL_CALL") event.payload["tool"] ?: event.payload["name"] ?: current.currentTool else null
        val body = next.messages.filter { it.role == ChatRole.Assistant }.flatMap { it.blocks }
            .filterIsInstance<ChatBlock.Text>().joinToString("") { it.text }
        return current.copy(messages = next.messages, currentActivity = activity, currentTool = tool,
            errorMessage = next.errorMessage, errorId = next.errorId,
            errorDiagnostic = next.errorDiagnostic, statusHint = next.statusHint,
            outputText = body.takeIf { it.isNotEmpty() } ?: current.outputText)
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
