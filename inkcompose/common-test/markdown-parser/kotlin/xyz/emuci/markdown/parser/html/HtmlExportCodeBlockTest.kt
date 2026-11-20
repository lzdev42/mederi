package xyz.emuci.markdown.parser.html

import xyz.emuci.inkcompose.HtmlExportOptions
import xyz.emuci.inkcompose.HtmlExportPipeline
import xyz.emuci.inkcompose.HtmlExportTheme
import xyz.emuci.inkcompose.MarkdownExporter
import xyz.emuci.markdown.parser.MarkdownParser
import xyz.emuci.markdown.parser.flavour.ExtendedFlavour
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ST2 代码块完整实现验收：title 栏、行号列、高亮行、tok-* 语法高亮与主题色注入。
 */
class HtmlExportCodeBlockTest {

    private fun parse(markdown: String) = MarkdownParser(ExtendedFlavour).parse(markdown)

    private fun render(markdown: String, hooks: HtmlExportHooks = HtmlExportPipeline.defaultHooks()): String =
        HtmlRenderer(hooks = hooks).render(parse(markdown))

    // ─────────────── title 栏 ───────────────

    @Test
    fun fencedCodeBlockRendersTitleBarAndLanguageLabel() {
        val html = render("```kotlin {title=\"main.kt\"}\nfun main() {}\n```")
        assertContains(html, "code-block-title")
        assertContains(html, "main.kt")
        assertContains(html, "code-lang")
        assertContains(html, "kotlin")
        assertContains(html, "code-title-text")
        // 容器与 pre 类名
        assertContains(html, "code-block")
        assertContains(html, "code-block-pre")
    }

    @Test
    fun fencedCodeBlockWithoutTitleStillShowsLanguageLabel() {
        val html = render("```python\nprint(1)\n```")
        assertContains(html, "code-block-title")
        assertContains(html, "code-lang")
        assertContains(html, "python")
        assertFalse(html.contains("code-title-text"))
    }

    // ─────────────── 行号列 ───────────────

    @Test
    fun lineNumbersRenderedWithStartlineOffset() {
        val html = render("```kotlin {linenos=true startline=5}\nline-a\nline-b\n```")
        assertContains(html, "line-number")
        assertContains(html, ">5</span>")
        assertContains(html, ">6</span>")
        assertFalse(html.contains(">4</span>"))
        assertFalse(html.contains(">7</span>"))
        // data-* 属性保留（既有断言兼容）
        assertContains(html, "data-linenums=\"true\"")
        assertContains(html, "data-startline=\"5\"")
    }

    @Test
    fun lineNumbersDefaultStartAtOne() {
        val html = render("```kotlin\nline-a\nline-b\n```")
        assertContains(html, ">1</span>")
        assertContains(html, ">2</span>")
    }

    @Test
    fun lineNumbersSuppressedWhenDisabled() {
        val html = render("```kotlin {linenums=false}\nline-a\n```")
        assertFalse(html.contains("line-number"))
        assertContains(html, "data-linenums=\"false\"")
    }

    // ─────────────── 高亮行 ───────────────

    @Test
    fun highlightedLinesGetHlClass() {
        val html = render("```kotlin {highlight=\"1-2\"}\nline-a\nline-b\nline-c\n```")
        assertContains(html, "code-line hl")
        // 第 1/2 行高亮，第 3 行不高亮：hl 行数应为 2
        assertEquals(2, Regex("code-line hl").findAll(html).count())
        assertContains(html, "data-hl-lines=\"1-2\"")
    }

    @Test
    fun highlightedLinesSupportDisjointRanges() {
        val html = render("```kotlin {hl_lines=\"1 3\"}\nline-a\nline-b\nline-c\n```")
        assertEquals(2, Regex("code-line hl").findAll(html).count())
    }

    // ─────────────── 语法高亮 token ───────────────

