package xyz.emuci.latex.parser.tokenizer

import xyz.emuci.latex.parser.model.SourceRange

/**
 * LaTeX 词法单元
 *
 * 每个 token 携带 [range] 记录其在原始输入字符串中的位置
 */
sealed class LatexToken {
    abstract val range: SourceRange

    /** 创建带有新 SourceRange 的副本（消除 shiftToken 中的 when 分派） */
    abstract fun withRange(newRange: SourceRange): LatexToken

    data class Text(val content: String, override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class Command(val name: String, override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class BeginEnvironment(val name: String, override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class EndEnvironment(val name: String, override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class LeftBrace(override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class RightBrace(override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class LeftBracket(override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class RightBracket(override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class Superscript(override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class Prime(override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class Subscript(override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class Ampersand(override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class NewLine(override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class Whitespace(val content: String, override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    /** `$` 数学模式切换符（单个 `$` 或 `$$`，由 count 区分） */
    data class MathShift(val count: Int, override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
    data class EOF(override val range: SourceRange = SourceRange.EMPTY) : LatexToken() {
        override fun withRange(newRange: SourceRange) = copy(range = newRange)
    }
}
