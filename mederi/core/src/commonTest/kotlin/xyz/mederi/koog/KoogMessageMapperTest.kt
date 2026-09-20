package xyz.mederi.koog

import ai.koog.prompt.message.Message as KoogMessage
import ai.koog.prompt.message.MessagePart as KoogMessagePart
import xyz.mederi.domain.model.Message as MederiMessage
import xyz.mederi.domain.model.MessagePart as MederiMessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.MessageStatus
import xyz.mederi.infrastructure.koog.KoogMessageMapper
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.prompt.streaming.emitReasoningDelta
import ai.koog.prompt.streaming.emitTextDelta
import ai.koog.prompt.streaming.emitEnd
import xyz.mederi.infrastructure.koog.toAssistantMessageSafe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoogMessageMapperTest {

    @Test
    fun testAssistantImageFallback() {
        val coreMsg = MederiMessage(
            id = "msg_img_only",
            sessionId = "sess_1",
            role = MessageRole.ASSISTANT,
            parts = listOf(MederiMessagePart.Image(url = "https://example.com/art.png")),
            status = MessageStatus.COMPLETED,
            createdAt = "2026-09-08T12:00:00Z"
        )
        val koogMsg = KoogMessageMapper.toKoogMessage(coreMsg)
        assertTrue(koogMsg is KoogMessage.Assistant)
        assertEquals(1, koogMsg.parts.size, "Assistant 纯图片应平滑回退为文本占位，防止发给 LLM 时空 parts 崩溃")
        val textPart = koogMsg.parts.first() as KoogMessagePart.Text
        assertEquals("[Image: art.png]", textPart.text)
    }

    @Test
    fun testMergeFragmentedReasoningFrames() {
        val frames = listOf(
            ai.koog.prompt.streaming.StreamFrame.ReasoningComplete(id = "r1", content = listOf("Let"), summary = null, encrypted = null, index = 0),
            ai.koog.prompt.streaming.StreamFrame.ReasoningComplete(id = "r1", content = listOf(" me", " understand"), summary = null, encrypted = null, index = 0),
            ai.koog.prompt.streaming.StreamFrame.ReasoningComplete(id = null, content = listOf(" the bug"), summary = null, encrypted = null, index = 0),
            ai.koog.prompt.streaming.StreamFrame.TextComplete(text = "Here is the plan", index = 0),
            ai.koog.prompt.streaming.StreamFrame.End(finishReason = "stop", metaInfo = ai.koog.prompt.message.ResponseMetaInfo.Empty)
        )
        val assistant = frames.toAssistantMessageSafe()
        val reasoningParts = assistant.parts.filterIsInstance<KoogMessagePart.Reasoning>()
        val textParts = assistant.parts.filterIsInstance<KoogMessagePart.Text>()

        assertEquals(1, reasoningParts.size, "相邻的 ReasoningComplete 帧应合并为一个 Reasoning part")
        assertEquals(listOf("Let", " me", " understand", " the bug"), reasoningParts.first().content)
        assertEquals(1, textParts.size)
        assertEquals("Here is the plan", textParts.first().text)
    }

    @Test
    fun testUserImageStrippedWhenIncludeImagesFalse() {
        val coreMsg = MederiMessage(
            id = "msg_user_img",
            sessionId = "sess_1",
            role = MessageRole.USER,
            parts = listOf(
                MederiMessagePart.Text("看图"),
                MederiMessagePart.Image(url = "data:image/png;base64,AAAA")
            ),
            status = MessageStatus.COMPLETED,
            createdAt = "2026-09-08T12:00:00Z"
        )
        val koogMsg = KoogMessageMapper.toKoogMessage(coreMsg, includeImages = false)
        assertTrue(koogMsg is KoogMessage.User)
        assertEquals(1, koogMsg.parts.size, "includeImages=false 时用户消息的 Image part 应被剔除")
        val textPart = koogMsg.parts.single() as KoogMessagePart.Text
        assertEquals("看图", textPart.text)
    }

    @Test
    fun testUserImageKeptByDefault() {
        val coreMsg = MederiMessage(
            id = "msg_user_img2",
            sessionId = "sess_1",
            role = MessageRole.USER,
            parts = listOf(
                MederiMessagePart.Text("看图"),
                MederiMessagePart.Image(url = "data:image/png;base64,AAAA")
            ),
            status = MessageStatus.COMPLETED,
            createdAt = "2026-09-08T12:00:00Z"
        )
        val koogMsg = KoogMessageMapper.toKoogMessage(coreMsg)
        assertTrue(koogMsg is KoogMessage.User)
        assertEquals(2, koogMsg.parts.size, "默认 includeImages=true 时 Text + Attachment 都应保留")
        val textPart = koogMsg.parts.filterIsInstance<KoogMessagePart.Text>().single()
        assertEquals("看图", textPart.text)
        assertTrue(
            koogMsg.parts.filterIsInstance<KoogMessagePart.Attachment>().isNotEmpty(),
            "默认行为下用户消息的 Image 应转为 Attachment"
        )
    }
}
