package xyz.emuci.inkcompose

import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.kbrowser.webview.JcefChecker
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarkdownExporterTest {

    @Test
    fun should_export_html_with_vlr_and_styles() {
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
    fun should_export_pdf_when_jcef_available() = runBlocking {
        if (!JcefChecker.isJcefAvailable) {
            println("Skipping should_export_pdf_when_jcef_available because JCEF is not available")
            return@runBlocking
        }

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

        val result = MarkdownExporter.toPdf(md, tmpPdf.absolutePath)
        assertTrue(result.isSuccess, "PDF export should succeed: ${result.exceptionOrNull()?.message}")

        assertTrue(tmpPdf.exists(), "PDF file must exist")
        assertTrue(tmpPdf.length() > 1000L, "PDF file size must be non-trivial (>1KB)")

        // Check PDF header
        val header = tmpPdf.inputStream().use { it.readNBytes(5) }
        assertEquals("%PDF-", String(header), "File should be a valid PDF starting with %PDF-")
    }
}
