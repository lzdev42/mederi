package xyz.mederi.util

/**
 * 解析出的独立文档产物数据。
 */
data class ParsedArtifact(
    val identifier: String,
    val title: String,
    val type: String = "text/markdown",
    val content: String,
    val isCompleted: Boolean,
    val lineCount: Int,
    val charCount: Int,
)

/**
 * 消息文本提取结果：前置导言 + 独立文档 + 后置文本。
 */
data class ArtifactParseResult(
    val preamble: String,
    val artifact: ParsedArtifact?,
    val postscript: String = "",
)

object ArtifactParser {

    private val ARTIFACT_OPEN_REGEX = Regex("""<artifact(?:\s+([^>]*?))?>""", RegexOption.IGNORE_CASE)
    private const val ARTIFACT_CLOSE_TAG = "</artifact>"

    private val TITLE_ATTR_REGEX = Regex("""title=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val ID_ATTR_REGEX = Regex("""(?:identifier|id)=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val TYPE_ATTR_REGEX = Regex("""type=["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    /**
     * 解析文本中的 Artifact 结构。
     *
     * @param text 输入文本（可能是流式增量文本，也可能是完整文本）
     * @param isStreaming 是否处于流式生成状态
     */
    fun parse(text: String, isStreaming: Boolean = false): ArtifactParseResult {
        if (text.isBlank()) {
            return ArtifactParseResult(preamble = text, artifact = null)
        }

        // 1. 优先尝试显式 <artifact> 标签匹配
        val match = ARTIFACT_OPEN_REGEX.find(text)
        if (match != null) {
            val preamble = text.substring(0, match.range.first).trimEnd()
            val attrsString = match.groupValues.getOrNull(1) ?: ""

            val titleAttr = TITLE_ATTR_REGEX.find(attrsString)?.groupValues?.getOrNull(1)?.trim()
            val idAttr = ID_ATTR_REGEX.find(attrsString)?.groupValues?.getOrNull(1)?.trim()
            val typeAttr = TYPE_ATTR_REGEX.find(attrsString)?.groupValues?.getOrNull(1)?.trim() ?: "text/markdown"

            val bodyStart = match.range.last + 1
            val closeIdx = text.indexOf(ARTIFACT_CLOSE_TAG, startIndex = bodyStart, ignoreCase = true)

            val (rawBody, isCompleted, postscript) = if (closeIdx != -1) {
                val body = text.substring(bodyStart, closeIdx)
                val post = text.substring(closeIdx + ARTIFACT_CLOSE_TAG.length).trimStart()
                Triple(body, true, post)
            } else {
                val body = text.substring(bodyStart)
                Triple(body, !isStreaming, "")
            }

            val cleanContent = rawBody.trimIndent().trim('\r', '\n')
            val effectiveTitle = titleAttr?.takeIf { it.isNotBlank() }
                ?: extractFirstHeading(cleanContent)
                ?: "Markdown 文档"

            val lineCount = if (cleanContent.isBlank()) 0 else cleanContent.lines().size
            val charCount = cleanContent.length

            val artifact = ParsedArtifact(
                identifier = idAttr?.takeIf { it.isNotBlank() } ?: "doc_${effectiveTitle.hashCode().toUInt()}",
                title = effectiveTitle,
                type = typeAttr,
                content = cleanContent,
                isCompleted = isCompleted,
                lineCount = lineCount,
                charCount = charCount,
            )

            return ArtifactParseResult(
                preamble = preamble,
                artifact = artifact,
                postscript = postscript,
            )
        }

        return ArtifactParseResult(preamble = text, artifact = null)
    }

    private fun extractFirstHeading(content: String): String? {
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("# ") && !trimmed.startsWith("##")) {
                return trimmed.removePrefix("#").trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }
}