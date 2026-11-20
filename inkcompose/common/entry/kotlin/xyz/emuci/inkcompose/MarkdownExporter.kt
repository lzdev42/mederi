package xyz.emuci.inkcompose

import xyz.emuci.markdown.parser.MarkdownParser
import xyz.emuci.markdown.parser.ast.ContainerNode
import xyz.emuci.markdown.parser.ast.Document
import xyz.emuci.markdown.parser.ast.LeafNode
import xyz.emuci.markdown.parser.ast.Node
import xyz.emuci.markdown.parser.flavour.ExtendedFlavour
import xyz.emuci.markdown.parser.flavour.MarkdownFlavour
import xyz.emuci.markdown.parser.html.HtmlExportHooks
import xyz.emuci.markdown.parser.html.mongolianScriptCodePointCount
import xyz.emuci.markdown.parser.html.HtmlExportRequirements
import xyz.emuci.markdown.parser.html.HtmlFontVariant
import xyz.emuci.markdown.parser.html.KATEX_FONT_VARIANTS
import androidx.compose.ui.graphics.Color
import xyz.emuci.markdown.parser.html.HtmlRenderer
import xyz.emuci.markdown.parser.html.HtmlRenderer.Companion.HtmlPageOptions
import xyz.emuci.syntax.ast.TokenType
import xyz.emuci.syntax.theme.CodeTheme
import xyz.emuci.syntax.theme.GithubLightTheme
import xyz.emuci.syntax.theme.OneDarkProTheme
import xyz.emuci.syntax.theme.safeColorFor
import xyz.kbrowser.webview.KBrowser
import kotlin.math.roundToInt

/** HTML 导出主题 */
enum class HtmlExportTheme { LIGHT, DARK }

/**
 * HTML 导出配置
 */
data class HtmlExportOptions(
    val title: String = "Markdown Export",
    val customCss: String = "",
    val embedFonts: Boolean = true,
    val flavour: MarkdownFlavour = ExtendedFlavour,
    val theme: HtmlExportTheme = HtmlExportTheme.LIGHT,
    /** 代码高亮主题（entry 层可选） */
    val codeTheme: CodeTheme? = null,
    /** 是否嵌入数学字体 */
    val embedMathFonts: Boolean = true,
    /** 是否嵌入 mermaid 运行时 */
    val embedMermaid: Boolean = true,
    /** 自定义 mermaid 脚本 URL 或内联内容 */
    val mermaidScript: String? = null,
    /** 页面语言；null 时默认 zh-CN */
    val lang: String? = null,
    /** 宿主覆盖 hooks；null 走 HtmlExportPipeline.defaultHooks() */
    val hooks: HtmlExportHooks? = null,
)

/**
 * PDF 导出配置
 */
