package xyz.emuci.inkcompose

import xyz.emuci.latex.parser.LatexParser
import xyz.emuci.latex.parser.visitor.MathMLVisitor
import xyz.emuci.markdown.parser.html.CodeHighlightProvider
import xyz.emuci.markdown.parser.html.DiagramBootstrapProvider
import xyz.emuci.markdown.parser.html.HtmlExportHooks
import xyz.emuci.markdown.parser.html.HtmlHighlightToken
import xyz.emuci.markdown.parser.html.MathHtmlProvider
import xyz.emuci.syntax.ast.TokenType
import xyz.emuci.syntax.stream.IncrementalHighlighter

/**
 * HTML 导出渲染增强管线：组装默认的 math / code / diagram hooks。
 *
 * markdown-parser 域保持零依赖，真实的 LaTeX 与语法高亮实现通过本管线注入。
 */
object HtmlExportPipeline {

    /**
     * 默认渲染增强 hooks。
     *
     * - math：LatexParser + MathMLVisitor 输出 MathML，解析失败降级为纯文本
     * - code：IncrementalHighlighter 词法高亮，token 覆盖不完整时降级
     * - diagram：默认返回 null（不接管）——mermaid bootstrap 需要库全文 + 明暗主题信息，
     *   而 [DiagramBootstrapProvider] 签名只有 diagramTypes，默认由
     *   MarkdownExporter 内置路径提供（customScript → hook → 打包库）。
     *   宿主自定义 bootstrap 时通过 HtmlExportOptions.hooks.diagram 注入（全权接管，
     *   返回值需自含 initialize + run + ready flag 语义）
     */
    fun defaultHooks(): HtmlExportHooks = HtmlExportHooks(
        math = MathHtmlProvider { latex, displayMode ->
            try {
                MathMLVisitor.convert(LatexParser().parse(latex), displayMode)
            } catch (_: Throwable) {
                null
            }
        },
        code = CodeHighlightProvider { code, language ->
            try {
                val tokens = IncrementalHighlighter().update(code, language).tokens
                val mapped = tokens.map { HtmlHighlightToken(categoryOf(it.type), it.text) }
                if (mapped.sumOf { it.text.length } == code.length) mapped else null
            } catch (_: Throwable) {
                null
            }
        },
        diagram = DiagramBootstrapProvider { null },
    )

    /**
     * 将语法高亮 TokenType 映射为中立的 category 字符串。
     * 覆盖全部 14 类 TokenType（VARIABLE 归入 identifier，DECORATOR 归入 annotation），
     * 与 HtmlStyleSheet 的 tok-* 规则集一一对应。
     */
    private fun categoryOf(type: TokenType): String = when (type) {
        TokenType.KEYWORD -> "keyword"
        TokenType.STRING -> "string"
        TokenType.NUMBER -> "number"
        TokenType.COMMENT -> "comment"
        TokenType.TYPE -> "type"
        TokenType.FUNCTION -> "function"
        TokenType.ANNOTATION, TokenType.DECORATOR -> "annotation"
        TokenType.BUILTIN -> "builtin"
        TokenType.CONSTANT -> "constant"
        TokenType.OPERATOR -> "operator"
        TokenType.PUNCTUATION -> "punctuation"
        TokenType.IDENTIFIER, TokenType.VARIABLE -> "identifier"
        TokenType.PLAIN -> "plain"
    }
}
