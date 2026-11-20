package xyz.emuci.markdown.parser.html

/** 中立语法高亮 token：category 为小写字符串（"keyword"/"string"/"comment"/"number"/"type"/"function"/"annotation"/"builtin"/"constant"/"operator"/"punctuation"/"identifier"/"plain"） */
data class HtmlHighlightToken(val category: String, val text: String)

fun interface MathHtmlProvider {
    /** 返回完整 <math>…</math> 元素字符串；null = 降级为纯文本 */
    fun toMathMl(latex: String, displayMode: Boolean): String?
}

fun interface CodeHighlightProvider {
    /** 返回按序覆盖全部源码字符的 token 列表（text 串联 == code）；null = 降级纯文本 */
    fun highlight(code: String, language: String): List<HtmlHighlightToken>?
}

fun interface DiagramBootstrapProvider {
    /** 返回 <script>…</script> 完整片段（含 mermaid 库 + initialize + run + ready flag）；null = 降级源码显示 */
    fun provideBootstrap(diagramTypes: Set<String>): String?
}

data class HtmlExportHooks(
    val math: MathHtmlProvider? = null,
    val code: CodeHighlightProvider? = null,
    val diagram: DiagramBootstrapProvider? = null,
)

/** 资源需求收集器：HtmlRenderer 渲染时填充，页面组装方读取以做按需嵌入 */
class HtmlExportRequirements {
    var hasMath: Boolean = false
    var hasDiagram: Boolean = false
    var hasVerticalText: Boolean = false
    val diagramTypes: MutableSet<String> = mutableSetOf()
}

/**
 * KaTeX 字体变体描述：@font-face 声明所需的全部信息。
 * Base64 数据由宿主（entry 层）提供；本表只是元数据，保持 markdown-parser 域零依赖。
 */
data class HtmlFontVariant(
    /** @font-face font-family 名，如 "KaTeX_Main" */
    val family: String,
    /** CSS font-weight 描述符："normal" | "bold" */
    val weight: String,
    /** CSS font-style 描述符："normal" | "italic" */
    val style: String,
    /** composeResources/font 下的 ttf 文件名（无路径），如 "katex_main_regular.ttf" */
    val resourceFileName: String,
)

/** KaTeX 全量 20 变体清单（与 composeResources/font/katex_*.ttf 一一对应） */
val KATEX_FONT_VARIANTS: List<HtmlFontVariant> = listOf(
    HtmlFontVariant("KaTeX_AMS", "normal", "normal", "katex_ams_regular.ttf"),
    HtmlFontVariant("KaTeX_Caligraphic", "normal", "normal", "katex_caligraphic_regular.ttf"),
    HtmlFontVariant("KaTeX_Caligraphic", "bold", "normal", "katex_caligraphic_bold.ttf"),
    HtmlFontVariant("KaTeX_Fraktur", "normal", "normal", "katex_fraktur_regular.ttf"),
    HtmlFontVariant("KaTeX_Fraktur", "bold", "normal", "katex_fraktur_bold.ttf"),
    HtmlFontVariant("KaTeX_Main", "normal", "normal", "katex_main_regular.ttf"),
    HtmlFontVariant("KaTeX_Main", "bold", "normal", "katex_main_bold.ttf"),
    HtmlFontVariant("KaTeX_Main", "normal", "italic", "katex_main_italic.ttf"),
    HtmlFontVariant("KaTeX_Main", "bold", "italic", "katex_main_bolditalic.ttf"),
    HtmlFontVariant("KaTeX_Math", "normal", "italic", "katex_math_italic.ttf"),
    HtmlFontVariant("KaTeX_Math", "bold", "italic", "katex_math_bolditalic.ttf"),
    HtmlFontVariant("KaTeX_SansSerif", "normal", "normal", "katex_sansserif_regular.ttf"),
    HtmlFontVariant("KaTeX_SansSerif", "bold", "normal", "katex_sansserif_bold.ttf"),
    HtmlFontVariant("KaTeX_SansSerif", "normal", "italic", "katex_sansserif_italic.ttf"),
    HtmlFontVariant("KaTeX_Script", "normal", "normal", "katex_script_regular.ttf"),
    HtmlFontVariant("KaTeX_Size1", "normal", "normal", "katex_size1_regular.ttf"),
    HtmlFontVariant("KaTeX_Size2", "normal", "normal", "katex_size2_regular.ttf"),
    HtmlFontVariant("KaTeX_Size3", "normal", "normal", "katex_size3_regular.ttf"),
    HtmlFontVariant("KaTeX_Size4", "normal", "normal", "katex_size4_regular.ttf"),
    HtmlFontVariant("KaTeX_Typewriter", "normal", "normal", "katex_typewriter_regular.ttf"),
)

/**
 * 判断码点是否属于蒙古文系竖排文字系统（蒙古文 0x1800..0x18AF + 蒙古文补充 0x11660..0x1167F
 * + 八思巴 0xA840..0xA87F），与 vtext 域 [isVerticalFontSystem] 判据一致。
 * 本域保持零依赖，不 import vtext，区间自写。
 */
fun isMongolianScriptCodePoint(codePoint: Int): Boolean =
    codePoint in 0x1800..0x18AF ||
        codePoint in 0x11660..0x1167F ||
        codePoint in 0xA840..0xA87F

/**
 * 统计文本中蒙古文系码点数量（按码点而非 UTF-16 单元计数，星平面字符手动配对代理对）。
 * 纯 Kotlin common 实现（无 JDK codePoints / Character.charCount 依赖），surrogate 手工组合码点。
 */
fun mongolianScriptCodePointCount(text: String): Int {
    var i = 0
    var count = 0
    while (i < text.length) {
        val high = text[i]
        if (high.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            val codePoint = ((high.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) + 0x10000
            if (isMongolianScriptCodePoint(codePoint)) count++
            i += 2
        } else {
            if (isMongolianScriptCodePoint(high.code)) count++
            i += 1
        }
    }
    return count
}
