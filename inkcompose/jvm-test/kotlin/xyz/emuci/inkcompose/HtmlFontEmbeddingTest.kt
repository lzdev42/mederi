package xyz.emuci.inkcompose

import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ST7 跨平台字体嵌入验收（JVM classloader 快路径 + 按需整合时序）：
 * 蒙古文字体按需（embedFonts × hasVerticalText）、KaTeX 按需（embedMathFonts × hasMath）、
 * toHtml suspend 化后输出结构完整、readBundledFontBase64 统一入口快路径与容错。
 */
class HtmlFontEmbeddingTest {

    private val vlrDoc = "```vlr\nᠮᠣᠩᠭᠣᠯ\n```"

    @Test
    fun vlrDocumentEmbedsMongolianFont() = runBlocking {
        val html = MarkdownExporter.toHtml(vlrDoc)
        assertContains(html, "@font-face")
        // @font-face 声明（family 名后紧跟分号，区别于 vtext inline 的逗号 fallback 链）
        assertContains(html, "font-family: 'Noto Sans Mongolian';")
        assertContains(html, "data:font/truetype")
    }

    @Test
    fun noVlrNoMongolianFont() = runBlocking {
        // 按需核心断言：无 vlr 竖排块 → 不嵌蒙古文 @font-face（即便 embedFonts=true）
        // 判据：@font-face 的 family 名后紧跟分号（vtext inline fallback 链是逗号续接）
        val html = MarkdownExporter.toHtml("# 标题\n正文")
        assertFalse(html.contains("font-family: 'Noto Sans Mongolian';"))
    }

    @Test
    fun embedFontsFalseSkipsAllFonts() = runBlocking {
        // vlr 块仍在（inline fallback 链以逗号续接含 'Noto Sans Mongolian'），但 @font-face 不嵌（分号形态不出现）
        val html = MarkdownExporter.toHtml(vlrDoc, HtmlExportOptions(embedFonts = false))
        assertFalse(html.contains("font-family: 'Noto Sans Mongolian';"))
    }

    @Test
    fun mathDocumentEmbedsKaTeXOnDemand() = runBlocking {
        val html = MarkdownExporter.toHtml("\$\$x^2\$\$")
        assertContains(html, "@font-face")
        assertContains(html, "font-family: 'KaTeX_Main'")
    }

    @Test
    fun noMathNoKaTeX() = runBlocking {
        val html = MarkdownExporter.toHtml("# t")
        assertFalse(html.contains("KaTeX_"))
    }

    @Test
    fun suspendToHtmlProducesCompletePage() = runBlocking {
        val html = MarkdownExporter.toHtml("# Hi\n\npara")
        assertContains(html, "<!DOCTYPE html>")
        assertContains(html, "</html>")
        assertContains(html, "<h1")
    }

    @Test
    fun readBundledFontBase64JvmFastPath() = runBlocking {
        val base64 = readBundledFontBase64("NotoSansMongolian-Regular.ttf")
        assertTrue(!base64.isNullOrBlank(), "JVM classloader 快路径应读到打包蒙古文字体")
        val bytes = Base64.getDecoder().decode(base64!!)
        assertTrue(bytes.isNotEmpty() && bytes.size > 1000, "解码后字节数应非平凡（>1KB）")
    }

    @Test
    fun readBundledFontBase64UnknownFile() = runBlocking {
        // 容错：不存在的字体文件 → null（不抛异常）
        assertEquals(null, readBundledFontBase64("nonexistent.ttf"))
    }
}
