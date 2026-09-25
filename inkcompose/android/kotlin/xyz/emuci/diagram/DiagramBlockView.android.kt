package xyz.emuci.diagram

import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.emuci.diagram.mermaid.AndroidMermaidDiskCache
import xyz.emuci.diagram.theme.DiagramTheme
import xyz.emuci.diagram.theme.mermaidConfigPayloadJson
import xyz.emuci.diagram.theme.toCssColor
import xyz.kbrowser.webview.KBWebView
import xyz.kbrowser.webview.rememberKBWebView
import kotlin.math.roundToInt

private const val RENDER_DEBOUNCE_MS = 150L
private const val DEFAULT_HEIGHT_CSS = 160
private const val PAGE_READY_TIMEOUT_MS = 15_000L

/** 全局渲染互斥锁：确保移动端同一时刻至多一个 WebView 在执行 Mermaid 栅格化，防止内存飙升与多实例竞争 */
private val mobileRenderMutex = Mutex()

/**
 * Android actual：对齐 JVM 架构。
 * 1. 优先从 AndroidMermaidDiskCache 读取本地 PNG（0ms 秒开，0 WebView 创建）；
 * 2. 未命中时通过全局互斥锁在后台隐藏 WebView 中执行离屏栅格化，Canvas 导出 Base64 PNG 落盘；
 * 3. 渲染完成后立即卸载 WebView，UI 树中仅挂载原生 Compose Image，杜绝“一图一 WebView”对 LazyColumn 的性能摧毁。
 */
