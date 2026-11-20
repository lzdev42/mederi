package xyz.emuci.markdown.parser.html

import xyz.emuci.inkcompose.HtmlExportOptions
import xyz.emuci.inkcompose.HtmlExportPipeline
import xyz.emuci.inkcompose.MarkdownExporter
import xyz.emuci.inkcompose.readBundledFontBase64
import xyz.emuci.markdown.parser.MarkdownParser
import xyz.emuci.markdown.parser.flavour.ExtendedFlavour
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ST3 数学完整实现验收：MathML 结构对齐（块级 / 行内 / 降级）+ KaTeX 字体按需嵌入。
 *
 * 1-3 走 HtmlRenderer 直渲染腿；4-8 走 MarkdownExporter.toHtml（entry 集成）腿。
 */
class HtmlExportMathTest {

    private fun parse(markdown: String) = MarkdownParser(ExtendedFlavour).parse(markdown)

    private fun render(
        markdown: String,
        hooks: HtmlExportHooks = HtmlExportPipeline.defaultHooks(),
    ): String = HtmlRenderer(hooks = hooks).render(parse(markdown))

    // ─────────────── MathML 结构对齐 ───────────────

    @Test
    fun displayMathRendersMathMlFraction() {
        val html = render("\$\$\\frac{a}{b}\$\$")
        assertContains(html, "<math")
        assertContains(html, "display=\"block\"")
        assertContains(html, "<mfrac")
        // 外层容器
        assertContains(html, "math-display")
        assertContains(html, "http://www.w3.org/1998/Math/MathML")
    }

    @Test
    fun inlineMathRendersMathMlSuperscript() {
        val html = render("行内 \$x^2\$ 公式")
        assertContains(html, "display=\"inline\"")
        assertContains(html, "<msup")
        assertContains(html, "<math")
        // 外层容器
        assertContains(html, "math-inline")
    }

    @Test
    fun mathHookNullFallsBackInFullPage() {
        runTest {
        val html = MarkdownExporter.toHtml(
            "\$\$x^2\$\$",
            HtmlExportOptions(hooks = HtmlExportHooks(math = MathHtmlProvider { _, _ -> null })),
        )
        // 完整页面 + 降级纯文本 + 无 MathML
        assertContains(html, "<!DOCTYPE html>")
        assertContains(html, "</html>")
        assertContains(html, "\\[")
        assertContains(html, "\\]")
        assertFalse(html.contains("<math"))
        }
    }

    @Test
    fun brokenLatexPipelineChainKeepsPageIntact() {
        runTest {
        // Pipeline 真实链路：defaultHooks + 截断的 \frac（缺右括号），解析器容忍性由链路兜底
        val html = MarkdownExporter.toHtml("\$\$\\frac{\$\$")
        assertContains(html, "<!DOCTYPE html>")
        assertContains(html, "</html>")
        assertContains(html, "math-display")
        if (!html.contains("<math")) {
            // 未产出 MathML 时必须走降级纯文本
            assertContains(html, "\\[")
        }
        }
    }

    // ─────────────── KaTeX 字体按需嵌入 ───────────────

    @Test
    fun mathFontsEmbeddedWhenDocumentHasMath() {
        runTest {
        // JVM-only 守卫：非 JVM 平台桩返回 null，静默通过（JVM 上真断言）
        if (readBundledFontBase64(KATEX_FONT_VARIANTS.first().resourceFileName) == null) return@runTest
        val html = MarkdownExporter.toHtml("\$\$x^2\$\$", HtmlExportOptions(embedMathFonts = true))
        assertContains(html, "@font-face")
        assertContains(html, "font-family: 'KaTeX_Main'")
        assertContains(html, "font-family: 'KaTeX_Math'")
        // 多变体描述符：同 family 不同 weight/style
        assertContains(html, "font-weight: bold")
        assertContains(html, "font-style: italic")
        assertContains(html, "data:font/truetype")
        }
    }

    @Test
    fun mathFontsNotEmbeddedWithoutMath() {
        runTest {
        // 按需：文档无数学 → 零 KaTeX 嵌入（开关打开也零嵌入）
        val html = MarkdownExporter.toHtml("# 标题\n\n正文", HtmlExportOptions(embedMathFonts = true))
        assertFalse(html.contains("KaTeX_"))
        }
    }

    @Test
    fun mathFontsDisabledWhenOptionOff() {
        runTest {
        // 开关分支：文档有数学但 embedMathFonts=false → 零嵌入
        val html = MarkdownExporter.toHtml("\$\$x^2\$\$", HtmlExportOptions(embedMathFonts = false))
        assertFalse(html.contains("KaTeX_"))
        // 数学本体仍渲染（只是不嵌字体）：MathML 输出或降级纯文本二选一
        if (!html.contains("<math")) {
            assertContains(html, "\\[")
        } else {
            assertContains(html, "display=\"block\"")
        }
        }
    }

    // ─────────────── two-pass 需求探测 + 样式对齐 ───────────────

    @Test
    fun requirementsProbeCollectsHasMathBeforeFullPage() {
        val doc = parse("\$\$x^2\$\$")
        val requirements = HtmlExportRequirements()
        HtmlRenderer(hooks = HtmlExportPipeline.defaultHooks(), requirements = requirements).render(doc)
        assertTrue(requirements.hasMath)
    }

    @Test
    fun displayMathStyleAlignsWithComposeTheme() {
        runTest {
            val html = MarkdownExporter.toHtml("正文")
            assertContains(html, ".math-display {")
            // 与 Compose 端 mathBlockBackground 对齐：引用代码块背景变量（light #f6f8fa / dark #161b22）
            assertContains(html, "background-color: var(--md-code-bg)")
            assertContains(html, ".math-inline {")
            assertContains(html, "font-size: 1.15em")
        }
    }
}
