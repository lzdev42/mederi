package xyz.emuci.markdown.parser.html

import xyz.emuci.markdown.parser.MarkdownParser
import xyz.emuci.markdown.parser.flavour.ExtendedFlavour
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class HtmlExportStyleTest {

    // ─────────────── theme tokens ───────────────

    @Test
    fun lightThemeTokensMatchMarkdownThemeDefaults() {
        val css = HtmlStyleSheet.generate(dark = false)
        assertContains(css, "--md-body-size: 13px")
        assertContains(css, "--md-body-line-height: 21.5px")
        assertContains(css, "--md-link-color: #0969da")
        assertContains(css, "--md-text-color: #1f2328")
        assertContains(css, "--md-bg: #ffffff")
        assertContains(css, "--md-border-color: #d0d7de")
        assertContains(css, "--md-code-bg: #f6f8fa")
        assertContains(css, "--md-inline-code-bg: #eff1f3")
        assertContains(css, "--md-mark-bg: #fff3b0")
        assertContains(css, "--md-task-checked: #1a7f37")
        assertContains(css, "--md-task-unchecked: #d0d7de")
        // blockquote: 3px solid border (blockQuoteBorderWidth = 3.dp)
        assertContains(css, "border-left: var(--md-blockquote-border) solid var(--md-border-color)")
        assertContains(css, "--md-blockquote-border: 3px")
        assertContains(css, "color: var(--md-blockquote-text)")
        assertContains(css, "--md-blockquote-text: #656d76")
        // table header background (tableHeaderBackground = 0xFFF6F8FA)
        assertContains(css, "table th { background-color: var(--md-code-bg)")
        // heading divider (showHeadingDividers = true)
        assertContains(css, "h1, h2 {")
        assertContains(css, "border-bottom: 1px solid var(--md-border-color)")
    }

    @Test
    fun darkThemeVariablesInjected() {
        val css = HtmlStyleSheet.generate(dark = true)
        assertContains(css, "html[data-theme=\"dark\"]")
        // MarkdownTheme.dark() values
        assertContains(css, "--md-bg: #0d1117")
        assertContains(css, "--md-text-color: #e6edf3")
        assertContains(css, "--md-link-color: #4493f8")
        assertContains(css, "--md-border-color: #3d444d")
        assertContains(css, "--md-code-bg: #161b22")
        assertContains(css, "--md-inline-code-bg: #343942")
        assertContains(css, "--md-mark-bg: #5c4b00")
        assertContains(css, "--md-task-checked: #3fb950")
        // dark theme activation stub (renderer sets data-theme attr on <html>)
        assertContains(css, "data-theme: dark")
    }

    @Test
    fun customCssAppended() {
        val base = HtmlStyleSheet.generate(dark = false)
        val css = HtmlStyleSheet.generate(dark = false, customCss = "/* X-CUSTOM */")
        assertContains(css, "/* X-CUSTOM */")
        // custom css must come after the main rules
        val mainEnd = css.lastIndexOf("}")
        val customStart = css.indexOf("/* X-CUSTOM */")
        assertTrue(customStart > mainEnd, "customCss should be appended after main rules")
        assertTrue(base.indexOf("/* X-CUSTOM */") == -1, "base css must not contain custom css")
    }

    // ─────────────── typography ───────────────

    @Test
    fun headingSizesGradientPresent() {
        val css = HtmlStyleSheet.generate(dark = false)
        assertContains(css, "h1 { font-size: 17px; font-weight: 700; line-height: 23px; }")
        assertContains(css, "h2 { font-size: 15px; font-weight: 700; line-height: 20px; }")
        assertContains(css, "h3 { font-size: 14px; font-weight: 600; line-height: 18px; }")
        assertContains(css, "h4 { font-size: 13.5px; font-weight: 600; line-height: 18px; }")
        assertContains(css, "h5 { font-size: 13px; font-weight: 600; line-height: 18px; }")
        assertContains(css, "h6 { font-size: 12.5px; font-weight: 500; line-height: 17px; }")
    }

    @Test
    fun tableAndKbdStylesPresent() {
        val css = HtmlStyleSheet.generate(dark = false)
        assertContains(css, "table th { background-color: var(--md-code-bg)")
        assertContains(css, "table th, table td {")
        assertContains(css, "padding: 6px 13px")
        assertContains(css, "table tr:nth-child(2n) { background: var(--md-code-bg); }")
        assertContains(css, "text-align: inherit")
        assertContains(css, "kbd {")
        assertContains(css, "--md-kbd-bg: #eff1f3")
        assertContains(css, "font-size: 11.5px")
    }

    // ─────────────── inline / footnote / figure / misc ───────────────

    @Test
    fun inlineCodeAndMarkStylesPresent() {
        val css = HtmlStyleSheet.generate(dark = false)
        assertContains(css, ":not(pre) > code {")
        assertContains(css, "background: var(--md-inline-code-bg)")
        assertContains(css, "border-radius: 4px")
        assertContains(css, CODE_FONT_TOKEN)
        assertContains(css, "mark {")
        assertContains(css, "background: var(--md-mark-bg)")
    }

    @Test
    fun footnoteAndFigureStylesPresent() {
        val css = HtmlStyleSheet.generate(dark = false)
        assertContains(css, "sup a.footnote-ref")
        assertContains(css, "font-size: 11px")
        assertContains(css, "div[id^=\"fn-\"]")
        assertContains(css, "figure {")
        assertContains(css, "text-align: center")
        assertContains(css, "figcaption {")
    }

    @Test
    fun miscBlocksStylesPresent() {
        val css = HtmlStyleSheet.generate(dark = false)
        // hr
        assertContains(css, "border-top: 1px solid var(--md-border-color)")
        // ruby rt
        assertContains(css, "ruby rt {")
        // task list checkbox
        assertContains(css, "li input[type=\"checkbox\"]")
        assertContains(css, "var(--md-task-checked)")
        // spoiler placeholder
        assertContains(css, ".spoiler {")
        assertContains(css, "background-color: var(--md-spoiler-bg)")
        // math-display / vtext / page-break / print preserved from ST0
        assertContains(css, ".math-display {")
        assertContains(css, ".vtext, .vertical-text {")
        assertContains(css, ".page-break {")
        assertContains(css, "@media print {")
    }

    // ─────────────── integration with renderer ───────────────

    @Test
    fun fullPageViaRendererUsesStylesheet() {
        val document = MarkdownParser(ExtendedFlavour).parse("# t\n\ntext")
        val html = HtmlRenderer.renderFullHtmlPage(document, HtmlRenderer.Companion.HtmlPageOptions())
        val styleStart = html.indexOf("<style>")
        val styleEnd = html.indexOf("</style>")
        assertTrue(styleStart != -1 && styleEnd > styleStart, "full page must embed a <style> block")
        val style = html.substring(styleStart, styleEnd)
        assertContains(style, "--md-body-size: 13px")
        assertContains(style, "--md-body-line-height: 21.5px")
    }

    private companion object {
        const val CODE_FONT_TOKEN = "ui-monospace, SFMono-Regular, \"SF Mono\", Menlo, Consolas, monospace"
    }
}
