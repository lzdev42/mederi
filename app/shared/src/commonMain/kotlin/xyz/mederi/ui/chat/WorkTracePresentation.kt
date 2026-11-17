package xyz.mederi.ui

import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi
import xyz.mederi.ui.chat.ToolActionKind
import xyz.mederi.ui.chat.classifyToolAction

private const val WORK_NOTE_MARKER = "<!-- mederi:work-note -->"

internal sealed interface WorkTraceEntry {
    val key: String

    data class Step(val item: ChatListItem) : WorkTraceEntry {
        override val key: String get() = item.key
    }

    data class Details(
        override val key: String,
        val items: List<ChatListItem>,
        val actionCounts: Map<ToolActionKind, Int>,
        val failureCount: Int,
        val noteCount: Int,
    ) : WorkTraceEntry
}

/** The exact marker opts into folding. Partial prefixes can wait for the next streaming delta. */
private fun ChatListItem.TextMessage.workNoteBody(): String? {
    if (isUser) return null
    val candidate = text.trimStart()
    val firstLine = candidate.substringBefore('\n').trimEnd()
    return when {
        firstLine == WORK_NOTE_MARKER -> candidate.substringAfter('\n', "").trimStart('\r', '\n')
        isStreaming && candidate.isNotEmpty() && WORK_NOTE_MARKER.startsWith(candidate) -> ""
        else -> null
    }
}

internal fun ChatListItem.isVisibleWorkProgress(): Boolean = when (this) {
    is ChatListItem.TextMessage -> images.isNotEmpty() || (text.isNotBlank() && workNoteBody() == null)
    is ChatListItem.DocumentCard -> true
    else -> false
}

/** Fold adjacent routine calls and explicit work notes without modifying the underlying work log. */
internal fun projectWorkTraceEntries(
    items: List<ChatListItem>,
    detailBreakBefore: Set<String>,
): List<WorkTraceEntry> = buildList {
    var detailKey: String? = null
    val details = mutableListOf<ChatListItem>()

    fun flushDetails() {
        val key = detailKey
        if (key != null && details.isNotEmpty()) {
            val calls = details.filterIsInstance<ChatListItem.ToolCalls>().flatMap { it.toolCalls }
            add(WorkTraceEntry.Details(
                key = key,
                items = details.toList(),
                actionCounts = calls.groupingBy(::workTraceDetailKind).eachCount(),
                failureCount = calls.count { it.isFailed || it.state is ToolCallState.Failed },
                noteCount = details.count { it is ChatListItem.TextMessage },
            ))
        }
        detailKey = null
        details.clear()
    }

    for (item in items) {
        if (item.key in detailBreakBefore) flushDetails()
        val note = (item as? ChatListItem.TextMessage)?.workNoteBody()
        when {
            item is ChatListItem.ToolCalls && item.planOutcome == null -> {
                if (detailKey == null) detailKey = item.key
                details.add(item)
            }
            item is ChatListItem.TextMessage && note != null && item.images.isEmpty() -> {
                if (detailKey == null) detailKey = item.key
                if (note.isNotBlank()) details.add(item.copy(text = note))
            }
            else -> {
                flushDetails()
                val displayItem = if (item is ChatListItem.TextMessage && note != null) item.copy(text = note) else item
                add(WorkTraceEntry.Step(displayItem))
            }
        }
    }
    flushDetails()
}

private fun workTraceDetailKind(call: ToolCallUi): ToolActionKind = when (val kind = classifyToolAction(call)) {
    ToolActionKind.COMMAND -> classifySimpleCommand(call.target)
    ToolActionKind.WEB_FETCH -> ToolActionKind.READ
    ToolActionKind.LIST_PROCESSES -> ToolActionKind.LIST
    ToolActionKind.READ, ToolActionKind.SEARCH, ToolActionKind.EDIT, ToolActionKind.LIST,
    ToolActionKind.VERIFY, ToolActionKind.GENERATE_SPEC, ToolActionKind.SUBAGENT_STATUS,
    ToolActionKind.MCP, ToolActionKind.WEB_SEARCH -> kind
    else -> ToolActionKind.OTHER
}

private val shellOperators = charArrayOf(';', '&', '|', '<', '>', '\n', '\r', '`', '$', '(', ')', '\\')
private val sedPrintRange = Regex("""sed[ \t]+-n[ \t]+(?:[0-9,]+p|'[0-9,]+p'|"[0-9,]+p")[ \t]+[^\s-]\S*""")

/** 仅识别简单命令以优化摘要，原命令和执行结果仍可在工具明细中查看。 */
private fun classifySimpleCommand(command: String?): ToolActionKind {
    val text = command?.trim() ?: return ToolActionKind.COMMAND
    if (text.indexOfAny(shellOperators) >= 0) return ToolActionKind.COMMAND
    return when (text.takeWhile { !it.isWhitespace() }) {
        "rg", "grep" -> ToolActionKind.SEARCH
        "cat", "head", "tail" -> ToolActionKind.READ
        "ls" -> ToolActionKind.LIST
        "sed" -> if (sedPrintRange.matches(text)) ToolActionKind.READ else ToolActionKind.COMMAND
        else -> ToolActionKind.COMMAND
    }
}

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
            is ChatListItem.ToolCalls -> if (item.planOutcome == null) item.toolCalls else emptyList()
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
