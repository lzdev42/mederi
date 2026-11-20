package xyz.emuci.markdown.parser.html

import xyz.emuci.inkcompose.HtmlExportOptions
import xyz.emuci.inkcompose.HtmlExportPipeline
import xyz.emuci.inkcompose.HtmlExportTheme
import xyz.emuci.inkcompose.MarkdownExporter
import xyz.emuci.inkcompose.readBundledMermaidScript
import xyz.emuci.markdown.parser.MarkdownParser
import xyz.emuci.markdown.parser.flavour.ExtendedFlavour
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ST4 图表完整实现验收：mermaid div 化（<pre class="mermaid"> 渲染语义）+ 非 mermaid 源码降级
 * + bootstrap 按需注入（优先级链：customScript → hooks.diagram → 内置打包库）。
 *
 * 1-3 走 HtmlRenderer 直渲染腿（无平台依赖，全平台跑）；
 * 4-5、11 走 MarkdownExporter.toHtml（entry 集成）腿且依赖 JVM 打包库（守卫静默）；
 * 6-10 走 toHtml 腿但不依赖平台库（customScript / hooks / 开关分支，全平台可跑）。
 */
class HtmlExportDiagramTest {

    private val mermaidMd = "```mermaid\ngraph TD; A-->B;\n```"

    private fun parse(markdown: String) = MarkdownParser(ExtendedFlavour).parse(markdown)

    private fun render(
        markdown: String,
        hooks: HtmlExportHooks = HtmlExportPipeline.defaultHooks(),
    ): String = HtmlRenderer(hooks = hooks).render(parse(markdown))

    // ─────────────── mermaid div 化 ───────────────

    @Test
    fun mermaidBlockRendersMermaidPre() {
        val html = render(mermaidMd)
        assertContains(html, "class=\"mermaid\"")
        // 转义源码：--> 中 > 变 &gt;（mermaid 读 textContent，转义不影响图表语义）
        assertContains(html, "graph TD; A--&gt;B;")
        // 旧结构（pre>code.language-mermaid）不再存在
        assertFalse(html.contains("language-mermaid"))
        assertContains(html, "class=\"diagram mermaid\"")
    }

    @Test
    fun nonMermaidDiagramKeepsSourceCode() {
        val html = render("```plantuml\n@startuml\nA -> B\n@enduml\n```")
        assertContains(html, "class=\"diagram plantuml\"")
        // 非 mermaid 类型：保留 pre>code 源码展示（无 JS 渲染引擎）
        assertContains(html, "<code class=\"language-plantuml\">")
        assertContains(html, "A -&gt; B")
        assertFalse(html.contains("class=\"mermaid\""))
    }

    @Test
    fun diagramRequirementsCollected() {
        val doc = parse("$mermaidMd\n\n```plantuml\n@startuml\n@enduml\n```")
        val requirements = HtmlExportRequirements()
        HtmlRenderer(hooks = HtmlExportPipeline.defaultHooks(), requirements = requirements).render(doc)
        assertTrue(requirements.hasDiagram)
        assertContains(requirements.diagramTypes, "mermaid")
        assertContains(requirements.diagramTypes, "plantuml")
    }

    // ─────────────── 内置 bootstrap 按需注入（JVM 打包库守卫） ───────────────

    @Test
    fun bootstrapInjectsScriptBeforeBodyEnd() {
        runTest {
        // JVM-only 守卫：非 JVM 平台桩返回 null，静默通过（JVM 上真断言）
        if (readBundledMermaidScript() == null) return@runTest
        val html = MarkdownExporter.toHtml(mermaidMd)
        // 注意：内联 mermaid 库文本自身含 "</body>" 字样，须用 lastIndexOf 定位页面真实的 </body>
        val bodyEnd = html.lastIndexOf("</body>")
        assertTrue(bodyEnd >= 0)
        val lastScript = html.lastIndexOf("<script")
        // 注入点：bodyHtml 之后（最后一个 </div> 之后）、</body> 之前
        val bodyContentEnd = html.lastIndexOf("</div>")
        assertTrue(lastScript > bodyContentEnd)
        assertTrue(lastScript < bodyEnd)
        assertContains(html, "mermaid.initialize")
        assertContains(html, "mermaid.run()")
        assertContains(html, "__inkExportReady")
        assertContains(html, "securityLevel")
        }
    }

