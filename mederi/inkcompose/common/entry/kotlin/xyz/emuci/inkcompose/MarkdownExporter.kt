package xyz.emuci.inkcompose

import xyz.emuci.markdown.parser.MarkdownParser
import xyz.emuci.markdown.parser.ast.Document
import xyz.emuci.markdown.parser.flavour.ExtendedFlavour
import xyz.emuci.markdown.parser.flavour.MarkdownFlavour
import xyz.emuci.markdown.parser.html.HtmlRenderer
import xyz.kbrowser.webview.KBrowser

/**
 * HTML 导出配置
 */
data class HtmlExportOptions(
    val title: String = "Markdown Export",
    val customCss: String = "",
    val embedFonts: Boolean = true,
    val flavour: MarkdownFlavour = ExtendedFlavour,
)

/**
 * PDF 导出配置
 */
data class PdfExportOptions(
    val title: String = "Markdown Export",
    val customCss: String = "",
    val embedFonts: Boolean = true,
    val landscape: Boolean = false,
    val printBackground: Boolean = true,
    val flavour: MarkdownFlavour = ExtendedFlavour,
    val browser: KBrowser? = null,
)

/**
 * 平台层 PDF 导出实现
 */
internal expect suspend fun exportMarkdownHtmlToPdfPlatform(
    html: String,
    outputPath: String,
    options: PdfExportOptions,
): Result<String>

/**
 * 平台层获取打包的蒙古文字体 Base64 编码
 */
internal expect fun getBundledMongolianFontBase64(): String?

/**
 * Markdown 文档导出器。
 *
 * 支持将 Markdown 文本或 AST 导出为完整的自包含 HTML 页面，或通过无头打印导出矢量 PDF。
 */
object MarkdownExporter {

    @kotlin.concurrent.Volatile
    private var defaultBrowser: KBrowser? = null

    /**
     * 由外部（宿主应用）注入已初始化就绪的 KBrowser 全局单例。
     * 若导出时 options.browser 为空，则回退使用此默认注入。
     */
    fun setBrowser(browser: KBrowser?) {
        this.defaultBrowser = browser
    }

    fun getBrowser(): KBrowser? = defaultBrowser

    /**
     * 将 Markdown 字符串导出为完整的、自包含样式的独立 HTML 页面。
     */
    fun toHtml(
        markdown: String,
        options: HtmlExportOptions = HtmlExportOptions(),
    ): String {
        val parser = MarkdownParser(options.flavour)
        val document = parser.parse(markdown)
        return toHtml(document, options)
    }

    /**
     * 将 Markdown AST 导出为完整的、自包含样式的独立 HTML 页面。
     */
    fun toHtml(
        document: Document,
        options: HtmlExportOptions = HtmlExportOptions(),
    ): String {
        val fontBase64 = if (options.embedFonts) getBundledMongolianFontBase64() else null
        return HtmlRenderer.renderFullHtmlPage(
            document = document,
            title = options.title,
            customCss = options.customCss,
            embeddedFontBase64 = fontBase64,
        )
    }

    /**
     * 将 Markdown 字符串导出为 PDF 文件。
     *
     * @param markdown Markdown 文本
     * @param outputPath 输出 PDF 文件绝对或相对路径
     * @param options 导出选项
     * @return 成功返回生成的 PDF 绝对路径，失败返回 Result.failure
     */
    suspend fun toPdf(
        markdown: String,
        outputPath: String,
        options: PdfExportOptions = PdfExportOptions(),
    ): Result<String> {
        val parser = MarkdownParser(options.flavour)
        val document = parser.parse(markdown)
        return toPdf(document, outputPath, options)
    }

    /**
     * 将 Markdown AST 导出为 PDF 文件。
     */
    suspend fun toPdf(
        document: Document,
        outputPath: String,
        options: PdfExportOptions = PdfExportOptions(),
    ): Result<String> {
        val htmlOptions = HtmlExportOptions(
            title = options.title,
            customCss = options.customCss,
            embedFonts = options.embedFonts,
            flavour = options.flavour,
        )
        val html = toHtml(document, htmlOptions)
        val effectiveOptions = if (options.browser == null && defaultBrowser != null) {
            options.copy(browser = defaultBrowser)
        } else {
            options
        }
        return exportMarkdownHtmlToPdfPlatform(html, outputPath, effectiveOptions)
    }
}
