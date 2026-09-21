package xyz.mederi.core.ui.chat

import xyz.mederi.core.contract.models.ToolCallUi

/**
 * 工具动作大类枚举（用于时序行图标与聚合展示）。
 * 数据投影层（core/ui/chat）单一来源：视图层不得再定义工具名→动作分类规则。
 */
enum class ToolActionKind {
    COMMAND, READ, EDIT, SEARCH, LIST, SUBAGENT, MCP, ASK, TODO, OTHER
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
        lower.contains("ask") -> ToolActionKind.ASK
        lower.contains("todo") -> ToolActionKind.TODO
        else -> ToolActionKind.OTHER
    }
}

/**
 * 连续同类工具动作聚合组。
 */
data class ToolActionGroup(
    val kind: ToolActionKind,
    val calls: List<ToolCallUi>,
)

/**
 * 将同批连续的同类工具调用聚合为动作组。
 */
fun groupToolCallsByAction(calls: List<ToolCallUi>): List<ToolActionGroup> {
    if (calls.isEmpty()) return emptyList()
    val groups = mutableListOf<ToolActionGroup>()
    var currentKind = classifyToolAction(calls.first().name)
    var currentList = mutableListOf(calls.first())
    for (i in 1 until calls.size) {
        val call = calls[i]
        val kind = classifyToolAction(call.name)
        if (kind == currentKind) {
            currentList.add(call)
        } else {
            groups.add(ToolActionGroup(currentKind, currentList))
            currentKind = kind
            currentList = mutableListOf(call)
        }
    }
    if (currentList.isNotEmpty()) {
        groups.add(ToolActionGroup(currentKind, currentList))
    }
    return groups
}