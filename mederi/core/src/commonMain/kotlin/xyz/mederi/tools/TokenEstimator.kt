package xyz.mederi.tools

import ai.koog.prompt.message.Message as KoogMessage
import ai.koog.prompt.message.MessagePart as KoogMessagePart
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole

/**
 * token 估算统一入口（全应用唯一实现，双入口对应两种消息模型）。
 *
 * 精度策略——**真实值优先，估算兜底**：
 * - [contextUsedTokens]：当前上下文占用 = 最近一条 Assistant 的 inputTokens
 *   （API 报告的真实 prompt 大小）；端点不报告时回退加权估算。
 *   压缩触发、get_context_remaining、UI 上下文百分比都应使用它，保证判定与显示同源。
 * - [estimateTokens]：纯加权估算，用于增量（本轮新输入）和无真实值可用的场景。
 *
 * 加权规则：现代 BPE 词表下 ASCII ≈ 4 字符/token、CJK ≈ 1.1 字/token
 * （宁可略高估——早压不晚压）、图片固定 2000、其他 50。
 * 估算误差对 70% 阈值判定的整体影响 < 窗口的 1%（增量通常只占窗口百分之几）。
 */

// ==================== domain Message（tools 层） ====================

@JvmName("estimateTokensDomain")
fun estimateTokens(messages: List<Message>): Int =
    messages.sumOf { msg -> msg.parts.sumOf { domainPartTokens(it) } }

@JvmName("contextUsedTokensDomain")
fun contextUsedTokens(messages: List<Message>): Int =
    messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.inputTokens
        ?: estimateTokens(messages)

private fun domainPartTokens(part: MessagePart): Int = when (part) {
    is MessagePart.Text -> weightedTokens(part.text)
    is MessagePart.ToolCall -> weightedTokens(part.tool + part.args)
    is MessagePart.ToolResult -> weightedTokens(part.output)
    is MessagePart.Reasoning -> weightedTokens(part.content.joinToString(""))
    // 视觉编码器一张图典型 300~1500 token，固定取高值：低估会漏压，高估无害
    is MessagePart.Image -> 2000
    is MessagePart.File -> 0
}

// ==================== Koog Message（infrastructure.koog 层） ====================

fun estimateTokens(messages: List<KoogMessage>): Int =
    messages.sumOf { msg ->
        msg.parts.sumOf { part ->
            when (part) {
                is KoogMessagePart.Text -> weightedTokens(part.text)
                is KoogMessagePart.Tool.Call -> weightedTokens(part.args)
                is KoogMessagePart.Tool.Result -> weightedTokens(part.output)
                is KoogMessagePart.Attachment -> 2000
                else -> 50
            }
        }
    }

fun contextUsedTokens(messages: List<KoogMessage>): Int =
    (messages.lastOrNull { it is KoogMessage.Assistant } as? KoogMessage.Assistant)
        ?.metaInfo?.inputTokensCount ?: estimateTokens(messages)

// ==================== 共享加权核心 ====================

fun weightedTokens(text: String): Int {
    var ascii = 0
    var cjk = 0
    for (ch in text) {
        if (ch.code in 0x3000..0x9FFF || ch.code in 0xF900..0xFAFF || ch.code in 0xFF00..0xFFEF) cjk++
        else ascii++
    }
    return ascii / 4 + (cjk * 1.1).toInt()
}
