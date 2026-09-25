package xyz.emuci.diagram.mermaid

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.kbrowser.webview.JcefChecker
import xyz.kbrowser.webview.KBWebView
import xyz.kbrowser.webview.KBrowser
import kotlinx.coroutines.delay
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds

internal data class MermaidWorkerResult(
    val bitmap: ImageBitmap?,
    val error: String?,
)

/**
 * 全应用唯一公用、永不销毁的离屏 Mermaid 渲染工作者（基于 JCEF KBWebView）。
 *
 * 核心保证：
 * 1. 全局单例常驻：全软件仅持有一个后台不可见的 KBWebView 实例，启动后永不反复创建和销毁；
 * 2. 严格串行执行：内部使用协程 Mutex 排队，每次只渲染一张图，求稳不出错，绝无多进程/多实例争抢与闪退；
 * 3. 磁盘缓存贯通：渲染完成后立即通过 MermaidDiskCache 原子安全落盘，并生成 ImageBitmap。
 */
object SingleMermaidWorker {

    private val queueMutex = Mutex()
    private val initMutex = Mutex()

    @Volatile
    private var webView: KBWebView? = null

    @Volatile
    private var isReady = false

    @Volatile
    private var attachedBrowser: KBrowser? = null

    /**
     * 由外部（宿主应用）注入已初始化就绪的 KBrowser 全局单例。
     * 未注入前，Worker 处于不可用状态，不会尝试自行初始化。
     */
    fun attachBrowser(browser: KBrowser) {
        this.attachedBrowser = browser
        println("[SingleMermaidWorker] KBrowser attached successfully.")
    }

    fun isBrowserAttached(): Boolean = attachedBrowser != null

    @Volatile
    private var pendingDeferred: CompletableDeferred<WorkerPayload>? = null

    private var renderSequence = 0L

    private data class WorkerPayload(
        val state: Int,
        val base64: String?,
        val error: String?,
    )

