package xyz.mederi.ui

import okio.ByteString.Companion.decodeBase64
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.ImageAttachment
import xyz.mederi.core.contract.models.PastedTextAttachment
import xyz.mederi.util.PromptComposer

/** 回退消息后放回输入框的内容：主指令 + 大段文本附件 + 图片附件（原样恢复，非脱壳简化） */
data class RestoredInput(
    val instruction: String,
    val pastedTexts: List<PastedTextAttachment>,
    val images: List<ImageAttachment>,
) {
    val isEmpty: Boolean get() = instruction.isBlank() && pastedTexts.isEmpty() && images.isEmpty()
}

/**
 * 从一条已发送的用户消息反解出可放回输入框的内容（纯函数，单测直接覆盖）：
 * - 主指令 = `PromptComposer.parse` 剥离大段文本 XML 标签后的主指令部分
 * - `pastedTexts` = parse 拆出的大段文本附件，index 重新编号、id 改为当前时间戳前缀
 * - `images` = 消息中 `data:` URL 的 `ChatBlock.File` → base64 解码还原 `ImageAttachment`
 *   （回退是"恢复已发内容"，不做模型图片能力门禁；真发送时 send() 的 guardImageSupport 会拦）
 */
fun restoreInputFromMessage(targetMsg: ChatMessage?, fallbackText: String): RestoredInput {
    val fullText = targetMsg?.blocks
        ?.filterIsInstance<ChatBlock.Text>()
        ?.joinToString("") { it.text }
        ?.ifBlank { fallbackText } ?: fallbackText
    val parsed = PromptComposer.parse(fullText)
    val images = targetMsg?.blocks
        ?.filterIsInstance<ChatBlock.File>()
        ?.filter { it.url.startsWith("data:") && it.url.contains("base64,") }
        ?.mapIndexedNotNull { i, block ->
            val bytes = block.url.substringAfter("base64,", "").decodeBase64()?.toByteArray()
            if (bytes != null) {
                ImageAttachment(
                    id = "img_${targetMsg.id}_$i",
                    name = block.name.ifBlank { "image_${i + 1}" },
                    mimeType = block.mimeType ?: "image/png",
                    bytes = bytes,
                    base64DataUrl = block.url,
                )
            } else null
        } ?: emptyList()
    val pastedTexts = parsed.pastedTexts.mapIndexed { i, item ->
        item.copy(
            id = "pasted_${targetMsg?.id ?: "rollback"}_${i + 1}",
            index = i + 1
        )
    }
    return RestoredInput(
        instruction = parsed.instruction,
        pastedTexts = pastedTexts,
        images = images
    )
}