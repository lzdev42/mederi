package xyz.mederi.koog

import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import xyz.mederi.infrastructure.koog.toAssistantMessageSafe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmptyResponseGuardTest {

    @Test
    fun endOnlyResponseHasNoContent() {
        // 模型只返回 End 帧，没有任何内容
        val frames = listOf(
            StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty)
        )
        val result = frames.toAssistantMessageSafe()
        assertTrue(result.parts.isEmpty())
        assertEquals("stop", result.finishReason)
    }

    @Test
    fun reasoningOnlyResponsePreservesReasoning() {
        // 模型只输出了思考过程，没有正文也没有工具调用
        val frames = listOf(
            StreamFrame.ReasoningComplete(
                id = "r1", content = listOf("Let me think..."), summary = null, encrypted = null, index = 0
            ),
            StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty)
        )
        val result = frames.toAssistantMessageSafe()
        val reasoning = assertIs<MessagePart.Reasoning>(result.parts.single())
        assertEquals(listOf("Let me think..."), reasoning.content)
    }

    @Test
    fun blankTextBlocksAreOmitted() {
        val frames = listOf(
            StreamFrame.TextComplete(text = "", index = 0),
            StreamFrame.TextComplete(text = " \n\t", index = 1),
            StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty)
        )
        assertTrue(frames.toAssistantMessageSafe().parts.isEmpty())
    }

    @Test
    fun whitespaceWithinMeaningfulTextIsPreserved() {
        val frames = listOf(
            StreamFrame.TextComplete(text = "  ", index = 0),
            StreamFrame.TextComplete(text = "Hello world\n", index = 0),
            StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty)
        )
        val result = frames.toAssistantMessageSafe()
        val text = assertIs<MessagePart.Text>(result.parts.single())
        assertEquals("  Hello world\n", text.text)
    }

    @Test
    fun toolCallOnlyResponsePreservesToolCall() {
        // 有工具调用的响应，不应额外注入
        val frames = listOf(
            StreamFrame.ToolCallComplete(
                id = "call_1", name = "read_file", content = """{"path":"test.txt"}""", index = 0
            ),
            StreamFrame.End(finishReason = "tool_calls", metaInfo = ResponseMetaInfo.Empty)
        )
        val result = frames.toAssistantMessageSafe()
        assertEquals("read_file", assertIs<MessagePart.Tool.Call>(result.parts.single()).tool)
    }

    @Test
    fun emptyFrameListHasNoContent() {
        // 极端情况：连 End 帧都没有
        val frames = emptyList<StreamFrame>()
        val result = frames.toAssistantMessageSafe()
        assertTrue(result.parts.isEmpty())
    }
}
