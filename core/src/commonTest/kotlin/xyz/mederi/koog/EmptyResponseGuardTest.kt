package xyz.mederi.koog

import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import xyz.mederi.infrastructure.koog.toAssistantMessageSafe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证 toAssistantMessageSafe() 的空响应兜底：
 * 模型返回空响应或纯思考响应时，补入 Text("") 防止图引擎卡死
 * （AIAgentStuckInTheNodeException）。
 */
class EmptyResponseGuardTest {

    @Test
    fun emptyFrames_injectsEmptyText() {
        // 模型只返回 End 帧，没有任何内容
        val frames = listOf(
            StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty)
        )
        val result = frames.toAssistantMessageSafe()
        // 必须有至少一个 Text part，否则图边条件全不匹配
        assertTrue(result.parts.any { it is MessagePart.Text }, "empty response should have injected Text")
    }

    @Test
    fun reasoningOnly_injectsEmptyText() {
        // 模型只输出了思考过程，没有正文也没有工具调用
        val frames = listOf(
            StreamFrame.ReasoningComplete(
                id = "r1", content = listOf("Let me think..."), summary = null, encrypted = null, index = 0
            ),
            StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty)
        )
        val result = frames.toAssistantMessageSafe()
        // Reasoning 应保留
        assertTrue(result.parts.any { it is MessagePart.Reasoning }, "reasoning should be preserved")
        // 必须补了一个 Text
        assertTrue(result.parts.any { it is MessagePart.Text }, "reasoning-only response should have injected Text")
    }

    @Test
    fun normalTextResponse_noInjection() {
        // 正常的文本响应，不应额外注入
        val frames = listOf(
            StreamFrame.TextComplete(text = "Hello world", index = 0),
            StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty)
        )
        val result = frames.toAssistantMessageSafe()
        val textParts = result.parts.filterIsInstance<MessagePart.Text>()
        assertEquals(1, textParts.size, "normal response should have exactly one Text part")
        assertEquals("Hello world", textParts.first().text)
    }

    @Test
    fun toolCallResponse_noInjection() {
        // 有工具调用的响应，不应额外注入
        val frames = listOf(
            StreamFrame.ToolCallComplete(
                id = "call_1", name = "read_file", content = """{"path":"test.txt"}""", index = 0
            ),
            StreamFrame.End(finishReason = "tool_calls", metaInfo = ResponseMetaInfo.Empty)
        )
        val result = frames.toAssistantMessageSafe()
        assertTrue(result.parts.any { it is MessagePart.Tool.Call }, "tool call should be present")
        // 有 Tool.Call 时不需要注入 Text
        assertEquals(1, result.parts.size, "tool-call-only response should not inject extra Text")
    }

    @Test
    fun completelyEmptyFrames_injectsEmptyText() {
        // 极端情况：连 End 帧都没有
        val frames = emptyList<StreamFrame>()
        val result = frames.toAssistantMessageSafe()
        assertTrue(result.parts.any { it is MessagePart.Text }, "completely empty frames should have injected Text")
    }
}