    @Test
    fun syntaxHighlightTokensWrappedInTokSpans() {
        // kotlin 代码：IncrementalHighlighter 对 fun 产出 KEYWORD token
        val html = render("```kotlin\nfun main() {}\n```")
        assertContains(html, "tok-keyword")
        assertContains(html, ">fun</span>")
    }

    @Test
    fun tokenTextIsHtmlEscaped() {
        val hooks = HtmlExportHooks(
            code = CodeHighlightProvider { code, _ ->
                listOf(HtmlHighlightToken("string", code))
            },
        )
        val html = HtmlRenderer(hooks = hooks).render(parse("```html\n<a href=\"x\">y</a>\n```"))
        assertContains(html, "tok-string")
        assertFalse(html.contains("tok-<"))
        assertContains(html, "&lt;a href=")
    }

    // ─────────────── 降级兜底 ───────────────

    @Test
    fun hookFallbackToPlainEscapedText() {
        val html = render("```kotlin\nfun <main> {}\n```", hooks = HtmlExportHooks(code = null))
        assertFalse(html.contains("tok-"))
        assertContains(html, "fun &lt;main&gt; {}")
        // 结构仍完整：行 + 行号
        assertContains(html, "code-line")
        assertContains(html, "line-number")
    }

    @Test
    fun hookTokenMismatchFallsBackToPlainText() {
        // token 串联与原文不一致 → 必须整块降级，不得错位输出
        val hooks = HtmlExportHooks(
            code = CodeHighlightProvider { _, _ ->
                listOf(HtmlHighlightToken("keyword", "fun ma"))
            },
        )
        val html = HtmlRenderer(hooks = hooks).render(parse("```kotlin\nfun main() {}\n```"))
        assertFalse(html.contains("tok-"))
        assertContains(html, "fun main() {}")
    }

    @Test
    fun hookThrowingProviderFallsBackToPlainText() {
        val hooks = HtmlExportHooks(
            code = CodeHighlightProvider { _, _ -> throw IllegalStateException("boom") },
        )
        val html = HtmlRenderer(hooks = hooks).render(parse("```kotlin\nfun main() {}\n```"))
        assertFalse(html.contains("tok-"))
        assertContains(html, "fun main() {}")
    }

    // ─────────────── 缩进代码块 ───────────────

    @Test
    fun indentedCodeBlockGetsContainerStyling() {
        val html = render("    code line 1\n    code line 2")
        assertContains(html, "code-block")
        assertContains(html, "code-block-pre")
        assertContains(html, "code-line")
        // 缩进代码块无 title 栏、无行号
        assertFalse(html.contains("code-block-title"))
        assertFalse(html.contains("line-number"))
    }

    @Test
    fun indentedCodeBlockSupportsHighlight() {
        val hooks = HtmlExportHooks(
            code = CodeHighlightProvider { code, _ ->
                listOf(HtmlHighlightToken("plain", code))
            },
        )
        val html = HtmlRenderer(hooks = hooks).render(parse("    code line 1\n    code line 2"))
        assertContains(html, "tok-plain")
    }

    // ─────────────── 主题色注入 ───────────────

    @Test
    fun customCodeThemeColorsInjected() {
        val document = parse("```kotlin\nfun main() {}\n```")
        runTest {
        val html = MarkdownExporter.toHtml(
            document,
            HtmlExportOptions(codeTheme = xyz.emuci.syntax.theme.GithubLightTheme),
        )
        val styleStart = html.indexOf("<style>")
        val styleEnd = html.indexOf("</style>")
        assertTrue(styleStart != -1 && styleEnd > styleStart)
        val style = html.substring(styleStart, styleEnd)
        // GithubLightTheme.KEYWORD = 0xFFCF222E
        assertContains(style, ".tok-keyword { color: #cf222e; font-weight: bold; }")
        // GithubLightTheme.COMMENT = 0xFF6E7781，斜体
        assertContains(style, ".tok-comment { color: #6e7781; font-style: italic; }")
        // 14 类规则齐全
        for (category in listOf(
            "keyword", "string", "comment", "number", "type", "function",
            "annotation", "builtin", "constant", "operator", "punctuation",
            "identifier", "plain",
        )) {
            assertContains(style, ".tok-$category {")
        }
        }
    }

