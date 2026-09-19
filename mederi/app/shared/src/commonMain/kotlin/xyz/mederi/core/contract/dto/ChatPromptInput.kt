package xyz.mederi.core.contract.dto

import kotlinx.serialization.Serializable
import xyz.mederi.core.contract.models.AgentOption
import xyz.mederi.core.contract.models.ModelOption

@Serializable
data class ChatPromptInput(
    val text: String,
    val model: ModelOption?,
    val agent: AgentOption?,
    val thinkingLevel: String?,
    val attachments: List<FileAttachment> = emptyList(),
    /**
     * 本次发送选定的供应商 API Key 的 ID（UI 显示为供应商配置的 key 名）。
     * null = 用该供应商默认 key。唯一真理源 = 每次发送携带的 apiKeyId。
     */
    val apiKeyId: String? = null,
)

@Serializable
data class FileAttachment(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is FileAttachment &&
        name == other.name && mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = 31 * name.hashCode() + 31 * mimeType.hashCode() + bytes.contentHashCode()
}

@Serializable
data class RollbackMessageInput(
    val messageId: String,
)
