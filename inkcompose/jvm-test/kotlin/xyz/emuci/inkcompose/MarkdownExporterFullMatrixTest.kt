package xyz.emuci.inkcompose

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.kbrowser.webview.JcefChecker
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import java.io.File
import kotlin.test.assertTrue

/**
 * ST9 全节点矩阵集成测试。
 *
 * 在一个 Markdown 文档中覆盖尽可能多的 AST 节点类型（共 66 类），
 * 通过 [MarkdownExporter.toHtml] 渲染整页后逐节点断言关键结构串。
 *
 * 节点覆盖说明：
 * - 66 个节点中 ~60 个可通过 markdown 语法直接触发；
 * - BlankLine/FrontMatter/LinkReferenceDefinition/AbbreviationDefinition 等中间态节点
 *   不直接产出独立 HTML 结构，由 HtmlRendererTest 单元用例覆盖；
 * - DirectiveInline 需 `{% tag %}` 行内语法（ExtendedFlavour 支持）。
 */
class MarkdownExporterFullMatrixTest {

    /** 全节点覆盖 Markdown 文档（ExtendedFlavour 语法） */
    private val fullMatrixMarkdown = """
        ---
        title: Full Matrix Test
        ---

        [TOC]

        # Heading Level 1

        ## Heading Level 2

        ### Heading Level 3

        #### Heading Level 4

        ##### Heading Level 5

        ###### Heading Level 6

        Setext H1
        =========

        Setext H2
        ---------

        Paragraph with **strong**, *emphasis*, ~~strikethrough~~, `inline code`, [link](https://example.com "Link Title"), ==highlight==, ++inserted++, x^sup^, H~sub~O, :smile:, <kbd>Ctrl+C</kbd>, <span>raw inline</span>, &amp; entity, \*escaped\*, >!spoiler text!<, [[WikiPage]], {ruby base|ruby ann}, [styled text]{.red style="color:red"}.

        Line with hard break
        After break.

        Soft line break
        next line.

        ---

        - Unordered item one
        - Unordered item two

        1. Ordered item one
        2. Ordered item two

        > Blockquote text here.

        > [!NOTE]
        > Admonition note content.

        | Header A | Header B |
        |----------|----------|
        | Cell 1   | Cell 2   |

        ```kotlin
        fun hello() { println("hi") }
        ```

            Indented code block line.

        [^1]: Footnote definition content.

        Text with footnote reference[^1].

        $$
        \int_0^1 x^2 dx = \frac{1}{3}
        $$

        Inline math ${'$'}E=mc^2${'$'} in paragraph.

        Definition Term
        : Definition description text.

        ::: warning "Warning Title"
        Custom container body.
        :::

        ```mermaid
        graph TD; A-->B;
        ```

        ```vlr {height=100dp}
        ᠮᠣᠩᠭᠣᠯ
        ```

        ***pagebreak***

        {% note %}
        Directive block content.
        {% endnote %}

        === "Tab One"
        First tab content

        === "Tab Two"
        Second tab content

        [^bibliography]:
            smith2024: Smith J, "A Great Paper", 2024

        ---

        Citation reference: [@smith2024] in text.

        <div class="raw-html-block">Raw HTML block content</div>

        ![Figure Caption](test-image.png)

        Ref style link [click here][myref]

        [myref]: https://example.com "Ref Title"

        *[HTML]: HyperText Markup Language

        The HTML spec is long.

        :::columns
        :::column
        Column one content.
        :::
        :::column
        Column two content.
        :::
        :::
    """.trimIndent()

    // ───────────────────────── 主矩阵测试 ─────────────────────────

