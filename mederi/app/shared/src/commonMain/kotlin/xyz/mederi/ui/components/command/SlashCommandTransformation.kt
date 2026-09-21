package xyz.mederi.ui.components.command

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * 快捷命令高亮与占位提示变换：仅影响显示层（底层文本/光标/IME 不变）。
 * 1. 当且仅当输入单个 "/" 时，追加显示半透明提示文案 " 输入搜索..."（与现代 IDE 体验一致）；
 * 2. 对开头的命令前缀（例如 /spec, /plan, /skill <name> 等）给予高亮着色 + 半粗体，提供可见的确认反馈。
 */
internal class SlashCommandTransformation(
    private val highlightColor: Color,
    private val hintColor: Color,
) : VisualTransformation {
    private val commandRegex = Regex("""(?:^|(?<=\s))(/skill\s+\S+|/[a-zA-Z0-9_\-\u4e00-\u9fa5]+)""", RegexOption.IGNORE_CASE)

    override fun filter(text: AnnotatedString): TransformedText {
        if (text.text == "/") {
            val annotated = buildAnnotatedString {
                append("/")
                addStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.SemiBold), 0, 1)
                pushStyle(SpanStyle(color = hintColor))
                append(" 输入搜索...")
                pop()
            }
            val offsetMapping = object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = offset
                override fun transformedToOriginal(offset: Int): Int = offset.coerceAtMost(1)
            }
            return TransformedText(annotated, offsetMapping)
        }

        val annotated = buildAnnotatedString {
            append(text.text)
            for (match in commandRegex.findAll(text.text)) {
                val start = match.range.first
                // 若前面有反斜杠 \ 转义，则不作为命令高亮
                if (start > 0 && text.text[start - 1] == '\\') {
                    continue
                }
                addStyle(
                    style = SpanStyle(color = highlightColor, fontWeight = FontWeight.SemiBold),
                    start = start,
                    end = match.range.last + 1
                )
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
