package xyz.emuci.inkcompose

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.kbrowser.webview.JcefChecker
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownExporterTest {

    @Test
    fun should_export_html_with_vlr_and_styles() = runBlocking {
        val md = """
            # ᠮᠣᠩᠭᠣᠯ ᠪᠢᠴᠢᠭ
            
            这是正文段落。
            
            ```vlr {height=240dp fontSize=18sp wrap=true}
            ᠮᠣᠩᠭᠣᠯ ᠪᠢᠴᠢᠭ ᠲᠡᠦᠬᠡ
            ```
        """.trimIndent()

        val html = MarkdownExporter.toHtml(md, HtmlExportOptions(title = "Mongolian Test"))
        assertTrue(html.contains("<title>Mongolian Test</title>"))
        assertTrue(html.contains("writing-mode: vertical-lr"))
        assertTrue(html.contains("height: 240px"))
        assertTrue(html.contains("font-size: 18px"))
        assertTrue(html.contains("white-space: normal"))
        assertTrue(html.contains("Noto Sans Mongolian"))
    }

    @Test
    fun should_export_pdf_with_mermaid_via_kbpdf() = runBlocking {
        assumeTrue("PDF export requires JCEF", JcefChecker.isJcefAvailable)
        if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
            assumeTrue("Linux JCEF uses X11 and requires DISPLAY", !System.getenv("DISPLAY").isNullOrBlank())
        }

        val testStorageDir = File(System.getProperty("java.io.tmpdir"), "inkcompose_test_kbrowser").absolutePath
        File(testStorageDir).mkdirs()
        KBrowser.initializeConfig(testStorageDir, useOsr = true)
        initializeKBrowser()

        val md = "# 标题\n\n正文段落。\n\n```mermaid\ngraph TD; A-->B;\n```\n"
        val tmpPdf = File.createTempFile("inkcompose_export_kbpdf_", ".pdf")
        tmpPdf.deleteOnExit()
        val result = MarkdownExporter.toPdf(md, tmpPdf.absolutePath, PdfExportOptions(title = "Mermaid PDF"))
        assertTrue(result.isSuccess, "PDF export should succeed: ${result.exceptionOrNull()?.message}")
        assertTrue(tmpPdf.length() > 0, "PDF should not be empty")
        // PDF 魔数：%PDF
        val head = tmpPdf.inputStream().use { it.readNBytes(4) }.toString(Charsets.US_ASCII)
        assertTrue(head.startsWith("%PDF"), "output must be a PDF file, head=$head")
    }

    @Test
    fun should_export_pdf_when_jcef_available() = runBlocking {
        assumeTrue("PDF export requires JCEF", JcefChecker.isJcefAvailable)
        if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
            assumeTrue("Linux JCEF uses X11 and requires DISPLAY", !System.getenv("DISPLAY").isNullOrBlank())
        }

        val testStorageDir = File(System.getProperty("java.io.tmpdir"), "inkcompose_test_kbrowser").absolutePath
        File(testStorageDir).mkdirs()
        KBrowser.initializeConfig(testStorageDir, useOsr = true)
        initializeKBrowser()

        val md = """
            # PDF 导出测试
            
            传统蒙古文竖排段落：
            
            ```vlr {height=300dp fontSize=16sp wrap=false}
            ᠮᠣᠩᠭᠣᠯ ᠪᠢᠴᠢᠭ ᠪᠣᠯ ᠮᠣᠩᠭᠣᠯ ᠦᠨᠳᠦᠰᠦᠲᠡᠨ ᠦ ᠡᠷᠲᠡᠨ ᠡᠴᠡ ᠤᠯᠠᠮᠵᠢᠯᠠᠵᠤ ᠢᠷᠡᠭᠰᠡᠨ ᠪᠢᠴᠢᠭ ᠮᠥᠨ᠃
            ```
            
            代码块嵌套：
            
            ```kotlin
            fun main() {
                println("Hello from Mederi InkCompose!")
            }
            ```
        """.trimIndent()

        val tmpPdf = File.createTempFile("inkcompose_export_test_", ".pdf")
        tmpPdf.deleteOnExit()

        val result = MarkdownExporter.toPdf(md, tmpPdf.absolutePath, PdfExportOptions(browser = KBrowser))
        assertTrue(result.isSuccess, "PDF export should succeed: ${result.exceptionOrNull()?.message}")

        assertTrue(tmpPdf.exists(), "PDF file must exist")
        assertTrue(tmpPdf.length() > 1000L, "PDF file size must be non-trivial (>1KB)")

        // Check PDF header
        val header = tmpPdf.inputStream().use { it.readNBytes(5) }
        assertEquals("%PDF-", String(header), "File should be a valid PDF starting with %PDF-")
    }
}
