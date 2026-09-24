package xyz.emuci.inkcompose

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.misc.CefPdfPrintSettings
import xyz.kbrowser.webview.JcefChecker
import xyz.kbrowser.webview.JvmWebView
import xyz.kbrowser.webview.KBPage
import xyz.kbrowser.webview.KBrowser
import java.io.File
import java.util.Base64
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

@Volatile
private var cachedFontBase64: String? = null

internal actual fun getBundledMongolianFontBase64(): String? {
    cachedFontBase64?.let { return it }
    return synchronized(MarkdownExporter::class.java) {
        cachedFontBase64 ?: run {
            val fontStream = MarkdownExporter::class.java.classLoader.getResourceAsStream(
                "composeResources/xyz.emuci.inkcompose.resources/font/NotoSansMongolian-Regular.ttf"
            ) ?: MarkdownExporter::class.java.getResourceAsStream(
                "/composeResources/xyz.emuci.inkcompose.resources/font/NotoSansMongolian-Regular.ttf"
            )
            fontStream?.use {
                val encoded = Base64.getEncoder().encodeToString(it.readBytes())
                cachedFontBase64 = encoded
                encoded
            }
        }
    }
}

private val pdfExportMutex = Mutex()

internal actual suspend fun exportMarkdownHtmlToPdfPlatform(
    html: String,
    outputPath: String,
    options: PdfExportOptions,
): Result<String> = withContext(Dispatchers.IO) {
    val browser = options.browser
    if (browser == null) {
        return@withContext Result.failure(
            IllegalStateException("KBrowser runtime is not provided or initialized. Cannot export PDF.")
        )
    }

    if (!JcefChecker.isJcefAvailable) {
        return@withContext Result.failure(
            IllegalStateException("JCEF runtime is not available in current environment.")
        )
    }

    val outputFile = File(outputPath).absoluteFile
    outputFile.parentFile?.mkdirs()

    pdfExportMutex.withLock {
        var page: KBPage? = null
        try {
            page = withContext(Dispatchers.Main) {
                browser.newPage(viewportWidth = 1200, viewportHeight = 1600)
            }
            val webView = page.webView as? JvmWebView
                ?: return@withLock Result.failure(IllegalStateException("Page webView is not JvmWebView"))
            val cefBrowser = webView.browser.getCefBrowser()

            val loadDeferred = CompletableDeferred<Unit>()
            val loadHandler = object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (frame?.isMain == true) {
                        loadDeferred.complete(Unit)
                    }
                }

                override fun onLoadError(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    errorCode: CefLoadHandler.ErrorCode?,
                    errorText: String?,
                    failedUrl: String?,
                ) {
                    if (frame?.isMain == true) {
                        loadDeferred.completeExceptionally(
                            RuntimeException("Failed to load HTML: $errorText ($errorCode)")
                        )
                    }
                }
            }

            webView.browser.myCefClient.addLoadHandler(loadHandler, cefBrowser)
            try {
                withContext(Dispatchers.Main) {
                    webView.loadHtml(html)
                }

                val loadOk = withTimeoutOrNull(20.seconds) {
                    loadDeferred.await()
                }
                if (loadOk == null) {
                    return@withLock Result.failure(
                        RuntimeException("Timed out waiting for HTML page to load.")
                    )
                }

                // 稍微延迟等待字体与样式稳定布局
                delay(250)

                val printDeferred = CompletableDeferred<Boolean>()
                val settings = CefPdfPrintSettings().apply {
                    landscape = options.landscape
                    print_background = options.printBackground
                    prefer_css_page_size = true
                }

                cefBrowser.printToPDF(outputFile.absolutePath, settings) { _: String?, ok: Boolean ->
                    printDeferred.complete(ok)
                }

                val printOk = withTimeoutOrNull(20.seconds) {
                    printDeferred.await()
                } ?: false

                if (printOk && outputFile.exists() && outputFile.length() > 0L) {
                    Result.success(outputFile.absolutePath)
                } else {
                    Result.failure(RuntimeException("CEF printToPDF failed for ${outputFile.absolutePath}"))
                }
            } finally {
                webView.browser.myCefClient.removeLoadHandler(loadHandler, cefBrowser)
            }
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            try {
                withContext(Dispatchers.Main) {
                    page?.close()
                }
            } catch (_: Throwable) {}
        }
    }
}
