package xyz.emuci.inkcompose

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.kbrowser.webview.JcefChecker
import xyz.kbrowser.webview.KBPdf
import java.io.File
import java.util.Base64
import kotlin.concurrent.Volatile

@Volatile
private var cachedFontBase64ByFile: MutableMap<String, String>? = null

@Volatile
private var cachedMermaidScript: String? = null

@Volatile
private var cachedMermaidScriptLoaded = false

/**
 * JVM classloader 快路径：按文件名读取 composeResources 下打包的 ttf（蒙古文 / KaTeX 统一入口）并转 Base64。
 * 资源缺失时缓存空串并返回 null，避免重复 IO。suspend 签名但同步即时返回（无真实挂起点）。
 */
internal actual suspend fun readBundledFontBase64(fontFileName: String): String? {
    cachedFontBase64ByFile?.get(fontFileName)?.takeIf { it.isNotEmpty() }?.let { return it }
    return synchronized(MarkdownExporter::class.java) {
        val cache = cachedFontBase64ByFile
            ?: mutableMapOf<String, String>().also { cachedFontBase64ByFile = it }
        cache.getOrPut(fontFileName) {
            val stream = MarkdownExporter::class.java.classLoader.getResourceAsStream(
                "composeResources/xyz.emuci.inkcompose.resources/font/$fontFileName"
            ) ?: MarkdownExporter::class.java.getResourceAsStream(
                "/composeResources/xyz.emuci.inkcompose.resources/font/$fontFileName"
            )
            stream?.use { Base64.getEncoder().encodeToString(it.readBytes()) } ?: ""
        }.takeIf { it.isNotEmpty() }
    }
}

/**
 * JVM classloader 快路径：读取 jvm/resources 下打包的 mermaid.min.js 全文。
 * 资源路径 /mermaid.min.js（SingleMermaidWorker 同机制）；读取失败缓存 null，
 * 避免重复 IO（cachedMermaidScriptLoaded 区分"未加载"与"加载得 null"）。
 */
internal actual fun readBundledMermaidScript(): String? {
    if (cachedMermaidScriptLoaded) return cachedMermaidScript
    return synchronized(MarkdownExporter::class.java) {
        if (!cachedMermaidScriptLoaded) {
            val stream = MarkdownExporter::class.java.classLoader.getResourceAsStream("mermaid.min.js")
                ?: MarkdownExporter::class.java.getResourceAsStream("/mermaid.min.js")
            cachedMermaidScript = stream?.use { it.bufferedReader().readText() }
                ?.takeIf { it.isNotBlank() }
            cachedMermaidScriptLoaded = true
        }
        cachedMermaidScript
    }
}

internal actual suspend fun exportMarkdownHtmlToPdfPlatform(
    html: String,
    outputPath: String,
    options: PdfExportOptions,
): Result<String> = withContext(Dispatchers.IO) {
    if (!JcefChecker.isJcefAvailable) {
        return@withContext Result.failure(
            IllegalStateException("JCEF runtime is not available in current environment.")
        )
    }

    val outputFile = File(outputPath).absoluteFile
    outputFile.parentFile?.mkdirs()

    try {
        // KBrowser 0.2.2 KBPdf：内建等待（load + fonts + mermaid SVG + images + fonts.ready），
        // 自建离屏 OSR browser，不消费注入的 options.browser（保留参数为向后兼容）。
        // fonts 传空 Map：HTML 已内嵌 data:font/truetype @font-face（ST3/ST5/ST7 按需嵌入）。
        val pdfBytes = KBPdf.fromHtml(html)
        outputFile.writeBytes(pdfBytes)
        if (outputFile.exists() && outputFile.length() > 0L) {
            Result.success(outputFile.absolutePath)
        } else {
            Result.failure(RuntimeException("KBPdf produced an empty PDF for ${outputFile.absolutePath}"))
        }
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
