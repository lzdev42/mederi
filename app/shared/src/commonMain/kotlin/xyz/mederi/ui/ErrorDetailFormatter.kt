package xyz.mederi.ui

import xyz.mederi.AppInfo
import xyz.mederi.getPlatform

/**
 * 错误详情对话框的字符串解析与报告模板拼装——纯函数，零 Compose 依赖。
 *
 * 逻辑从 `ui/components/ErrorDetailDialog.kt` 组合期 remember 块搬移而来（保持行为一致），
 * 可在任意层（ViewModel / 工具函数 / 测试）直接调用。
 */

/**
 * 从 errorSummary 中提取分类 Badge，例如 "[API] KoogHttpClientException..." → "API"。
 * 原逻辑：startsWith("[") && contains("]") → substringAfter("[").substringBefore("]")，否则 null。
 */
fun extractErrorCategory(errorSummary: String): String? {
    return if (errorSummary.startsWith("[") && errorSummary.contains("]")) {
        errorSummary.substringAfter("[").substringBefore("]")
    } else {
        null
    }
}

/**
 * 去掉分类 Badge 前缀后的干净错误简述（category 为 null 时原样返回）。
 * 原逻辑：category != null → substringAfter("]").trim()，否则原串。
 */
fun cleanErrorSummary(errorSummary: String, category: String?): String {
    return if (category != null) {
        errorSummary.substringAfter("]").trim()
    } else {
        errorSummary
    }
}

/**
 * 从诊断报告中提取 Suggestion 恢复建议（如有）——兼容 `Suggestion:`（异常路径）
 * 与 `建议：`（collectWarning 断流路径）。
 */
fun extractErrorSuggestion(errorDiagnostic: String): String? {
    val line = errorDiagnostic.lineSequence().find { it.startsWith("Suggestion:") || it.startsWith("建议：") }
    return line?.let {
        when {
            it.startsWith("Suggestion:") -> it.removePrefix("Suggestion:").trim()
            it.startsWith("建议：") -> it.removePrefix("建议：").trim()
            else -> null
        }
    }
}

/**
 * 拼装 Markdown Bug Report 模板（用于剪贴板复制和 GitHub Issue 内容填充）。
 *
 * 版本号与平台名内部直接取 [AppInfo.VERSION] / [getPlatform]（均 commonMain 非 Compose）；
 * 其余文案参数由调用方（composable）stringResource 解析后传入。
 */
fun buildBugReportMarkdown(
    envTitle: String,
    appVersionLabel: String,
    platformLabel: String,
    summaryTitle: String,
    noSummaryText: String,
    logsTitle: String,
    detailsSummary: String,
    reportFooter: String,
    errorSummary: String,
    errorDiagnostic: String,
): String {
    return buildString {
        appendLine("### $envTitle")
        appendLine("- **$appVersionLabel**: ${AppInfo.VERSION}")
        appendLine("- **$platformLabel**: ${getPlatform().name}")
        appendLine()
        appendLine("### $summaryTitle")
        appendLine("```")
        appendLine(errorSummary.ifBlank { noSummaryText })
        appendLine("```")
        appendLine("### $logsTitle")
        appendLine("<details open>")
        appendLine("<summary>$detailsSummary</summary>")
        appendLine()
        appendLine("```")
        appendLine(errorDiagnostic.ifBlank { errorSummary })
        appendLine("```")
        appendLine("</details>")
        appendLine()
        appendLine("---")
        appendLine("*$reportFooter*")
    }
}