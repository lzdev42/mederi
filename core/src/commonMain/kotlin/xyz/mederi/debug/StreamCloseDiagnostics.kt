package xyz.mederi.debug

/**
 * SSE 流关闭时捕获的结构化诊断——回答"谁关的连接、为什么关"。
 *
 * 由 [xyz.mederi.provider.infrastructure.koog.sanitize.MederiOpenAILLMClient] 的 `onCompletion` 块写入，
 * [xyz.mederi.koog.TurnExecutor] 在生成断流警告时读取，作为 `ErrorCollector.collectWarning` 的 `detail` 传入。
 *
 * 核心判读（`mode` 字段）：
 * - `done`           → 正常结束（收到 [DONE] 或 finishReason）
 * - `premature-close` → 服务端/代理关闭了连接，**无异常抛出** → 责任在 SERVER/PROXY
 * - `exception`      → 抛了异常（超时/重置/HTTP 错误等）→ 看 `errorType` 进一步判定
 *
 * `lastRawLines` 与 `errorSseLines` 在 `premature-close` 模式下尤其有价值：
 * 服务端关闭前可能发了 `event: error` 或 error data 行，这些在正常过滤中被丢弃，
 * 这里单独截获供排查。
 */
data class StreamCloseDiagnostics(
    /** 流结束模式：done / premature-close / exception */
    val mode: String,
    /** 收到的 SSE 行数 */
    val linesReceived: Int,
    /** 收到的字节数 */
    val bytesReceived: Long,
    /** 流持续时间（ms，首帧到末帧） */
    val durationMs: Long,
    /** 帧间最大间隔（ms）——大值暗示超时 */
    val maxGapMs: Long,
    /** 首字节时间（ms）—— -1 表示无数据 */
    val ttfbMs: Long,
    /** 最后 N 条原始 SSE 行（data: 过滤前，含注释行/event 行） */
    val lastRawLines: List<String> = emptyList(),
    /** 非 data: 行中疑似错误信号的行（含 "error" / "event:" 前缀等） */
    val errorSseLines: List<String> = emptyList(),
    /** exception 模式下的异常类型名（premature-close 为 null） */
    val errorType: String? = null,
    /** exception 模式下的异常消息首行 */
    val errorMessage: String? = null
) {
    /**
     * 人类可读摘要——拼入 `collectWarning` 的 `detail` 参数。
     * 责任方判定直接写明，不需要调用方再解读。
     */
    fun summary(): String = buildString {
        appendLine("流关闭模式: $mode")
        when (mode) {
            "done" -> appendLine("责任方: 正常结束（收到 [DONE]）")
            "premature-close" -> {
                appendLine("责任方: SERVER/PROXY（服务端关闭连接，无异常抛出——客户端网络问题会抛异常而非正常结束）")
                appendLine("已接收: $linesReceived 行 / $bytesReceived 字节")
                appendLine("持续: ${durationMs}ms, 首字节: ${ttfbMs}ms, 最大间隔: ${maxGapMs}ms")
                if (errorSseLines.isNotEmpty()) {
                    appendLine("服务端错误信号（非 data: 行）:")
                    errorSseLines.take(5).forEach { appendLine("  $it") }
                }
                if (lastRawLines.isNotEmpty()) {
                    appendLine("最后 ${lastRawLines.size} 条原始 SSE 行:")
                    lastRawLines.forEach { appendLine("  ${it.take(200)}") }
                }
            }
            "exception" -> {
                appendLine("责任方: 需看异常类型判定（$errorType）")
                appendLine("异常消息: ${errorMessage ?: "无"}")
                appendLine("已接收: $linesReceived 行 / $bytesReceived 字节")
            }
            else -> appendLine("责任方: 未知")
        }
    }
}
