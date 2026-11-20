package xyz.emuci.markdown.parser.html

import xyz.emuci.inkcompose.HtmlExportOptions
import xyz.emuci.inkcompose.HtmlExportPipeline
import xyz.emuci.inkcompose.MarkdownExporter
import xyz.emuci.markdown.parser.MarkdownParser
import xyz.emuci.markdown.parser.flavour.ExtendedFlavour
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ST5 VLR 竖排完整对齐 + lang 启发式检测验收。
 *
 * 1-6、9-12 走 HtmlRenderer / HtmlStyleSheet 直渲染腿；
 * 7、8、13 走 MarkdownExporter.toHtml（entry 集成）腿（String 入口 + AST 入口）。
 *
 * 蒙文字符样本：ᠮ(U+182E) ᠣ(U+1823) ᠨ(U+1828) ᠭ(U+182D) ᠯ(U+182F) 等，均在蒙古文区段 0x1800..0x18AF。
 */
class HtmlExportVlrTest {

    private fun parse(markdown: String) = MarkdownParser(ExtendedFlavour).parse(markdown)

    private fun render(markdown: String): String =
        HtmlRenderer(hooks = HtmlExportPipeline.defaultHooks()).render(parse(markdown))

    /** 真实蒙古文样本（"蒙古" ᠮᠣᠨᠭᠭᠣᠯ + 空格 + ᠪᠢᠴᠢᠭ） */
    private val mongolianLine = "ᠮᠣᠨᠭᠭᠣᠯ ᠪᠢᠴᠢᠭ"

    /** 含 vlr 块的蒙古文整页文档（lang 启发式强信号：hasVerticalText + 蒙文字符 > 0） */
    private val mongolianDoc = "# ᠮᠣᠨᠭᠭᠣᠯ ᠰᠠᠭᠠᠨ\n\n```vlr\n$mongolianLine\n```\n"

    // ─────────────── 竖排 style 对齐（Renderer 腿）───────────────

    @Test
    fun vlrBlockRendersVerticalLrStyle() {
        val html = render("```vlr\n$mongolianLine\n```")
        assertContains(html, "writing-mode: vertical-lr")
        assertContains(html, "text-orientation: mixed")
        assertContains(html, "vtext vertical-text")
    }

    @Test
    fun vlrFontFallbackChainIncludesSystemFonts() {
        val html = render("```vlr\n$mongolianLine\n```")
        // 嵌入字体首选 + 平台系统蒙文字体兜底 + serif 保底
        assertContains(html, "'Noto Sans Mongolian'")
        assertContains(html, "'Mongolian Baiti'")
        assertContains(html, "'Segoe UI Historic'")
        assertContains(html, "serif")
    }

    @Test
    fun vlrDefaultLineHeightMatchesCompose() {
        // Compose verticalTextStyle：fontSize 13sp / lineHeight 21.5sp；fontSize 未指定时用 21.5px
        val html = render("```vlr\n$mongolianLine\n```")
        assertContains(html, "line-height: 21.5px")
    }

    @Test
    fun vlrExplicitFontSizeScalesLineHeight() {
        // 1.654 × 18px = 29.772 → 保留 1 位小数 29.8px
        val html = render("```vlr {fontSize=18sp}\n$mongolianLine\n```")
        assertContains(html, "font-size: 18px")
        assertContains(html, "line-height: 29.8px")
    }

    @Test
    fun vlrNonNumericFontSizeUsesUnitlessLineHeightFactor() {
        // 非数值单位（2em）→ CSS 无单位倍数 1.654
        val html = render("```vlr {fontSize=2em}\n$mongolianLine\n```")
        assertContains(html, "font-size: 2em")
        assertContains(html, "line-height: 1.654")
    }

    @Test
    fun vlrWrapBranchesMatchWhiteSpace() {
        assertContains(render("```vlr {wrap=true}\n$mongolianLine\n```"), "white-space: normal")
        assertContains(render("```vlr {wrap=false}\n$mongolianLine\n```"), "white-space: pre")
    }

    @Test
    fun vlrHeightAppliedAsPx() {
        val html = render("```vlr {height=300dp}\n$mongolianLine\n```")
        assertContains(html, "height: 300px")
    }

    // ─────────────── lang 启发式（Exporter 腿）───────────────

    @Test
    fun langDefaultsToZhCn() {
        runTest {
            val html = MarkdownExporter.toHtml("# 中文文档")
            assertContains(html, "<html lang=\"zh-CN\"")
        }
    }

    @Test
    fun langHeuristicMongolianDocument() {
        runTest {
        // vlr 块存在且含蒙文字符（强信号）→ mn-Mong-CN
        val html = MarkdownExporter.toHtml(mongolianDoc)
        assertContains(html, "<html lang=\"mn-Mong-CN\"")
        }
    }

    @Test
    fun langExplicitOverridesHeuristic() {
        runTest {
            val html = MarkdownExporter.toHtml(mongolianDoc, HtmlExportOptions(lang = "en"))
            assertContains(html, "<html lang=\"en\"")
        }
    }

    @Test
    fun langHeuristicThresholdMixed() {
        runTest {
        // 纯中文 + 拉丁（无蒙文）→ zh-CN
        val zhHtml = MarkdownExporter.toHtml("# 标题\n\n正文段落 Hello world 混合排版")
        assertContains(zhHtml, "<html lang=\"zh-CN\"")
        // 小比例蒙文（< 30%）且无 vlr 块 → 阈值不触发 → zh-CN
        val fewMongolian = "这是一个普通中文文档，正文以中文和英文混合排版为主 Hello world only。" +
            "其中仅含 ᠮᠣ 两个蒙文字符，不足以主导整页语言。"
        val mixedHtml = MarkdownExporter.toHtml(fewMongolian)
        assertContains(mixedHtml, "<html lang=\"zh-CN\"")
        }
    }

    // ─────────────── 回归确认 + 样式表 ───────────────

    @Test
    fun vlrTextIsEscaped() {
        val html = render("```vlr\n<b>$mongolianLine</b>\n```")
        assertContains(html, "&lt;b&gt;")
        assertFalse(html.contains("<b>$mongolianLine"))
    }

    @Test
    fun stylesheetVtextHasLineHeight() {
        val css = HtmlStyleSheet.generate(dark = false)
        val start = css.indexOf(".vtext, .vertical-text {")
        assertTrue(start >= 0, ".vtext 规则缺失")
        val rule = css.substring(start, css.indexOf('}', start))
        // 容器级列距默认（inline style 优先级更高，此条为无 inline 时的防御性默认）
        assertContains(rule, "line-height: 21.5px")
        assertContains(rule, "letter-spacing: 0")
    }

    @Test
    fun langHeuristicViaAstEntry() {
        // toHtml(document) 无源文本 → detectHtmlLang 走 AST 叶子 literal 收集分支
        runTest {
            val doc = parse(mongolianDoc)
            val html = MarkdownExporter.toHtml(doc)
            assertContains(html, "<html lang=\"mn-Mong-CN\"")
        }
    }
}