    @Test
    fun darkThemeUsesOneDarkProColorsByDefault() {
        val document = parse("```kotlin\nfun main() {}\n```")
        runTest {
        val html = MarkdownExporter.toHtml(document, HtmlExportOptions(theme = HtmlExportTheme.DARK))
        val style = html.substring(html.indexOf("<style>"), html.indexOf("</style>"))
        // OneDarkProTheme.KEYWORD = 0xFFC678DD
        assertContains(style, ".tok-keyword { color: #c678dd; font-weight: bold; }")
        }
    }

    @Test
    fun lightThemeDefaultsToGithubLightColors() {
        val document = parse("```kotlin\nfun main() {}\n```")
        runTest {
        val html = MarkdownExporter.toHtml(document, HtmlExportOptions())
        val style = html.substring(html.indexOf("<style>"), html.indexOf("</style>"))
        assertContains(style, ".tok-keyword { color: #cf222e; font-weight: bold; }")
        }
    }

    // ─────────────── 样式表：代码块视觉 token ───────────────

    @Test
    fun codeBlockStylesheetRulesPresent() {
        val css = HtmlStyleSheet.generate(
            dark = false,
            codeColors = mapOf("keyword" to "#cf222e"),
        )
        // 容器：圆角 8px + 边框 + margin
        assertContains(css, ".code-block {")
        assertContains(css, "border-radius: 8px")
        assertContains(css, "border: 1px solid var(--md-border-color)")
        assertContains(css, "margin: 16px 0")
        // title 栏：背景 #ebedf0、padding 6px 12px、字号 12px
        assertContains(css, ".code-block-title {")
        assertContains(css, "background: var(--md-code-title-bg)")
        assertContains(css, "--md-code-title-bg: #ebedf0")
        assertContains(css, "padding: 6px 12px")
        assertContains(css, "font-size: 12px")
        // pre：padding 10px、行高 20px
        assertContains(css, ".code-block-pre {")
        assertContains(css, "padding: 10px")
        assertContains(css, "line-height: 20px")
        // 行号列
        assertContains(css, ".line-number {")
        assertContains(css, "min-width: 2.5em")
        assertContains(css, "text-align: right")
        assertContains(css, "user-select: none")
        // 高亮行背景
        assertContains(css, ".code-line.hl { background: var(--md-code-hl-bg); }")
        assertContains(css, "--md-code-hl-bg: rgba(0, 0, 0, 0.07)")
        // tok 颜色注入与缺省
        assertContains(css, ".tok-keyword { color: #cf222e; font-weight: bold; }")
        assertContains(css, ".tok-plain { color: var(--md-text-color); }")
    }

    @Test
    fun codeBlockDarkThemeVariablesPresent() {
        val css = HtmlStyleSheet.generate(dark = true)
        assertContains(css, "--md-code-title-bg: #21262d")
        assertContains(css, "--md-code-hl-bg: rgba(255, 255, 255, 0.13)")
        assertContains(css, "--md-line-number-color: #8b949e")
    }

    // ─────────────── 结构完整性（兼容既有 data-* 断言） ───────────────

    @Test
    fun fullPageRendersCompleteCodeBlockStructure() {
        // startline=5 时首行显示行号为 5，highlight 按绝对行号命中首行
        runTest {
            val html = MarkdownExporter.toHtml("```kotlin {title=\"main.kt\" highlight=\"5\" linenos=true startline=5}\nfun main() {}\n```")
            assertContains(html, "code-block-title")
            assertContains(html, "code-lang")
            assertContains(html, "main.kt")
            assertContains(html, "line-number")
            assertContains(html, ">5</span>")
            assertContains(html, "code-line hl")
            assertContains(html, "tok-keyword")
            assertContains(html, "data-hl-lines=\"5\"")
            assertContains(html, "data-startline=\"5\"")
        }
    }
}