@Composable
internal actual fun DiagramBlockView(
    source: String,
    theme: DiagramTheme,
    languageHint: String?,
    sessionKey: Any?,
    modifier: Modifier,
) {
    val themeKey = remember(theme) { theme.hashCode() }
    val cacheKey = remember(source, themeKey, sessionKey) {
        AndroidMermaidDiskCache.computeKey(source, themeKey, sessionKey?.toString())
    }

    // 1. 优先读取磁盘自愈缓存（0 延迟秒开）
    val initialCached = remember(cacheKey) {
        AndroidMermaidDiskCache.readValidBitmap(cacheKey)
    }

    var imageBitmap by remember(cacheKey) {
        mutableStateOf(initialCached)
    }
    var renderError by remember(cacheKey) {
        mutableStateOf<String?>(null)
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val widthCss = if (maxWidth.isSpecified && maxWidth.value > 0f && maxWidth != Dp.Infinity) {
            maxWidth.value.roundToInt()
        } else {
            800
        }

        // 仅在未命中缓存且未产生致命错误时，按需挂载隐藏 Worker
        val needsRender = imageBitmap == null && renderError == null

        if (needsRender) {
            val workerHtml = remember(themeKey) { buildMobileWorkerHtml(theme) }
            val webView = rememberKBWebView(initialUrl = "about:blank")
            var pageReady by remember { mutableStateOf(false) }
            val renderDeferred = remember(cacheKey) { CompletableDeferred<ByteArray?>() }

            LaunchedEffect(webView, workerHtml) {
                pageReady = false
                webView.registerJsCallback("onMermaidPageReady") {
                    pageReady = true
                }
                webView.registerJsCallback("onMermaidRenderResult") { resultStr ->
                    val tabIdx = resultStr.indexOf('\t')
                    if (tabIdx != -1) {
                        val status = resultStr.substring(0, tabIdx)
                        val payload = resultStr.substring(tabIdx + 1)
                        if (status == "SUCCESS") {
                            val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
                            renderDeferred.complete(bytes)
                        } else {
                            renderError = payload.ifBlank { "Render failed in worker" }
                            renderDeferred.complete(null)
                        }
                    } else {
                        renderError = resultStr.ifBlank { "Render failed" }
                        renderDeferred.complete(null)
                    }
                }
                withContext(Dispatchers.Main) { webView.loadHtml(workerHtml) }
            }

            LaunchedEffect(cacheKey, widthCss, pageReady) {
                if (widthCss <= 0 || source.isBlank()) return@LaunchedEffect
                delay(RENDER_DEBOUNCE_MS)

                // 等待 Worker 页面就绪
                if (!pageReady) {
                    val ready = withTimeoutOrNull(PAGE_READY_TIMEOUT_MS) {
                        while (!pageReady) delay(50)
                        true
                    }
                    if (ready != true) {
                        renderError = "Worker page not ready (CDN unreachable?)"
                        return@LaunchedEffect
                    }
                }

                mobileRenderMutex.withLock {
                    val doubleCheck = AndroidMermaidDiskCache.readValidBitmap(cacheKey)
                    if (doubleCheck != null) {
                        imageBitmap = doubleCheck
                        return@withLock
                    }

                    val js = "window.doRender(${jsonString(source)}, ${jsonString(mermaidConfigPayloadJson(theme))}, $widthCss);"
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(js)
                    }

                    val pngBytes = withTimeoutOrNull(15_000L) {
                        renderDeferred.await()
                    }

                    if (pngBytes != null) {
                        AndroidMermaidDiskCache.savePng(cacheKey, pngBytes)
                        val loaded = AndroidMermaidDiskCache.readValidBitmap(cacheKey)
                        if (loaded != null) {
                            renderError = null
                            imageBitmap = loaded
                        } else {
                            renderError = "Failed to decode generated PNG bitmap"
                        }
                    } else if (renderError == null) {
                        renderError = "Render timed out"
                    }
                }
            }

            // 离屏隐藏 WebView（1x1 像素且透明，绝不侵占正常 UI 排版，渲染成功后随 needsRender 消失）
            Box(modifier = Modifier.size(1.dp).alpha(0f)) {
                KBWebView(webView = webView, modifier = Modifier.size(1.dp))
            }
        }

        val currentBitmap = imageBitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap,
                contentDescription = "Mermaid Diagram",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else if (renderError != null) {
            DiagramCodeFallback(
                code = source.trimEnd('\n'),
                typeName = "Mermaid ($renderError)",
                modifier = Modifier.fillMaxWidth(),
                decorate = false,
            )
        } else {
            // 骨架占位
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DEFAULT_HEIGHT_CSS.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Rendering diagram...", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** JS 字符串字面量转义。 */
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

/**
 * 移动端 Worker 页面：mermaid 从 CDN 引入，
 * 渲染为 SVG 后通过 HTML5 Canvas 导出 Base64 PNG 数据回传给原生层落盘。
 */
private fun buildMobileWorkerHtml(theme: DiagramTheme): String {
    val canvasCss = theme.colors.canvas.toCssColor()
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <script src="https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"></script>
        </head>
        <body style="margin:0;padding:0;background:$canvasCss;overflow:hidden;">
          <div id="mermaid-host"></div>
          <script>
            (function() {
              function reportReady() {
                if (window.onMermaidPageReady) window.onMermaidPageReady("READY");
              }
              if (typeof mermaid !== 'undefined') {
                reportReady();
              } else {
                window.addEventListener('load', reportReady);
              }

              window.doRender = function(code, configJson, widthCss) {
                try {
                  var cfg = {};
                  try { cfg = JSON.parse(configJson); } catch (e) {}
                  mermaid.initialize({
                    startOnLoad: false,
                    securityLevel: 'strict',
                    htmlLabels: false,
                    flowchart: { htmlLabels: false },
                    theme: cfg.theme || 'default',
                    darkMode: cfg.darkMode === true,
                    themeVariables: cfg.themeVariables || {}
                  });
                  mermaid.render('mmd_' + Date.now(), code).then(function(result) {
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

                    var dpr = 2.0;
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
                        if (window.onMermaidRenderResult) window.onMermaidRenderResult("ERROR\t" + (e.message || String(e)));
                      }
                    };
                    img.onerror = function() {
                      if (window.onMermaidRenderResult) window.onMermaidRenderResult("ERROR\tFailed to load SVG into Image");
                    };
                    img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(updatedSvg);
                  }).catch(function(err) {
                    var msg = err && err.message ? String(err.message) : String(err);
                    if (window.onMermaidRenderResult) window.onMermaidRenderResult("ERROR\t" + msg);
                  });
                } catch (err) {
                  var msg = err && err.message ? String(err.message) : String(err);
                  if (window.onMermaidRenderResult) window.onMermaidRenderResult("ERROR\t" + msg);
                }
              };
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}
