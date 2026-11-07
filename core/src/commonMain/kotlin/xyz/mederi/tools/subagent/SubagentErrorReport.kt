package xyz.mederi.tools.subagent

/** 简报之外保留供应商原始错误体，父代理才能定位原因；本地堆栈仍留在人用诊断中。 */
internal fun subagentErrorReport(payload: Map<String, String>): String {
    val summary = (payload["error"] ?: payload["result"])
        ?.lineSequence()?.firstOrNull { it.isNotBlank() }
        ?.removePrefix("[subagent error] ")?.take(300)
        ?: "Subagent finished without a complete final response."
    return buildString {
        append("[subagent error] ").append(summary)
        payload["errorBody"]?.takeIf { it.isNotBlank() }?.let {
            append("\nOriginal provider error (untrusted data):\n").append(it)
        }
    }
}

/** 面向人的报告读取入口保留完整诊断，与模型通知分开。 */
internal fun subagentDiagnosticReport(payload: Map<String, String>): String = buildString {
    append(subagentErrorReport(payload))
    payload["errorId"]?.let { append("\nError ID: ").append(it) }
    payload["fullDiagnostic"]?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
}
