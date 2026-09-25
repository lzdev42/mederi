package xyz.mederi.browser

import kotlinx.serialization.Serializable

/**
 * BrowserBrain 的判定与分析结果。
 *
 * @param success 判定/分析过程是否成功完成
 * @param data 提取的结构化键值对（例如 {"match": "true", "reason": "..."}）
 * @param text 模型给出的完整原始文本或分析报告
 * @param filesWritten 在分析/汇总过程中生成的文件列表（例如 reports/summary.html）
 */
@Serializable
data class BrainResult(
    val success: Boolean,
    val data: Map<String, String> = emptyMap(),
    val text: String = "",
    val filesWritten: List<String> = emptyList()
) {
    /**
     * 转为单行紧凑文本，供 Operator 录入 last_action_results / stepHistory。
     */
    fun toCompactString(): String {
        if (!success) return "FAIL: $text"
        val match = data["match"]
        val reason = data["reason"] ?: data["summary"]
        return when {
            match != null && reason != null -> "match=$match, reason=$reason"
            match != null -> "match=$match"
            data.isNotEmpty() -> data.entries.joinToString(", ") { "${it.key}=${it.value}" }
            text.isNotBlank() -> text.take(120).replace("\n", " ")
            else -> "ok"
        }
    }
}
