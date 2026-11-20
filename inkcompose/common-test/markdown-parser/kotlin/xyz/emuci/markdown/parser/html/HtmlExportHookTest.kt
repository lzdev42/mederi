package xyz.emuci.markdown.parser.html

import xyz.emuci.inkcompose.HtmlExportPipeline
import xyz.emuci.markdown.parser.MarkdownParser
import xyz.emuci.markdown.parser.flavour.ExtendedFlavour
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlExportHookTest {

    private fun parse(markdown: String) = MarkdownParser(ExtendedFlavour).parse(markdown)

    @Test
    fun mathHookInjectsMathMl() {
        val doc = parse("\$\$x^2\$\$")
        val html = HtmlRenderer(
            hooks = HtmlExportHooks(
                math = MathHtmlProvider { _, _ -> "<math><msup><mi>x</mi><mn>2</mn></msup></math>" },
            ),
        ).render(doc)
        assertContains(html, "<math")
        assertContains(html, "math-display")
    }

    @Test
    fun noHookFallsBackToLiteralText() {
        val doc = parse("\$\$x^2\$\$")
        val html = HtmlRenderer().render(doc)
        assertContains(html, "\\[")
        assertFalse(html.contains("<math"))
    }

    @Test
    fun mathHookReturningNullFallsBack() {
        val doc = parse("\$\$x^2\$\$")
        val html = HtmlRenderer(
            hooks = HtmlExportHooks(math = MathHtmlProvider { _, _ -> null }),
        ).render(doc)
        assertContains(html, "\\[")
        assertFalse(html.contains("<math"))
    }

    @Test
    fun inlineMathUsesMathInlineSpan() {
        val doc = parse("行内 \$x\$ 公式")
        val html = HtmlRenderer(
            hooks = HtmlExportHooks(
                math = MathHtmlProvider { _, _ -> "<math><mi>x</mi></math>" },
            ),
        ).render(doc)
        assertContains(html, "math-inline")
        assertContains(html, "<math")
    }

    @Test
    fun requirementsCollectsBlockKinds() {
        val doc = parse(
            "```mermaid\ngraph TD; A-->B;\n```\n\n" +
                "```vlr\n\u1806\u182d\u182d\n```\n\n" +
                "\$\$x^2\$\$",
        )
        val requirements = HtmlExportRequirements()
        HtmlRenderer(requirements = requirements).render(doc)
        assertTrue(requirements.hasMath)
        assertTrue(requirements.hasDiagram)
        assertTrue(requirements.hasVerticalText)
        assertContains(requirements.diagramTypes, "mermaid")
    }

    @Test
    fun pipelineDefaultMathHookRendersRealMathMl() {
        val math = HtmlExportPipeline.defaultHooks().math!!
        val valid = checkNotNull(math.toMathMl("\\frac{1}{2}", true))
        assertContains(valid, "<mfrac")
        // 非法输入：不抛异常即可（可能产出部分 AST，也可能降级 null）
        val broken = runCatching { math.toMathMl("this is \\broken \\{", true) }
        assertTrue(broken.isSuccess)
        val brokenResult = broken.getOrNull()
        if (brokenResult != null) {
            assertContains(brokenResult, "<math")
        }
        // 显式消费可空结果，避免平台告警
        assertTrue(brokenResult == null || brokenResult.isNotEmpty())
    }
}
