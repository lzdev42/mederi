package xyz.mederi.infrastructure.koog

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message as KoogMessage
import ai.koog.prompt.message.MessagePart as KoogMessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import xyz.mederi.domain.model.Message as MederiMessage
import xyz.mederi.domain.model.MessagePart as MederiMessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.MessageStatus
import kotlin.time.Instant as KoogInstant
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Mederi Message 与 Koog Message 之间的双向转换器。
 */
object KoogMessageMapper {

    /**
     * 将 Mederi 消息列表转换为 Koog Prompt 用的 Message 列表。
     */
    fun toKoogMessages(messages: List<MederiMessage>): List<KoogMessage> =
        messages.map { toKoogMessage(it) }

    /**
     * 单条 Mederi Message -> Koog Message。
     */
    fun toKoogMessage(message: MederiMessage): KoogMessage {
        val metaInfo = RequestMetaInfo(timestamp = parseTimestamp(message.createdAt).toKoog())
        val koogParts = message.parts.mapNotNull { toKoogPart(it) }

        return when (message.role) {
            MessageRole.SYSTEM -> KoogMessage.System(
                parts = koogParts.filterIsInstance<KoogMessagePart.Text>(),
                metaInfo = metaInfo,
                id = message.id
            )
            MessageRole.USER -> KoogMessage.User(
                parts = koogParts.filterIsInstance<KoogMessagePart.RequestPart>(),
                metaInfo = metaInfo,
                id = message.id
            )
            MessageRole.ASSISTANT -> {
                val responseParts = koogParts.filterIsInstance<KoogMessagePart.ResponsePart>()
                    .filterNot { it is KoogMessagePart.Attachment }
                    .toMutableList()
                if (responseParts.none { it is KoogMessagePart.Text }) {
                    val images = message.parts.filterIsInstance<MederiMessagePart.Image>()
                    if (images.isNotEmpty()) {
                        val names = images.map { it.url.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image" } }
                        responseParts.add(KoogMessagePart.Text("[Image: ${names.joinToString(", ")}]"))
                    }
                }
                KoogMessage.Assistant(
                    parts = responseParts,
                    metaInfo = ResponseMetaInfo.create(
                        clock = KoogClock.System,
                        totalTokensCount = message.totalTokens,
                        inputTokensCount = message.inputTokens,
                        outputTokensCount = message.outputTokens,
                        metadata = message.cachedTokens?.let {
                            buildJsonObject { put("cachedTokens", it) }
                        }
                    ),
                    finishReason = message.finishReason,
                    id = message.id
                )
            }
            // 压缩标记发给 AI 时作为普通 Assistant 消息（TLDR 总结），
            // 让模型知道此前上下文被压缩过以及压缩了什么
            MessageRole.SUMMARY -> KoogMessage.Assistant(
                parts = koogParts.filterIsInstance<KoogMessagePart.ResponsePart>(),
                metaInfo = ResponseMetaInfo.create(clock = KoogClock.System),
                finishReason = null,
                id = message.id
            )
        }
    }

    /**
     * Koog Assistant Message -> Mederi Message（完整响应）。
     */
    fun fromKoogMessage(sessionId: String, koogMessage: KoogMessage.Assistant): MederiMessage {
        val parts = koogMessage.parts.mapNotNull { fromKoogPart(it) }
        val metaInfo = koogMessage.metaInfo
        return MederiMessage(
            id = koogMessage.id ?: "msg_${UUID.randomUUID().toString().take(8)}",
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            parts = parts,
            status = MessageStatus.COMPLETED,
            createdAt = sanitizeTimestamp(metaInfo.timestamp.toJava()),
            finishReason = koogMessage.finishReason,
            totalTokens = metaInfo.totalTokensCount,
            inputTokens = metaInfo.inputTokensCount,
            outputTokens = metaInfo.outputTokensCount,
            cachedTokens = readCachedTokens(metaInfo.metadata)
        )
    }

    /**
     * 从 ResponseMetaInfo.metadata 中读取缓存命中 token 数。
     * 支持 Mederi 自定义 key `cachedTokens`，以及 OpenAI 风格的 `usage.prompt_tokens_details.cached_tokens`。
     */
    private fun readCachedTokens(metadata: JsonObject?): Int? {
        if (metadata == null) return null
        // Mederi 自定义顶层 key
        metadata["cachedTokens"]?.jsonPrimitive?.intOrNull?.let { return it }
        // OpenAI usage 风格
        metadata["usage"]?.jsonObject?.get("prompt_tokens_details")?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull?.let { return it }
        return null
    }

    /**
     * Koog User Message -> Mederi Message。
     *
     * 用于 ChatHistoryProvider.store() 持久化用户消息（含工具结果）。
     */
    fun fromKoogUserMessage(sessionId: String, koogMessage: KoogMessage.User): MederiMessage {
        val parts = koogMessage.parts.mapNotNull { part ->
            fromKoogPart(part)
        }
        val metaInfo = koogMessage.metaInfo
        return MederiMessage(
            id = koogMessage.id ?: "msg_${UUID.randomUUID().toString().take(8)}",
            sessionId = sessionId,
            role = MessageRole.USER,
            parts = parts,
            status = MessageStatus.COMPLETED,
            createdAt = formatTimestamp(
                metaInfo.timestamp.toJava()
            )
        )
    }

    /**
     * 用户输入构造 Mederi Message。
     */
    fun createUserMessage(sessionId: String, parts: List<MederiMessagePart>): MederiMessage {
        return MederiMessage(
            id = "msg_${UUID.randomUUID().toString().take(8)}",
            sessionId = sessionId,
            role = MessageRole.USER,
            parts = parts,
            status = MessageStatus.COMPLETED,
            createdAt = formatTimestamp(Instant.now())
        )
    }

    private fun toKoogPart(part: MederiMessagePart): KoogMessagePart? = when (part) {
        is MederiMessagePart.Text -> KoogMessagePart.Text(part.text)
        is MederiMessagePart.Image -> KoogMessagePart.Attachment(
            source = AttachmentSource.Image(
                content = AttachmentContent.URL(part.url),
                format = deriveImageFormat(part.url, part.mimeType),
                mimeType = part.mimeType ?: "image/"
            )
        )
        is MederiMessagePart.File -> null
        is MederiMessagePart.ToolCall -> KoogMessagePart.Tool.Call(
            id = part.id,
            tool = part.tool,
            args = part.args
        )
        is MederiMessagePart.ToolResult -> KoogMessagePart.Tool.Result(
            id = part.id,
            tool = part.tool,
            output = part.output,
            isError = part.isError
        )
        is MederiMessagePart.Reasoning -> KoogMessagePart.Reasoning(
            content = part.content,
            summary = part.summary,
            encrypted = part.encrypted,
            id = part.id
        )
    }

    private fun fromKoogPart(part: KoogMessagePart): MederiMessagePart? = when (part) {
        is KoogMessagePart.Text -> MederiMessagePart.Text(part.text)
        is KoogMessagePart.Tool.Call -> MederiMessagePart.ToolCall(
            id = part.id,
            tool = part.tool,
            args = part.args
        )
        is KoogMessagePart.Tool.Result -> MederiMessagePart.ToolResult(
            id = part.id,
            tool = part.tool,
            output = part.output,
            isError = part.isError
        )
        is KoogMessagePart.Reasoning -> MederiMessagePart.Reasoning(
            content = part.content,
            summary = part.summary,
            encrypted = part.encrypted,
            id = part.id
        )
        is KoogMessagePart.Attachment -> {
            val src = part.source
            when (src) {
                is AttachmentSource.Image -> {
                    val url = (src.content as? AttachmentContent.URL)?.url
                    if (url != null) {
                        MederiMessagePart.Image(url = url, mimeType = src.mimeType.takeIf { it != "image/" })
                    } else null
                }
                else -> null
            }
        }
        else -> null
    }

    private fun parseTimestamp(iso: String): Instant = Instant.parse(iso)
    private fun formatTimestamp(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

    /**
     * Koog 无 metaInfo 时回退到 `ResponseMetaInfo/RequestMetaInfo.Empty`（timestamp = Instant.DISTANT_PAST，
     * 即 -100001 年）——若直接落库 createdAt 会变成哨兵日期，消息在 UI 按时间排序时沉底错乱。
     * 检测到遥远远于当前时间的纪元值（早于公元 1 年）一律用当前时间兜底。
     */
    private fun sanitizeTimestamp(instant: Instant): String {
        val usable = if (instant.isAfter(Instant.parse("0001-01-01T00:00:00Z"))) instant else Instant.now()
        return formatTimestamp(usable)
    }

    private fun Instant.toKoog(): KoogInstant = KoogInstant.parse(this.toString())
    private fun KoogInstant.toJava(): Instant = Instant.parse(this.toString())

    private fun deriveImageFormat(url: String, mimeType: String?): String {
        val ext = url.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext.length <= 4) return ext
        mimeType?.substringAfter('/')?.takeIf { it.isNotEmpty() }?.let { return it }
        return "png"
    }
}