    @Test
    fun fullMatrixRendersAllNodeStructures() = runBlocking {
        val html = MarkdownExporter.toHtml(fullMatrixMarkdown, HtmlExportOptions(title = "Full Matrix"))

        // ── 页面结构 ──
        assertTrue(html.contains("<!DOCTYPE html>"), "full page: DOCTYPE")
        assertTrue(html.contains("<title>Full Matrix</title>"), "full page: title")

        // ── 标题类 ──
        assertTrue(html.contains("<h1"), "Heading h1")
        assertTrue(html.contains("<h2"), "Heading h2")
        assertTrue(html.contains("<h3"), "Heading h3")
        assertTrue(html.contains("<h4"), "Heading h4")
        assertTrue(html.contains("<h5"), "Heading h5")
        assertTrue(html.contains("<h6"), "Heading h6")
        assertTrue(html.contains("id="), "Heading auto-id")
        // SetextHeading: "Setext H1" → <h1, "Setext H2" → <h2
        assertTrue(html.contains("Setext H1"), "SetextHeading h1 content")
        assertTrue(html.contains("Setext H2"), "SetextHeading h2 content")

        // ── 段落/文本 ──
        assertTrue(html.contains("<p>"), "Paragraph <p>")
        assertTrue(html.contains("Paragraph with"), "Text content")

        // ── 换行 ──
        assertTrue(html.contains("<br"), "HardLineBreak <br>")
        // SoftLineBreak renders as \n inside <p>

        // ── 分隔线 ──
        assertTrue(html.contains("<hr"), "ThematicBreak <hr>")

        // ── 强调类 ──
        assertTrue(html.contains("<strong>strong</strong>"), "StrongEmphasis")
        assertTrue(html.contains("<em>emphasis</em>"), "Emphasis")
        assertTrue(html.contains("<del>strikethrough</del>"), "Strikethrough")
        assertTrue(html.contains("<ins>inserted</ins>"), "InsertedText")
        assertTrue(html.contains("<mark>highlight</mark>"), "Highlight")
        assertTrue(html.contains("<sup>sup</sup>"), "Superscript")
        assertTrue(html.contains("<sub>sub</sub>"), "Subscript")

        // ── 代码 ──
        assertTrue(html.contains("<code"), "InlineCode")
        assertTrue(html.contains("language-kotlin"), "FencedCodeBlock language")
        assertTrue(html.contains("code-block-pre"), "IndentedCodeBlock")
        // FencedCodeBlock with syntax highlighting (tok- classes from code theme)
        assertTrue(html.contains("tok-"), "FencedCodeBlock syntax highlight")

        // ── 链接/引用 ──
        assertTrue(html.contains("<a href=\"https://example.com\""), "Link")
        assertTrue(html.contains("class=\"wikilink\""), "WikiLink")
        // LinkReferenceDefinition: [click here][myref] → <a href="https://example.com" title="Ref Title">click here</a>
        assertTrue(html.contains("click here"), "LinkReferenceDefinition resolved link text")
        assertTrue(html.contains("Ref Title"), "LinkReferenceDefinition title")
        // Autolink: covered by Link assertions (same <a href> output)

        // ── 图片/Figure ──
        assertTrue(html.contains("<figure"), "Figure")
        assertTrue(html.contains("<img"), "Image <img>")
        assertTrue(html.contains("<figcaption"), "Figure caption")
        assertTrue(html.contains("Figure Caption"), "Figure caption text")

        // ── 列表 ──
        assertTrue(html.contains("<ul>"), "ListBlock <ul>")
        assertTrue(html.contains("<ol>"), "ListBlock <ol>")
        assertTrue(html.contains("<li>"), "ListItem <li>")
        assertTrue(html.contains("Unordered item one"), "ListItem text")

        // ── 表格 ──
        assertTrue(html.contains("<table>"), "Table")
        assertTrue(html.contains("<thead>"), "TableHead")
        assertTrue(html.contains("<tbody>"), "TableBody")
        assertTrue(html.contains("<tr"), "TableRow")
        assertTrue(html.contains("<th"), "TableCell <th>")
        assertTrue(html.contains("<td"), "TableCell <td>")
        assertTrue(html.contains("Header A"), "Table header content")

        // ── 引用 ──
        assertTrue(html.contains("<blockquote>"), "BlockQuote")
        assertTrue(html.contains("Blockquote text"), "BlockQuote content")

        // ── Admonition ──
        assertTrue(html.contains("admonition note"), "Admonition class")
        assertTrue(html.contains("admonition-title"), "Admonition title")

        // ── 脚注 ──
        assertTrue(html.contains("footnote-ref"), "FootnoteReference")
        assertTrue(html.contains("id=\"fn-1\""), "FootnoteDefinition id")
        assertTrue(html.contains("Footnote definition content"), "FootnoteDefinition text")

        // ── 参考文献 ──
        assertTrue(html.contains("bibliography"), "BibliographyDefinition")
        assertTrue(html.contains("bib-smith2024"), "BibliographyEntry id")
        assertTrue(html.contains("citation-ref"), "CitationReference")

        // ── 定义列表 ──
        assertTrue(html.contains("<dl>"), "DefinitionList <dl>")
        assertTrue(html.contains("<dt>"), "DefinitionTerm <dt>")
        assertTrue(html.contains("<dd>"), "DefinitionDescription <dd>")
        assertTrue(html.contains("Definition Term"), "DefinitionTerm content")

        // ── 数学 ──
        assertTrue(html.contains("math-display"), "MathBlock")
        assertTrue(html.contains("math-inline"), "InlineMath")

        // ── 自定义容器 ──
        assertTrue(html.contains("custom-container"), "CustomContainer")
        assertTrue(html.contains("warning"), "CustomContainer type")
        assertTrue(html.contains("Warning Title"), "CustomContainer title")

        // ── 图表 ──
        assertTrue(html.contains("class=\"mermaid\""), "DiagramBlock mermaid")
        assertTrue(html.contains("graph TD"), "DiagramBlock content")

        // ── 竖排 ──
        assertTrue(html.contains("vtext"), "VerticalTextBlock class")
        assertTrue(html.contains("writing-mode: vertical-lr"), "VerticalTextBlock CSS")
        assertTrue(html.contains("ᠮᠣᠩᠭᠣᠯ"), "VerticalTextBlock content")

        // ── 多列布局 ──
        assertTrue(html.contains("columns-layout"), "ColumnsLayout")
        assertTrue(html.contains("column-item"), "ColumnItem")

        // ── TOC ──
        assertTrue(html.contains("table-of-contents"), "TocPlaceholder")

        // ── 剧透 ──
        assertTrue(html.contains("spoiler"), "Spoiler")
        assertTrue(html.contains("spoiler text"), "Spoiler content")

        // ── 分页 ──
        assertTrue(html.contains("page-break"), "PageBreak")

        // ── 指令块 ──
        assertTrue(html.contains("data-directive"), "DirectiveBlock")
        assertTrue(html.contains("note"), "DirectiveBlock tag")

        // ── 选项卡 ──
        assertTrue(html.contains("tab-bar"), "TabBlock tab bar")
        assertTrue(html.contains("tab-item"), "TabItem")
        assertTrue(html.contains("aria-selected"), "TabItem aria-selected")
        assertTrue(html.contains("Tab One"), "TabItem title")

        // ── HTML 透传 ──
        assertTrue(html.contains("raw-html-block"), "HtmlBlock passthrough")
        assertTrue(html.contains("raw inline"), "InlineHtml passthrough")

        // ── 行内杂项 ──
        assertTrue(html.contains("&amp;"), "HtmlEntity")
        assertTrue(html.contains("escaped"), "EscapedChar")
        assertTrue(html.contains("<kbd>"), "KeyboardInput")
        assertTrue(html.contains("<ruby>"), "RubyText")
        assertTrue(html.contains("ruby ann"), "RubyText annotation")
        assertTrue(html.contains("<abbr"), "Abbreviation")
        assertTrue(html.contains("HyperText Markup Language"), "Abbreviation title")
        assertTrue(html.contains("class=\"red\""), "StyledText")
        assertTrue(html.contains("color:red"), "StyledText style")

        // ── Emoji: :smile: → 😄 (from STANDARD_EMOJI_MAP) ──
        assertTrue(html.contains("😄"), "Emoji unicode output")
    }

