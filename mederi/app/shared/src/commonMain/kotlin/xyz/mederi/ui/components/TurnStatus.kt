package xyz.mederi.ui.components

import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.models.*

/**
 * AI 一轮对话的实时状态。从 ConversationSnapshot 派生，UI 层无需自行判断。
 */
enum class TurnStatus(val label: String) {
    Idle("空闲"),
    /** 消息已提交，turn 尚未开始（等待 core 受理/新会话创建完成） */
    Sending("正在发送"),
    /** 请求已发出，等待模型返回首个 token（网络在途） */
    Preparing("已送达，等待回应"),
    Thinking("AI 思考中"),
    CallingTool("调用工具中"),
    Generating("正在生成回复"),
    WaitingAnswer("等待回答"),
    Retrying("正在自动重试"),
    Aborted("已中断");

    /**
     * 该状态是否需要在状态栏（StatusBar）中展示。
     * - 等待响应阶段（Sending, Preparing）：展示状态栏以消除用户焦虑；
     * - 收到推理/正文/工具等内容时（Thinking, Generating, CallingTool...）：卡片内已有对应内容在渲染，状态栏隐藏；
     * - 仅在异常重试时（Retrying）：状态栏重新出现，展示重试轮次与真实错误信息。
     */
    val shouldDisplayInStatusBar: Boolean
        get() = when (this) {
            Sending, Preparing, Retrying -> true
            Thinking, Generating, CallingTool, WaitingAnswer, Aborted, Idle -> false
        }
}

/**
 * 重试状态的结构化信息，由 [parseRetryHint] 从 statusHint 字符串中解析。
 * [attempt]/[max] 表示当前是第几次/最多几次；[serverMsg] 是供应商返回的真实错误原因。
 */
data class RetryHint(
    val attempt: String,
    val max: String,
    val serverMsg: String?,
    val retryAtMillis: Long? = null,
)

/**
 * 将 SnapshotReducer 存入 statusHint 的格式（"attempt/max"、"attempt/max|serverMsg" 或 "attempt/max|serverMsg|retryAt"）
 * 解析成结构体，供 StatusBar 按需渲染。
 */
fun parseRetryHint(statusHint: String?): RetryHint? {
    if (statusHint == null) return null
    val firstPipe = statusHint.indexOf('|')
    // 无 '|'：仅 "attempt/max"
    if (firstPipe < 0) {
        val progress = statusHint.split("/")
        return RetryHint(
            attempt = progress.getOrNull(0) ?: "?",
            max = progress.getOrNull(1) ?: "?",
            serverMsg = null,
            retryAtMillis = null,
        )
    }
    val progress = statusHint.substring(0, firstPipe).split("/")
    val attempt = progress.getOrNull(0) ?: "?"
    val max = progress.getOrNull(1) ?: "?"
    val rest = statusHint.substring(firstPipe + 1) // "serverMsg|retryAt" 或 "serverMsg"
    val lastPipe = rest.lastIndexOf('|')
    if (lastPipe < 0) {
        // 仅 serverMsg，无 retryAt
        return RetryHint(attempt, max, serverMsg = rest.takeIf { it.isNotBlank() }, retryAtMillis = null)
    }
    val possibleRetryAt = rest.substring(lastPipe + 1).toLongOrNull()
    return if (possibleRetryAt != null) {
        // 末段是数字 → retryAt，中间是 serverMsg（可含 |）
        RetryHint(
            attempt = attempt,
            max = max,
            serverMsg = rest.substring(0, lastPipe).takeIf { it.isNotBlank() },
            retryAtMillis = possibleRetryAt,
        )
    } else {
        // 末段非数字 → 整个 rest 是 serverMsg，无 retryAt
        RetryHint(attempt, max, serverMsg = rest.takeIf { it.isNotBlank() }, retryAtMillis = null)
    }
}

fun deriveTurnStatus(snapshot: ConversationSnapshot?): TurnStatus {
    val snap = snapshot ?: return TurnStatus.Idle
    return when (snap.conversation.status) {
        // 轮次已结束：错误/警告一律由 ErrorBoard（输入框上方）展示，StatusBar 只显示运转过程状态。
        // 故 Error 状态也回落 Idle（错误信息在 ErrorBoard 呈现），StatusBar 永不显示错误。
        ConversationStatus.Error -> TurnStatus.Idle
        ConversationStatus.Idle -> TurnStatus.Idle
        ConversationStatus.WaitingUser -> TurnStatus.WaitingAnswer
        ConversationStatus.Working -> {
            when {
                // 环境态过程提示（供应商限流重试中）优先于内容派生——此时模型没在产出
                snap.statusHint != null -> TurnStatus.Retrying
                snap.pendingQuestion != null -> TurnStatus.WaitingAnswer
                else -> {
                    // 只看正在流式的 assistant 消息（aggregator 占位消息跨整个 turn 累积所有轮次的块）。
                    // 不回退到已完成消息：两轮之间的空窗（下一轮请求在途）拿旧正文判会误显示"生成回复中"。
                    val streaming = snap.messages.lastOrNull { it.isStreaming && it.role == ChatRole.Assistant }
                    when {
                        streaming == null -> TurnStatus.Preparing
                        streaming.blocks.any {
                            it is ChatBlock.ToolCall && (it.state is ToolCallState.Running || it.state is ToolCallState.Pending)
                        } -> TurnStatus.CallingTool
                        else -> when (val lastBlock = streaming.blocks.lastOrNull()) {
                            // 以"最近活动的块"为准（块按事件到达顺序排列）：
                            null -> TurnStatus.Preparing                   // 占位已建，首个 delta 未到
                            is ChatBlock.Reasoning -> TurnStatus.Thinking  // 推理流式中（含后续轮次的思考）
                            is ChatBlock.Text -> TurnStatus.Generating     // 正文流式中
                            is ChatBlock.ToolCall -> TurnStatus.Preparing  // 工具已完成，下一轮请求在途
                            else -> TurnStatus.Preparing                   // File/Diff/Unknown：按等待处理
                        }
                    }
                }
            }
        }
    }
}

