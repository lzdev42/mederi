package xyz.mederi.koog

import ai.koog.prompt.message.Message as KoogMessage
import ai.koog.prompt.message.MessagePart as KoogMessagePart
import xyz.mederi.domain.model.Message as MederiMessage
import xyz.mederi.domain.model.MessagePart as MederiMessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.MessageStatus
import xyz.mederi.infrastructure.koog.KoogMessageMapper
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
}
