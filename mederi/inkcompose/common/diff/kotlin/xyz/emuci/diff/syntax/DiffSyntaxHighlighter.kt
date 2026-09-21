package xyz.emuci.diff.syntax

import androidx.compose.ui.text.AnnotatedString
import xyz.emuci.syntax.lexer.LanguageRegistry
import xyz.emuci.syntax.renderer.buildHighlightedString
import xyz.emuci.syntax.theme.CodeTheme

/**
 * 差异代码行的高亮桥接器。
 * 复用 inkcompose 的 syntax-parser 和 syntax-render 设施。
 */
object DiffSyntaxHighlighter {

    /**
     * 对单行代码进行语法高亮，生成 AnnotatedString。
     */
    fun highlightLine(
        content: String,
        language: String?,
        theme: CodeTheme,
    ): AnnotatedString {
        if (content.isEmpty() || language.isNullOrBlank()) {
            return AnnotatedString(content)
        }

        val lexer = LanguageRegistry.get(language) ?: return AnnotatedString(content)

        return try {
            val tokens = lexer.tokenize(content)
            buildHighlightedString(tokens, theme)
        } catch (_: Exception) {
            AnnotatedString(content)
        }
    }
}