    // ───────────────────────── 暗色主题 ─────────────────────────

    @Test
    fun fullMatrixDarkTheme() = runBlocking {
        val html = MarkdownExporter.toHtml(
            fullMatrixMarkdown,
            HtmlExportOptions(title = "Dark", theme = HtmlExportTheme.DARK),
        )

        assertTrue(html.contains("data-theme=\"dark\""), "dark: data-theme attribute")
        // 暗色 admonition 色板
        assertTrue(html.contains("#0d1d30"), "dark: admonition note bg color")
        // 代码高亮暗色主题 (OneDarkPro)
        assertTrue(html.contains("tok-"), "dark: code highlight present")
    }

    // ───────────────────────── PDF 冒烟 ─────────────────────────

    @Test
    fun fullMatrixPdfSmoke() = runBlocking {
        assumeTrue("PDF export requires JCEF", JcefChecker.isJcefAvailable)
        if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
            assumeTrue("Linux JCEF uses X11 and requires DISPLAY", !System.getenv("DISPLAY").isNullOrBlank())
        }

        val testStorageDir = File(System.getProperty("java.io.tmpdir"), "inkcompose_test_kbrowser_matrix").absolutePath
        File(testStorageDir).mkdirs()
        KBrowser.initializeConfig(testStorageDir, useOsr = true)
        initializeKBrowser()

        // 全节点文档含 mermaid/vlr/数学——KBPdf 0.2.2 等 .mermaid svg 渲染完成。
        // 若后续发现某些节点干扰 mermaid 渲染导致超时，可拆出"次全"文档。
        val tmpPdf = File.createTempFile("inkcompose_matrix_pdf_", ".pdf")
        tmpPdf.deleteOnExit()

        val result = MarkdownExporter.toPdf(
            fullMatrixMarkdown,
            tmpPdf.absolutePath,
            PdfExportOptions(title = "Full Matrix PDF"),
        )
        assertTrue(result.isSuccess, "PDF export should succeed: ${result.exceptionOrNull()?.message}")
        assertTrue(tmpPdf.length() > 0, "PDF should not be empty")
        val head = tmpPdf.inputStream().use { it.readNBytes(4) }.toString(Charsets.US_ASCII)
        assertTrue(head.startsWith("%PDF"), "output must be a PDF file, head=$head")
    }
}