    @Test
    fun bootstrapRespectsDarkTheme() {
        runTest {
        if (readBundledMermaidScript() == null) return@runTest
        val dark = MarkdownExporter.toHtml(mermaidMd, HtmlExportOptions(theme = HtmlExportTheme.DARK))
        assertContains(dark, "theme: 'dark'")
        assertContains(dark, "darkMode: true")
        val light = MarkdownExporter.toHtml(mermaidMd)
        assertContains(light, "theme: 'default'")
        assertContains(light, "darkMode: false")
        }
    }

    @Test
    fun noBootstrapWithoutDiagram() {
        runTest {
        // 按需注入：文档无图表 → 零脚本（不依赖平台库，全平台可跑）
        val html = MarkdownExporter.toHtml("# 标题\n\n正文")
        assertFalse(html.contains("mermaid.initialize"))
        assertFalse(html.contains("__inkExportReady"))
        assertFalse(html.contains("<script"))
        }
    }

    @Test
    fun embedMermaidFalseSkipsBootstrap() {
        runTest {
        // 开关分支：有图表但 embedMermaid=false → 结构仍在（class="mermaid"），脚本不注入
        val html = MarkdownExporter.toHtml(mermaidMd, HtmlExportOptions(embedMermaid = false))
        assertContains(html, "class=\"mermaid\"")
        assertFalse(html.contains("mermaid.initialize"))
        assertFalse(html.contains("__inkExportReady"))
        }
    }

    // ─────────────── 优先级链：customScript / hooks.diagram ───────────────

    @Test
    fun customScriptUrlUsesScriptSrc() {
        runTest {
        // 用户逃生门：http(s) URL → <script src>；init/run/ready flag 仍由导出器注入
        val url = "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"
        val html = MarkdownExporter.toHtml(mermaidMd, HtmlExportOptions(mermaidScript = url))
        assertContains(html, "<script src=\"$url\"></script>")
        assertContains(html, "mermaid.initialize")
        assertContains(html, "mermaid.run()")
        assertContains(html, "__inkExportReady")
        }
    }

    @Test
    fun customInlineScriptUsedVerbatim() {
        runTest {
        // 非 http 开头视为内联 JS 全文，原样包裹 <script>…</script>
        val html = MarkdownExporter.toHtml(mermaidMd, HtmlExportOptions(mermaidScript = "window.foo=42;"))
        assertContains(html, "<script>window.foo=42;</script>")
        assertContains(html, "mermaid.initialize")
        assertContains(html, "mermaid.run()")
        assertContains(html, "__inkExportReady")
        }
    }

    @Test
    fun hookProvidedBootstrapWins() {
        runTest {
        // 优先级链：hooks.diagram 非 null → 宿主全权接管，不再注入内置 init/run
        val hooks = HtmlExportHooks(
            diagram = DiagramBootstrapProvider { "<script>window.customHook=true;</script>" }
        )
        val html = MarkdownExporter.toHtml(mermaidMd, HtmlExportOptions(hooks = hooks))
        assertContains(html, "window.customHook=true;")
        assertFalse(html.contains("mermaid.initialize"))
        }
    }

    // ─────────────── </script> 转义防护（JVM 打包库守卫） ───────────────

    @Test
    fun scriptTagEscapingInLibrary() {
        runTest {
        if (readBundledMermaidScript() == null) return@runTest
        val library = readBundledMermaidScript()!!
        val html = MarkdownExporter.toHtml(mermaidMd)
        // 内置模式页面恰有 2 个 </script>（库 script 闭合 + init script 闭合）；
        // 若库内含 </script> 则必须转义为 <\/script>（JS 等价转义），原始计数保持 2
        var rawCount = 0
        var idx = 0
        while (true) {
            val found = html.indexOf("</script>", idx)
            if (found < 0) break
            rawCount++
            idx = found + 1
        }
        assertEquals(2, rawCount)
        if (library.contains("</script>")) {
            assertContains(html, "<\\/script>")
        }
        }
    }
}
