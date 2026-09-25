package xyz.mederi.util

import xyz.mederi.core.contract.models.PastedTextAttachment

object PromptComposer {
    const val LARGE_TEXT_MIN_CHARS = 800
    const val LARGE_TEXT_MIN_LINES = 15

    /**
     * 判断是否为大段文本（用于剪贴板粘贴拦截与卡片化）。
     */
    fun isLargeText(text: String): Boolean {
        if (text.length >= LARGE_TEXT_MIN_CHARS) return true
        val lineCount = text.count { it == '\n' } + 1
        return lineCount >= LARGE_TEXT_MIN_LINES
    }

    /**
     * 组装 Prompt：用户主指令在上，大段文本以带编号的 XML 标签追加在后。
     * 服务端 TurnExecutor 会在最后追加 metaNote，保证 metaNote 永远在最底部。
     */
    fun compose(instruction: String, pastedTexts: List<PastedTextAttachment>): String {
        val trimmedInstruction = instruction.trim()
        if (pastedTexts.isEmpty()) return trimmedInstruction

        val builder = StringBuilder()
        if (trimmedInstruction.isNotEmpty()) {
            builder.append(trimmedInstruction)
            builder.append("\n\n")
        }
        builder.append("----------------------------------------\n")
        builder.append("The following are text sections pasted by the user for context:\n\n")
        pastedTexts.forEachIndexed { i, item ->
            val idx = i + 1
            builder.append("<pasted_text index=\"$idx\" lines=\"${item.lineCount}\" chars=\"${item.charCount}\">\n")
            builder.append(item.text.trim())
            builder.append("\n</pasted_text>\n\n")
        }
        return builder.toString().trim()
    }

    data class ParsedPrompt(
        val instruction: String,
        val pastedTexts: List<PastedTextAttachment>
    )

    private val PASTED_TAG_REGEX = Regex(
        """<pasted_text(?:\s+index="(\d+)")?(?:\s+lines="(\d+)")?(?:\s+chars="(\d+)")?>\n?([\s\S]*?)\n?</pasted_text>""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 解析完整消息文本，分离出主指令与底部粘贴文本列表。
     */
    fun parse(fullText: String): ParsedPrompt {
        val matches = PASTED_TAG_REGEX.findAll(fullText).toList()
        if (matches.isEmpty()) {
            return ParsedPrompt(instruction = fullText, pastedTexts = emptyList())
        }

        val firstMatchStart = matches.first().range.first
        // 取标签之前的内容作为主指令（去掉分隔线和提示说明）
        var rawInstruction = fullText.substring(0, firstMatchStart).trim()
        val separatorIndex = rawInstruction.indexOf("----------------------------------------")
        if (separatorIndex >= 0) {
            rawInstruction = rawInstruction.substring(0, separatorIndex).trim()
        }

        val parsedAttachments = matches.mapIndexed { i, match ->
            val indexStr = match.groups[1]?.value
            val content = match.groups[4]?.value.orEmpty().trim()
            val lines = content.lines().size
            val chars = content.length
            val index = indexStr?.toIntOrNull() ?: (i + 1)
            PastedTextAttachment(
                id = "pasted_$index",
                index = index,
                text = content,
                lineCount = lines,
                charCount = chars
            )
        }

        return ParsedPrompt(
            instruction = rawInstruction,
            pastedTexts = parsedAttachments
        )
    }
}
