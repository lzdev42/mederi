package xyz.mederi.ui.chat

import xyz.mederi.core.contract.models.ToolCallUi

/**
 * 工具动作大类枚举（用于时序行图标与聚合展示）。
 * 数据投影层（core/ui/chat）单一来源：视图层不得再定义工具名→动作分类规则。
 */
enum class ToolActionKind {
    COMMAND, READ, EDIT, SEARCH, LIST, SUBAGENT, MCP, ASK, TODO, VERIFY, OTHER
}

/**
 * 依据工具名分类动作大类。
 */
fun classifyToolAction(name: String): ToolActionKind {
    val lower = name.lowercase()
    return when {
        lower.contains("command") || lower == "bash" || lower.contains("exec") || lower.contains("terminal") -> ToolActionKind.COMMAND
        lower.contains("read") || lower.contains("view") || lower == "cat" -> ToolActionKind.READ
        lower.contains("edit") || lower.contains("patch") || lower.contains("replace") || lower.contains("write") || lower.contains("create") -> ToolActionKind.EDIT
        lower.contains("search") || lower.contains("grep") || lower.contains("find") -> ToolActionKind.SEARCH
        lower.contains("list") || lower.contains("dir") || lower.contains("tree") -> ToolActionKind.LIST
        lower == "subagent" || lower.contains("agent") -> ToolActionKind.SUBAGENT
        lower.contains("mcp") -> ToolActionKind.MCP
        isAskAction(lower) -> ToolActionKind.ASK
        lower.contains("verify") -> ToolActionKind.VERIFY
        lower.contains("todo") -> ToolActionKind.TODO
        else -> ToolActionKind.OTHER
    }
}

private fun isAskAction(lower: String): Boolean {
    // 排除包含 task/subtask 的工具名（如 verify_subtask）被子串误匹配
    if (lower.contains("task") && !lower.startsWith("ask")) return false
    return lower.startsWith("ask") ||
        lower.contains("question") ||
        lower.endsWith("_ask") ||
        lower.contains("_ask_") ||
        lower.contains("-ask")
}

/**
 * 连续同类工具动作聚合组。
 */
data class ToolActionGroup(
    val kind: ToolActionKind,
    val calls: List<ToolCallUi>,
)

/**
 * 将工具调用列表转换为单项动作组（严格保持时序不汇总，如实展示每个工具调用）。
 */
fun groupToolCallsByAction(calls: List<ToolCallUi>): List<ToolActionGroup> {
    return calls.map { call ->
        ToolActionGroup(classifyToolAction(call.name), listOf(call))
    }
}