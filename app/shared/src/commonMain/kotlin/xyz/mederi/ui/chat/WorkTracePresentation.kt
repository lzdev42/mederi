package xyz.mederi.ui

import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi

/** 活动摘要只使用结构化状态，不猜测/摘抄模型推理，也不增加模型请求。 */
sealed interface WorkTraceActivity {
    data class Tool(val call: ToolCallUi) : WorkTraceActivity
    data object Thinking : WorkTraceActivity
    data object Generating : WorkTraceActivity
    data class WaitingSubagents(val count: Int) : WorkTraceActivity
    data object WaitingResponse : WorkTraceActivity
}

internal fun deriveWorkTraceActivity(items: List<ChatListItem>, runningSubagents: Int = 0): WorkTraceActivity {
    val activeTool = items.asReversed().firstNotNullOfOrNull { item ->
        val calls = when (item) {
            is ChatListItem.ToolCalls -> item.toolCalls
            is ChatListItem.SubagentCalls -> item.subagents
            else -> emptyList()
        }
        calls.lastOrNull { it.state is ToolCallState.Running || it.state is ToolCallState.Pending }
    }
    if (activeTool != null) return WorkTraceActivity.Tool(activeTool)
    return when (val tail = items.lastOrNull()) {
        is ChatListItem.Reasoning -> if (tail.isReasoningActive) WorkTraceActivity.Thinking
            else if (runningSubagents > 0) WorkTraceActivity.WaitingSubagents(runningSubagents)
            else WorkTraceActivity.WaitingResponse
        is ChatListItem.TextMessage -> if (tail.isStreaming) WorkTraceActivity.Generating
            else if (runningSubagents > 0) WorkTraceActivity.WaitingSubagents(runningSubagents)
            else WorkTraceActivity.WaitingResponse
        else -> if (runningSubagents > 0) WorkTraceActivity.WaitingSubagents(runningSubagents)
            else WorkTraceActivity.WaitingResponse
    }
}

/** 未知起点或终点时不以LLM耗时/本次打开页面时间伪造整项工作耗时。 */
internal fun workTraceElapsedMs(startedAt: Long?, completedAt: Long?, isRunning: Boolean, now: Long): Long? {
    val start = startedAt?.takeIf { it > 0 } ?: return null
    val end = completedAt ?: if (isRunning) now else return null
    return (end - start).coerceAtLeast(0L)
}