    /**
     * 确保全局唯一的后台 KBWebView 已经就绪（全生命周期仅初始化一次，永不销毁）。
     */
    private suspend fun ensureWorkerReady(): Boolean {
        if (isReady && webView != null) return true
        return initMutex.withLock {
            if (isReady && webView != null) return@withLock true

            var browser = attachedBrowser
            if (browser == null) {
                // 等待短时间以防宿主正在异步完成注入
                var waitCount = 0
                while (attachedBrowser == null && waitCount < 10) {
                    delay(200)
                    waitCount++
                }
                browser = attachedBrowser
            }

            if (browser == null) {
                println("[SingleMermaidWorker] WARNING: KBrowser is not attached. Worker cannot initialize.")
                return@withLock false
            }

            if (!JcefChecker.isJcefAvailable) {
                println("[SingleMermaidWorker] WARNING: JCEF is not available in current JBR!")
                return@withLock false
            }

            try {
                println("[SingleMermaidWorker] Initializing single dedicated KBWebView using attached KBrowser...")
                val page = browser.newPage(viewportWidth = 1920, viewportHeight = 1080)
                val view = page.webView
                webView = view

                val pageReadyDeferred = CompletableDeferred<Unit>()

                view.registerJsCallback("onWorkerPageReady") {
                    println("[SingleMermaidWorker] Background worker page reported READY!")
                    pageReadyDeferred.complete(Unit)
                }

                view.registerJsCallback("onMermaidRenderResult") { resultStr ->
                    val tabIdx = resultStr.indexOf('\t')
                    if (tabIdx != -1) {
                        val status = resultStr.substring(0, tabIdx)
                        val payload = resultStr.substring(tabIdx + 1)
                        if (status == "SUCCESS") {
                            pendingDeferred?.complete(WorkerPayload(1, payload, null))
                        } else {
                            pendingDeferred?.complete(WorkerPayload(2, null, payload))
                        }
                    } else {
                        pendingDeferred?.complete(WorkerPayload(2, null, resultStr))
                    }
                }

                val html = buildWorkerHtml()
                view.loadHtml(html)

                withTimeoutOrNull(10000.milliseconds) {
                    pageReadyDeferred.await()
                } ?: run {
                    println("[SingleMermaidWorker] Worker page ready timeout (will continue anyway)")
                }

                isReady = true
                println("[SingleMermaidWorker] Dedicated KBWebView worker initialized successfully.")
                true
            } catch (e: Throwable) {
                println("[SingleMermaidWorker] Failed to initialize worker: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * 串行渲染一张 Mermaid 结构图。
     * 若磁盘已有图则直接返回；无图时排队渲染，并将结果保存至磁盘。
     */
    internal suspend fun renderOrLoad(
        key: String,
        code: String,
        widthCss: Int,
        themeKey: Int,
        configJson: String,
    ): MermaidWorkerResult {
        // 1. 优先查磁盘自愈缓存
        val cached = MermaidDiskCache.readValidBitmap(key)
        if (cached != null) {
            return MermaidWorkerResult(cached, null)
        }

        // 2. 严格串行排队（单例 Worker 保证同一时刻只渲染一张图）
        return queueMutex.withLock {
            // 二次检查（排队期间可能前序相同图已落盘）
            val doubleCheck = MermaidDiskCache.readValidBitmap(key)
            if (doubleCheck != null) {
                return@withLock MermaidWorkerResult(doubleCheck, null)
            }

            val ready = ensureWorkerReady()
            val currentView = webView
            if (!ready || currentView == null) {
                return@withLock MermaidWorkerResult(null, "JCEF browser worker not available")
            }

            val deferred = CompletableDeferred<WorkerPayload>()
            pendingDeferred = deferred
            val seq = ++renderSequence

            val jsCode = "window.doRender('$seq', ${jsonString(code)}, $widthCss, ${jsonString(configJson)});"
            withContext(Dispatchers.Main) {
                currentView.evaluateJavascript(jsCode)
            }

            val response = withTimeoutOrNull(12000.milliseconds) {
                deferred.await()
            }

            pendingDeferred = null

            if (response == null) {
                return@withLock MermaidWorkerResult(null, "Mermaid render timed out")
            }

            if (response.state != 1 || response.base64.isNullOrEmpty()) {
                val err = response.error ?: "Render failed in JCEF worker"
                return@withLock MermaidWorkerResult(null, err)
            }

            val pngBytes = try {
                Base64.getDecoder().decode(response.base64)
            } catch (e: Throwable) {
                return@withLock MermaidWorkerResult(null, "Base64 decode error: ${e.message}")
            }

            // 3. 原子落盘
            MermaidDiskCache.savePng(key, pngBytes)

            // 4. 读取生成 ImageBitmap
            val bitmap = MermaidDiskCache.readValidBitmap(key)
            if (bitmap != null) {
                MermaidWorkerResult(bitmap, null)
            } else {
                MermaidWorkerResult(null, "Failed to decode generated PNG bitmap")
            }
        }
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun buildWorkerHtml(): String {
        val inlineJs = try {
            SingleMermaidWorker::class.java.getResourceAsStream("/mermaid.min.js")
                ?.bufferedReader()
                ?.readText()
        } catch (e: Throwable) {
            null
        }

        val scriptTag = if (!inlineJs.isNullOrBlank()) {
            "<script>\n$inlineJs\n</script>"
        } else {
            "<script src=\"https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js\"></script>"
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              $scriptTag
            </head>
            <body style="margin:0;padding:0;background:transparent;">
              <div id="mermaid-host"></div>
              <script>
                (function() {
                  console.log('[WorkerHTML] initializing worker script...');
                  function reportReady() {
                    if (window.onWorkerPageReady) {
                      window.onWorkerPageReady("READY");
                    }
                  }
                  if (typeof mermaid !== 'undefined') {
                    reportReady();
                  } else {
                    window.addEventListener('load', reportReady);
                  }

                  window.doRender = function(id, code, widthCss, configJson) {
                    console.log('[WorkerHTML] doRender called for id=' + id);
                    try {
                      var cfg = {};
                      try { cfg = JSON.parse(configJson); } catch(e) {}
                      mermaid.initialize({
                        startOnLoad: false,
                        securityLevel: 'strict',
                        htmlLabels: false,
                        flowchart: { htmlLabels: false },
                        theme: cfg.theme || 'default',
                        darkMode: cfg.darkMode === true,
                        themeVariables: cfg.themeVariables || {}
                      });
                      mermaid.render('m_' + id, code).then(function(result) {
                        var parser = new DOMParser();
                        var doc = parser.parseFromString(result.svg, 'image/svg+xml');
                        var svg = doc.documentElement;
                        var vb = (svg.getAttribute('viewBox') || '').trim().split(/[\s,]+/);
                        var vbW = (vb.length >= 4) ? parseFloat(vb[2]) : (parseFloat(svg.getAttribute('width')) || 400);
                        var vbH = (vb.length >= 4) ? parseFloat(vb[3]) : (parseFloat(svg.getAttribute('height')) || 200);
                        if (!vbW || vbW <= 0) vbW = 400;
                        if (!vbH || vbH <= 0) vbH = 200;

                        var targetW = (widthCss > 0 && vbW > widthCss) ? widthCss : vbW;
                        var targetH = Math.ceil(vbH * (targetW / vbW));
                        if (targetH <= 0) targetH = 160;

                        var dpr = 2.0; // 2x Retina 高清超采样
                        var canvasW = Math.round(targetW * dpr);
                        var canvasH = Math.round(targetH * dpr);

                        svg.setAttribute('width', canvasW + 'px');
                        svg.setAttribute('height', canvasH + 'px');
                        svg.style.maxWidth = 'none';
                        svg.style.width = canvasW + 'px';
                        svg.style.height = canvasH + 'px';

                        var serializer = new XMLSerializer();
                        var updatedSvg = serializer.serializeToString(svg);

                        var img = new Image();
                        img.onload = function() {
                          try {
                            var canvas = document.createElement('canvas');
                            canvas.width = canvasW;
                            canvas.height = canvasH;
                            var ctx = canvas.getContext('2d');
                            ctx.clearRect(0, 0, canvasW, canvasH);
                            ctx.drawImage(img, 0, 0, canvasW, canvasH);
                            var dataUrl = canvas.toDataURL('image/png');
                            var base64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
                            if (window.onMermaidRenderResult) {
                              window.onMermaidRenderResult("SUCCESS\t" + base64);
                            }
                          } catch (e) {
                            if (window.onMermaidRenderResult) {
                              window.onMermaidRenderResult("ERROR\t" + String(e));
                            }
                          }
                        };
                        img.onerror = function(e) {
                          if (window.onMermaidRenderResult) {
                            window.onMermaidRenderResult("ERROR\tFailed to load SVG into image");
                          }
                        };
                        img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(updatedSvg);
                      }).catch(function(err) {
                        var msg = err && err.message ? String(err.message) : String(err);
                        if (window.onMermaidRenderResult) {
                          window.onMermaidRenderResult("ERROR\t" + msg);
                        }
                      });
                    } catch (err) {
                      var msg = err && err.message ? String(err.message) : String(err);
                      if (window.onMermaidRenderResult) {
                        window.onMermaidRenderResult("ERROR\t" + msg);
                      }
                    }
                  };
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
