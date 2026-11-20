package xyz.emuci.markdown.parser.html

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
 * ST6 扩展块完整实现验收：admonition 色板 / custom-container / tab 栏 / columns flex /
 * TOC 两遍扫描真渲染 / spoiler hover+print / figure 回归。
 *
 * Renderer 腿为主（HtmlRenderer.render），末尾附 Exporter 集成腿（MarkdownExporter.toHtml 整页）。
 */
class HtmlExportExtendedBlocksTest {

    private fun parse(markdown: String) = MarkdownParser(ExtendedFlavour).parse(markdown)

    private fun render(markdown: String): String = HtmlRenderer().render(parse(markdown))

    private fun count(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index != -1) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    // ─────────────── admonition ───────────────

    @Test
    fun admonitionNoteRendersTypeClassAndTitle() {
        val html = render("> [!NOTE] 注意标题\n> 正文内容")
        assertContains(html, "class=\"admonition note\"")
        assertContains(html, "class=\"admonition-title\"")
        assertContains(html, "注意标题")
        assertContains(html, "正文内容")
    }

    @Test
    fun admonitionFiveTypesHaveColorRules() {
        val light = HtmlStyleSheet.generate(dark = false)
        // 5 类型 light 色板（与 Compose MarkdownTheme.defaultAdmonitionStyles 逐值对齐）
        assertContains(light, ".admonition.note { border-left-color: #0969da; background-color: #ddf4ff; }")
        assertContains(light, ".admonition.note .admonition-title { color: #0969da; }")
        assertContains(light, ".admonition.tip { border-left-color: #1a7f37; background-color: #dcffe4; }")
        assertContains(light, ".admonition.tip .admonition-title { color: #1a7f37; }")
        assertContains(light, ".admonition.important { border-left-color: #8250df; background-color: #fbefff; }")
        assertContains(light, ".admonition.important .admonition-title { color: #8250df; }")
        assertContains(light, ".admonition.warning { border-left-color: #bf8700; background-color: #fff8c5; }")
        assertContains(light, ".admonition.warning .admonition-title { color: #bf8700; }")
        assertContains(light, ".admonition.caution { border-left-color: #cf222e; background-color: #ffebe9; }")
        assertContains(light, ".admonition.caution .admonition-title { color: #cf222e; }")
        // 深色主题覆盖（data-theme=dark 选择器，色值对齐 darkAdmonitionStyles）
        val dark = HtmlStyleSheet.generate(dark = true)
        assertContains(dark, ".admonition.note { border-left-color: #4493f8; background-color: #0d1d30; }")
        assertContains(dark, ".admonition.tip { border-left-color: #3fb950; background-color: #0d2818; }")
        assertContains(dark, ".admonition.important { border-left-color: #ab7df8; background-color: #1b1030; }")
        assertContains(dark, ".admonition.warning { border-left-color: #d29922; background-color: #2a1f00; }")
        assertContains(dark, ".admonition.caution { border-left-color: #f85149; background-color: #300c0c; }")
        // 深色规则必须挂在 data-theme=dark 选择器下（而非覆盖 light 规则）
        assertContains(dark, "html[data-theme=\"dark\"] .admonition.warning { border-left-color: #d29922")
    }

    @Test
    fun admonitionIconsMatchCompose() {
        val light = HtmlStyleSheet.generate(dark = false)
        assertContains(light, ".admonition.note .admonition-title::before { content: \"ℹ️ \"; }")
        assertContains(light, ".admonition.tip .admonition-title::before { content: \"💡 \"; }")
        assertContains(light, ".admonition.important .admonition-title::before { content: \"❗ \"; }")
        assertContains(light, ".admonition.warning .admonition-title::before { content: \"⚠️ \"; }")
        assertContains(light, ".admonition.caution .admonition-title::before { content: \"🔴 \"; }")
    }

    // ─────────────── custom container ───────────────

    @Test
    fun customContainerRendersTypeAndClasses() {
        val html = render(":::warning \"My Title\"\ncontent\n:::")
        assertContains(html, "class=\"custom-container warning\"")
        assertContains(html, "class=\"custom-container-title\"")
        assertContains(html, "My Title")
        // CSS 边框条目
        assertContains(HtmlStyleSheet.generate(dark = false), ".custom-container {")
        assertContains(HtmlStyleSheet.generate(dark = false), "border: 1px solid var(--md-border-color)")
    }

    // ─────────────── tab ───────────────

    @Test
    fun tabBlockRendersBarAndAllItems() {
        val html = render("=== \"T1\"\n    One\n\n=== \"T2\"\n    Two")
        assertContains(html, "class=\"tab-block\"")
        assertContains(html, "role=\"tablist\"")
        assertContains(html, "class=\"tab-bar\"")
        // 2 个视觉标签（解析器的非 TabItem 子节点/空 title 不产生标签）
        assertEquals(2, count(html, "class=\"tab-label\""))
        assertContains(html, ">T1</span>")
        assertContains(html, ">T2</span>")
        // 2 个全展开的内容项，带 data-tab-title 与 aria-selected
        assertEquals(2, count(html, "class=\"tab-item\""))
        assertContains(html, "data-tab-title=\"T1\"")
        assertContains(html, "data-tab-title=\"T2\"")
        assertEquals(2, count(html, "aria-selected=\"true\""))
        // 内容全展开堆叠
        assertContains(html, "One")
        assertContains(html, "Two")
    }

