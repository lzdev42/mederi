package xyz.mederi.ui

import xyz.mederi.core.contract.models.SubagentToolResult

/**
 * 子代理工具结果 → 汇报 markdown（ViewModel 层纯转换）。
 *
 * 数据本来就在数据库里（subagent(WAIT) 的 tool result 携带汇报全文 JSON），
 * 本函数只做"JSON → 可渲染 markdown"的投影，不新增任何存储：
 * UI 把 subagent 的工具卡片渲染成折叠的"子代理汇报"卡，点开时用
 * InkCompose 渲染本函数的输出。
 *
 * 返回 null = 不是可展开的子代理汇报（非目标工具 / 未完成 / 无结果文本），
 * UI 按普通工具卡片渲染。
 */
object SubagentReportMarkdown {

    /**
     * 会产出汇报 markdown 的工具（输出 SubagentToolResult JSON 形态）。
     * "subagent" = 2026-09 合并后的统一工具名（SubagentTool，按 action 分流）；
     * "wait_agent"/"agent_status" = 合并前的旧工具名（兼容历史消息记录）。
     */
    val REPORT_TOOL_NAMES = setOf("subagent", "wait_agent", "agent_status")

    /**
     * @param toolName 工具名（subagent / wait_agent / agent_status）
     * @param resultJson 工具输出的原始 JSON 字符串（tool result）
     * @return 汇报 markdown；null = 不适用（非目标工具 / JSON 无法解码 /
     *         状态非 COMPLETED / result 为空——TIMEOUT/RUNNING 等待中态无汇报可展开）
     */
    fun fromToolResult(toolName: String, resultJson: String): String? {
        if (toolName !in REPORT_TOOL_NAMES) return null
        val decoded = SubagentToolResult.decode(resultJson) ?: return null
        if (decoded.status != "COMPLETED") return null
        val report = decoded.result?.takeIf { it.isNotBlank() } ?: return null

        return buildString {
            appendLine("### Subagent Report")
            appendLine()
            appendLine("- **Agent**: `${decoded.agentId}`")
            val model = decoded.modelName ?: decoded.modelId
            if (model != null) {
                appendLine("- **Model**: $model" +
                    (decoded.reasoningLevel?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
            }
            appendLine("- **Status**: COMPLETED")
            appendLine()
            appendLine("---")
            appendLine()
            append(report)
        }
    }
}