data class PdfExportOptions(
    val title: String = "Markdown Export",
    val customCss: String = "",
    val embedFonts: Boolean = true,
    /** 横向打印。现阶段 KBPdf（KBrowser 0.2.2）未暴露横向设置，该参数暂不生效。 */
    val landscape: Boolean = false,
    val printBackground: Boolean = true,
    val flavour: MarkdownFlavour = ExtendedFlavour,
    /**
     * 0.2.2 起 PDF 走 KBPdf 自建离屏实例，该参数不再被消费；
     * 保留用于 API 兼容与未来 KBPdf 注入支持。
     */
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
 * 平台层读取打包字体 ttf 并转 Base64；平台不支持或读取失败返回 null。
 * JVM 走 classloader 快路径（同步即时返回）；其余平台走 Compose Resources（挂起点），
 * suspend 化以统一跨平台真实现路径。
 */
internal expect suspend fun readBundledFontBase64(fontFileName: String): String?

/**
 * 平台层读取打包的 mermaid.min.js 全文；不支持或读取失败返回 null。
 * JVM 走 classloader（SingleMermaidWorker 同机制，资源路径 /mermaid.min.js）；
 * 其余平台返回 null 桩（mermaid 资产仅 JVM 打包，导出页 mermaid 走源码降级展示）。
 */
internal expect fun readBundledMermaidScript(): String?

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
     *
     * suspend：按需字体读取为挂起点（跨平台走 Compose Resources；JVM 走 classloader 快路径通常即时完成）。
     */
    suspend fun toHtml(
        markdown: String,
        options: HtmlExportOptions = HtmlExportOptions(),
    ): String {
        val parser = MarkdownParser(options.flavour)
        val document = parser.parse(markdown)
        return toHtmlInternal(document, options, markdownText = markdown)
    }

    /**
     * 将 Markdown AST 导出为完整的、自包含样式的独立 HTML 页面。
     *
     * suspend：同 [toHtml]（字符串入口）。
     */
    suspend fun toHtml(
        document: Document,
        options: HtmlExportOptions = HtmlExportOptions(),
    ): String = toHtmlInternal(document, options, markdownText = null)

    /**
     * [markdownText] = 原始 Markdown 源文本（字符串入口有；AST 入口为 null，lang 检测改用 AST 文本收集）。
     *
     * 单一开关链（三开关 × 三需求信号，two-pass 先探测再嵌入）：
     * - embedFonts × hasVerticalText（文档含 vlr 竖排块）→ 蒙古文 @font-face 内嵌；
     * - embedMathFonts × hasMath → KaTeX 字体 @font-face 内嵌；
     * - embedMermaid × hasDiagram → mermaid bootstrap 脚本注入。
     */
    private suspend fun toHtmlInternal(
        document: Document,
        options: HtmlExportOptions,
        markdownText: String?,
    ): String {
        val hooks = options.hooks ?: HtmlExportPipeline.defaultHooks()
        val requirements = HtmlExportRequirements()
        // Pass 1：轻量渲染收集资源需求（hasMath 等），纯内存字符串构建、无 IO，开销可忽略
        HtmlRenderer(hooks = hooks, requirements = requirements).render(document)
        // 页面 lang：显式 options.lang 覆盖优先；否则蒙古文启发式（详见 [detectHtmlLang]）
        val lang = detectHtmlLang(markdownText, document, requirements.hasVerticalText, options.lang)
        // 蒙古文（vlr）字体按需嵌入：开关打开且文档含 vlr 竖排块才读字体
        val fontBase64 = if (options.embedFonts && requirements.hasVerticalText) {
            readBundledFontBase64("NotoSansMongolian-Regular.ttf")
        } else {
            null
        }
        val fontMap = if (!fontBase64.isNullOrBlank()) {
            mapOf("Noto Sans Mongolian" to fontBase64)
        } else {
            emptyMap()
        }
        // KaTeX 数学字体按需嵌入：文档含数学且开关打开才读字体
        val mathFontVariants = collectKaTeXFontVariants(
            embedMathFonts = options.embedMathFonts,
            hasMath = requirements.hasMath,
        )
        // 代码主题：显式 codeTheme 优先；否则按明暗模式选择内置主题（light->GithubLight、dark->OneDarkPro）
        val effectiveCodeTheme = options.codeTheme
            ?: if (options.theme == HtmlExportTheme.DARK) OneDarkProTheme else GithubLightTheme
        val codeColors = buildCodeColors(effectiveCodeTheme)
        // mermaid 图表 bootstrap 按需注入：文档含图表且 embedMermaid=true 才构造
        val diagramBootstrap = collectDiagramBootstrap(
            hooks = hooks,
            embedMermaid = options.embedMermaid,
            requirements = requirements,
            dark = options.theme == HtmlExportTheme.DARK,
            customScript = options.mermaidScript,
        )
        return HtmlRenderer.renderFullHtmlPage(
            document = document,
            options = HtmlPageOptions(
                title = options.title,
                customCss = options.customCss,
                dark = options.theme == HtmlExportTheme.DARK,
                fontBase64ByFamily = fontMap,
                lang = lang,
                hooks = hooks,
                requirements = requirements,
                codeColors = codeColors,
                mathFontVariants = mathFontVariants,
                diagramBootstrap = diagramBootstrap,
            ),
        )
    }

    /**
     * HTML 页面 lang 启发式检测。判据优先级：
     * 1. [explicit]（[HtmlExportOptions.lang]）显式覆盖——非空即采用，不做启发式；
     * 2. 文档含 vlr 竖排块（[hasVerticalText]，用户显式意图强信号）且蒙古文系码点 > 0 → "mn-Mong-CN"；
     * 3. 蒙古文系码点占非空白码点 ≥ 30%（宽松阈值，蒙古文文档即使混排 CJK/拉丁通常仍主导）→ "mn-Mong-CN"；
     * 4. 否则默认 "zh-CN"。
     *
     * [markdownText]（原始 Markdown 源文本）优先直接统计；AST 入口无源文本时
     * 递归收集 [Document] 树全部叶子 literal 作为统计语料。
     */
    internal fun detectHtmlLang(
        markdownText: String?,
        document: Document,
        hasVerticalText: Boolean,
        explicit: String?,
    ): String {
        if (!explicit.isNullOrBlank()) return explicit
        val text = if (markdownText != null) {
            markdownText
        } else {
            val sb = StringBuilder()
            fun collect(node: Node) {
                when (node) {
                    is ContainerNode -> node.children.forEach { collect(it) }
                    is LeafNode -> sb.append(node.literal)
                }
            }
            collect(document)
            sb.toString()
        }
        val mongolianCount = mongolianScriptCodePointCount(text)
        if (mongolianCount == 0) return "zh-CN"
        val totalCount = text.count { !it.isWhitespace() }
        if (hasVerticalText || mongolianCount * 10 >= totalCount * 3) return "mn-Mong-CN"
        return "zh-CN"
    }

    /**
     * 按需收集 KaTeX 字体变体：options.embedMathFonts=false 或文档无数学 → 空列表。
     * 任何变体读取失败（平台不支持 / 资源缺失）→ 跳过该变体（静默，不 fail 整页）。
     * 返回列表按 [KATEX_FONT_VARIANTS] 顺序，同 family 的多变体并存（@font-face 逐条声明）。
     * suspend：字体读取为挂起点（[readBundledFontBase64]）。
     */
    private suspend fun collectKaTeXFontVariants(
        embedMathFonts: Boolean,
        hasMath: Boolean,
    ): List<Pair<HtmlFontVariant, String>> {
        if (!embedMathFonts || !hasMath) return emptyList()
        return buildList {
            for (variant in KATEX_FONT_VARIANTS) {
                val base64 = try {
                    readBundledFontBase64(variant.resourceFileName)
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (base64.isEmpty()) continue
                add(variant to base64)
            }
        }
    }

    /**
     * 按需收集图表 bootstrap 完整 <script>…</script> 片段；返回空串则 renderFullHtmlPage 不注入
     * （mermaid 块以转义源码展示，结构不变）。
     *
     * 优先级链：
     * 1. [customScript]（HtmlExportOptions.mermaidScript，用户逃生门）：http(s) URL → <script src>，
     *    否则视为内联 JS 全文 → <script>…</script>。customScript 只是库，
     *    initialize/run/ready flag 仍由 [mermaidBootstrapScripts] 统一注入（URL 模式经 load 守卫等待库就绪）。
     * 2. hooks.diagram（宿主全权接管，返回值需自含 initialize + run + ready flag 语义——见
     *    DiagramBootstrapProvider KDoc）；非 null 时不再叠加内置脚本。
     * 3. 内置：[readBundledMermaidScript] 非空 → [buildMermaidBootstrap] 完整 bootstrap；
     *    null（平台无资源）→ 空串（源码降级）。
     */
    private fun collectDiagramBootstrap(
        hooks: HtmlExportHooks,
        embedMermaid: Boolean,
        requirements: HtmlExportRequirements,
        dark: Boolean,
        customScript: String?,
    ): String {
        if (!embedMermaid || !requirements.hasDiagram) return ""
        if (customScript != null) {
            val libTag = if (customScript.startsWith("http://") || customScript.startsWith("https://")) {
                "<script src=\"$customScript\"></script>"
            } else {
                "<script>$customScript</script>"
            }
            return buildString {
                append(libTag)
                append('\n')
                append(mermaidBootstrapScripts(dark = dark, guardForAsyncLibrary = customScript.startsWith("http")))
            }
        }
        val hookBootstrap = try {
            hooks.diagram?.provideBootstrap(requirements.diagramTypes)
        } catch (_: Throwable) {
            null
        }
        if (!hookBootstrap.isNullOrBlank()) return hookBootstrap
        val libraryJs = try {
            readBundledMermaidScript()
        } catch (_: Throwable) {
            null
        }
        if (libraryJs.isNullOrBlank()) return ""
        return buildMermaidBootstrap(libraryJs, dark)
    }

    /**
     * mermaid initialize + run + ready flag 内联脚本（内置 / customScript 两种模式共用）。
     *
     * [guardForAsyncLibrary] = true 时（customScript 为外部 URL，异步加载）包 load 守卫：
     * 库加载完成后再执行；库加载失败则只置 ready flag 放行（源码降级）。
     * ready flag `window.__inkExportReady` 无论渲染成败都置位——打印方等待的是"不再变化"信号。
     * init/run 全部 try/catch：库损坏或渲染失败不吞页面。
     * startOnLoad:false 下必须显式 mermaid.run()（扫描页面全部 .mermaid 元素）。
     */
    private fun mermaidBootstrapScripts(dark: Boolean, guardForAsyncLibrary: Boolean = false): String {
        val init = """
        var __inkInit = function(){
          var __setReady = function(){ try { window.__inkExportReady = true; } catch(e){} };
          try {
            mermaid.initialize({
              startOnLoad: false,
              securityLevel: 'strict',
              htmlLabels: false,
              flowchart: { htmlLabels: false },
              theme: '${if (dark) "dark" else "default"}',
              darkMode: ${if (dark) "true" else "false"}
            });
            mermaid.run().then(function(){ __setReady(); }, function(){ __setReady(); });
          } catch (e) { __setReady(); }
        };
        """.trimIndent()
        // 守卫判据与 SingleMermaidWorker 一致：typeof mermaid !== 'undefined'（v10+ UMD 挂 window.mermaid 模块对象）
        if (guardForAsyncLibrary) {
            val tryInit = "if (typeof mermaid !== 'undefined') {\n    $init __inkInit();\n  } else {\n    try { window.__inkExportReady = true; } catch(e){}\n  }\n"
            return "<script>\n(function(){\n" +
                "  var __run = function(){\n    $tryInit  };\n" +
                "  if (typeof mermaid !== 'undefined') {\n    __run();\n  } else {\n    window.addEventListener('load', __run);\n  }\n" +
                "})();\n</script>"
        }
        return "<script>\n(function(){\n  $init})();\n</script>"
    }

    /**
     * 内置完整 bootstrap：内联 mermaid.min.js 全文 + [mermaidBootstrapScripts] init/run。
     * 库全文原样插入，仅防御性转义 `</script>` → `<\/script>`
     *（JS 字符串/正则中反斜杠转义等价，避免提前闭合外层 <script>；当前库 0 处，恒为 no-op）。
     */
    private fun buildMermaidBootstrap(libraryJs: String, dark: Boolean): String {
        val safeLibrary = libraryJs.replace("</script>", "<\\/script>")
        return "<script>\n$safeLibrary\n</script>\n" + mermaidBootstrapScripts(dark = dark)
    }

    /**
     * 将 [CodeTheme] 的 14 类 TokenType 颜色转为中立 category -> hex 映射，
     * 供 HtmlStyleSheet 生成 tok-* 规则（entry 层合法依赖 syntax 域）。
     */
    private fun buildCodeColors(theme: CodeTheme): Map<String, String> = buildMap {
        for (type in TokenType.entries) {
            val color = theme.safeColorFor(type)
            put(categoryOf(type), color.toExportHex())
        }
    }

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

    /** Compose [Color] -> "#rrggbb"（代码高亮色均为不透明，忽略 alpha 通道） */
    private fun Color.toExportHex(): String {
        val r = (red * 255f).roundToInt().coerceIn(0, 255)
        val g = (green * 255f).roundToInt().coerceIn(0, 255)
        val b = (blue * 255f).roundToInt().coerceIn(0, 255)
        val rgb = (r shl 16) or (g shl 8) or b
        return "#" + rgb.toString(16).padStart(6, '0')
    }

    /**
     * 将 Markdown 字符串导出为 PDF 文件。
     *
     * PDF 链路：HTML 经 [toHtml] 渲染（mermaid/字体/图片按需内嵌）后交 KBPdf.fromHtml
     *（KBrowser 0.2.2+，内建等待 load/字体/mermaid SVG/图片就绪后打印），输出矢量 PDF。
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
