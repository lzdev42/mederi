package xyz.mederi.koog

import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.serialization.SerializationException
import xyz.mederi.infrastructure.koog.toAssistantMessageSafe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * salvage-keeps-raw 验收：toAssistantMessageSafe 的 salvage 分支。
 *
 * 旧行为有两个坏点：坏 JSON 调用的 args 被抹成 "{}"；且 filter 只保留"解析失败"的
 * ToolCallComplete，把同轮合法 toolCall 一并丢弃。
 * 新语义：保留全部 ToolCallComplete 与全部 TextComplete，坏调用的 args 原样保留原始
 * 字符串——下游 GenericAgentEnvironment 访问 argsJson 抛 kotlinx 错误并回喂模型自纠，
 * 合法调用不再被连累。
 */
class StreamSalvageTest {

    private val badArgs = """{"path":"unterminated"""
    private val goodArgs = """{"path":"src","pattern":"TODO"}"""

    private fun framesWithBadToolCall(): List<StreamFrame> = listOf(
        StreamFrame.TextComplete(text = "Let me read", index = 0),
        StreamFrame.ToolCallComplete(id = "call_bad", name = "read_file", content = badArgs, index = 1),
        StreamFrame.ToolCallComplete(id = "call_ok", name = "grep_dir", content = goodArgs, index = 2),
        StreamFrame.End(finishReason = "tool_calls", metaInfo = ResponseMetaInfo.Empty)
    )

    @Test
    fun `precondition - koog toMessageResponse throws on bad tool args`() {
        // 前置：原始流必须让 Koog 重建抛错，否则测的不是 salvage 分支
        assertFailsWith<SerializationException> {
            framesWithBadToolCall().toMessageResponse()
        }
    }

    @Test
    fun `salvage keeps all tool calls and raw args`() {
        val result = framesWithBadToolCall().toAssistantMessageSafe()

        val calls = result.parts.filterIsInstance<MessagePart.Tool.Call>()
        assertEquals(2, calls.size, "salvage 必须保留全部 ToolCallComplete（含坏 JSON 那个）")

        val bad = calls[0]
        assertEquals("call_bad", bad.id)
        assertEquals("read_file", bad.tool)
        assertEquals(badArgs, bad.args, "坏调用的 args 必须保留原始字符串，不被抹成 {}")

        val good = calls[1]
        assertEquals("call_ok", good.id)
        assertEquals("grep_dir", good.tool)
        assertEquals(goodArgs, good.args, "合法调用的 args 原样保留")

        // 文本帧同样保留
        assertEquals(listOf("Let me read"), result.parts.filterIsInstance<MessagePart.Text>().map { it.text })

        // 坏 args 原样进入 Tool.Call 后，下游 GenericAgentEnvironment 访问 argsJson 仍会抛错
        // （被 catch 后以 "failed to parse arguments due to the error: ..." 回喂模型）——
        // 这正是期望语义：错误被如实暴露给模型自纠，而不是被掩埋成空对象。
        assertFailsWith<SerializationException> { bad.argsJson }
    }
}