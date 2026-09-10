package xyz.mederi.ui.components

import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.models.*

/**
 * AI 一轮对话的实时状态。从 ConversationSnapshot 派生，UI 层无需自行判断。
 */
enum class TurnStatus(val label: String) {
    Idle("空闲"),
    /** 消息已提交，turn 尚未开始（等待 core 受理/新会话创建完成） */
    Sending("发送中"),
    /** 请求已发出，等待模型返回首个 token（网络在途） */
    Preparing("请求模型中"),
    Thinking("思考中"),
    CallingTool("调用工具中"),
    Generating("生成回复中"),
    WaitingAnswer("等待回答"),
    Retrying("重试中"),
    Error("出错了"),
    Aborted("已中断"),
    /** 流式传输异常警告（连接中断/消费错误）：回复已收尾但可能不完整 */
    Warning("回复可能不完整"),
}

fun deriveTurnStatus(snapshot: ConversationSnapshot?): TurnStatus {
    val snap = snapshot ?: return TurnStatus.Idle
    return when (snap.conversation.status) {
        ConversationStatus.Error -> TurnStatus.Error
        ConversationStatus.Idle -> {
            // 流式传输警告（连接中断等，随 MESSAGE_COMPLETED 的 warning payload 到达）：
            // Idle 态保留在状态栏提示"回复可能不完整"
            if (snap.statusHint != null) TurnStatus.Warning
            // Idle 但最后一条消息有 error 标记 -> Aborted
            else if (snap.messages.lastOrNull()?.error != null) TurnStatus.Aborted
            else TurnStatus.Idle
        }
        ConversationStatus.Working -> {
            when {
                // 环境态过程提示（供应商限流重试中）优先于内容派生——此时模型没在产出
                snap.statusHint != null -> TurnStatus.Retrying
                snap.errorMessage != null -> TurnStatus.Retrying
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