    @Test
    fun tabPrintCssExpandsContent() {
        val css = HtmlStyleSheet.generate(dark = false)
        assertContains(css, ".tab-block {")
        assertContains(css, ".tab-bar {")
        assertContains(css, ".tab-item {")
        assertContains(css, "@media print {")
        // 纯 CSS 静态呈现：渲染输出不含 JS 交互脚本
        val html = render("=== \"T1\"\n    One")
        assertFalse(html.contains("<script"), "tab 渲染输出不得含 JS")
    }

    // ─────────────── columns ───────────────

    @Test
    fun columnsLayoutFlexRow() {
        val css = HtmlStyleSheet.generate(dark = false)
        assertContains(css, ".columns-layout { display: flex; flex-direction: row; gap: 8px; margin: 16px 0; }")
        assertContains(css, ".column-item { flex: 1 1 0; min-width: 0; }")
    }

    @Test
    fun columnExplicitWidthUsesFlexBasis() {
        val html = render(":::columns\n:::column{.w-30}\nleft\n:::column\nright\n:::")
        assertContains(html, "class=\"columns-layout\"")
        assertContains(html, "style=\"flex: 0 0 30%\"")
        // 显式宽度列固定 flex-basis；无 width 列无 inline style（由样式表 flex:1 1 0 均分）
        assertEquals(2, count(html, "class=\"column-item\""))
        assertEquals(1, count(html, "style="))
    }

    // ─────────────── TOC ───────────────

    @Test
    fun tocRendersRealNavWithAnchors() {
        val html = render("# A\n\n## B\n\n[TOC]\n\n# C")
        assertContains(html, "<nav class=\"table-of-contents\">")
        assertContains(html, "class=\"toc-title\"")
        assertContains(html, "Table of Contents")
        // 两遍扫描：TOC 之后出现的 heading C 也在目录内
        assertContains(html, "href=\"#a\"")
        assertContains(html, "href=\"#b\"")
        assertContains(html, "href=\"#c\"")
        // 嵌套：B（h2）在 A 的 li 内层 ul 中
        assertContains(html, "<li class=\"toc-level-1\"><a href=\"#a\">A</a><ul>")
        assertContains(html, "<li class=\"toc-level-2\"><a href=\"#b\">B</a></li>")
        // 不输出占位文本
        assertFalse(html.contains("[TOC]"))
    }

    @Test
    fun tocRespectsMinMaxDepth() {
        val html = render("# A\n\n## B\n\n### C\n\n[TOC]\n:depth=2-6")
        assertContains(html, "href=\"#b\"")
        assertContains(html, "href=\"#c\"")
        // minDepth=2 → h1（A）不进目录
        assertFalse(html.contains("href=\"#a\""))
    }

    @Test
    fun tocExcludesIds() {
        val html = render("# A {#aa}\n\n## B {#ignore-id}\n\n[TOC]\n:exclude=ignore-id")
        assertContains(html, "href=\"#aa\"")
        assertFalse(html.contains("href=\"#ignore-id\""))
    }

    @Test
    fun tocDescOrderReverses() {
        val html = render("# A\n\n## B\n\n[TOC]\n:order=desc")
        val posB = html.indexOf("href=\"#b\"")
        val posA = html.indexOf("href=\"#a\"")
        assertTrue(posB != -1 && posA != -1)
        assertTrue(posB < posA, "order=desc 时 B 应排在 A 之前，实际 posB=$posB posA=$posA")
    }

    @Test
    fun tocEmptyWhenAllFiltered() {
        val html = render("# A\n\n[TOC]\n:depth=5-6")
        assertContains(html, "<nav class=\"table-of-contents\"></nav>")
        assertFalse(html.contains("[TOC]"))
        assertFalse(html.contains("toc-list"))
    }

    // ─────────────── spoiler / figure ───────────────

    @Test
    fun spoilerHoverAndPrintReveal() {
        val css = HtmlStyleSheet.generate(dark = false)
        // 旧遮罩条目保留（ST1）
        assertContains(css, "color: transparent")
        assertContains(css, "background-color: var(--md-spoiler-bg)")
        // 新增：hover 揭示 + 打印始终显示
        assertContains(css, ".spoiler:hover { color: inherit; background-color: transparent; }")
        assertContains(css, "@media print { .spoiler { color: inherit; background-color: transparent; } }")
    }

    @Test
    fun figureStylesComplete() {
        val css = HtmlStyleSheet.generate(dark = false)
        assertContains(css, "figure {")
        assertContains(css, "margin: 16px 0;")
        assertContains(css, "figcaption {")
        assertContains(css, "font-size: 11px;")
    }

    // ─────────────── Exporter 集成腿 ───────────────

    @Test
    fun exporterFullPageContainsExtendedBlockStyles() {
        val md = "> [!WARNING] 危险\n> 内容\n\n:::columns\n:::column{.w-50}\n左\n:::column\n右\n:::"
        runTest {
        val html = MarkdownExporter.toHtml(md)
        val styleStart = html.indexOf("<style>")
        val styleEnd = html.indexOf("</style>")
        assertTrue(styleStart != -1 && styleEnd > styleStart, "整页必须内嵌 <style>")
        val style = html.substring(styleStart, styleEnd)
        assertContains(style, ".admonition.warning { border-left-color: #bf8700")
        assertContains(style, ".columns-layout { display: flex")
        // body 侧扩展块结构
        assertContains(html, "class=\"admonition warning\"")
        assertContains(html, "class=\"columns-layout\"")
        }
    }
}
