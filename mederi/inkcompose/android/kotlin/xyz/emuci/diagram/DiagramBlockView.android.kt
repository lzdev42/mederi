package xyz.emuci.diagram

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.kbrowser.webview.KBWebView
import xyz.kbrowser.webview.rememberKBWebView
import xyz.emuci.diagram.theme.DiagramTheme
import xyz.emuci.diagram.theme.jsonEscape
import xyz.emuci.diagram.theme.mermaidConfigPayloadJson
import xyz.emuci.diagram.theme.toCssColor

/** 未渲染完成前的默认占位高度（CSS px ≈ dp）。 */
private const val DEFAULT_HEIGHT_CSS = 160

/** Worker 页面就绪超时（CDN 加载失败时降级为源码展示）。 */
private const val PAGE_READY_TIMEOUT_MS = 15_000L

/**
 * Android/IOS actual：KBrowser 桥接系统 WebView / WKWebView。
 * 内嵌 Worker 页面从 CDN 加载官方 mermaid.js，渲染为内联 SVG（透明背景），
 * 高度回报给 Compose 自适应容器。无位图产物，无需 JVM 端的磁盘缓存。
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
    val workerHtml = remember(themeKey) { buildMobileWorkerHtml(theme) }

    var pageReady by remember { mutableStateOf(false) }
    var heightCss by remember { mutableStateOf(DEFAULT_HEIGHT_CSS) }
    var renderError by remember { mutableStateOf<String?>(null) }

    val webView = rememberKBWebView(initialUrl = "about:blank")

    LaunchedEffect(webView, workerHtml) {
        pageReady = false
        renderError = null
        webView.registerJsCallback("onMermaidPageReady") { pageReady = true }
        webView.registerJsCallback("onMermaidHeight") { data ->
            data.toIntOrNull()?.let { if (it > 0) heightCss = it }
        }
        webView.registerJsCallback("onMermaidError") { data ->
            renderError = data.ifBlank { "Render failed" }
        }
        withContext(Dispatchers.Main) { webView.loadHtml(workerHtml) }
    }

    LaunchedEffect(webView, pageReady, source, themeKey) {
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
        if (source.isBlank()) return@LaunchedEffect
        renderError = null
        val js = "window.doRender(${jsonString(source)}, ${jsonString(mermaidConfigPayloadJson(theme))});"
        withContext(Dispatchers.Main) { webView.evaluateJavascript(js) }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val error = renderError
        if (error != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DEFAULT_HEIGHT_CSS.dp),
                contentAlignment = Alignment.Center,
            ) {
                DiagramCodeFallback(
                    code = source.trimEnd('\n'),
                    typeName = "Mermaid ($error)",
                    modifier = Modifier.fillMaxWidth(),
                    decorate = false,
                )
            }
        } else {
            KBWebView(
                webView = webView,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightCss.dp),
            )
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
 * 移动端 Worker 页面：mermaid 从 CDN 引入（内嵌页面无法访问宿主资源），
 * 背景色对齐图表画布，渲染完成后以 ResizeObserver 持续回报高度。
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
              var host = document.getElementById('mermaid-host');
              function reportReady() {
                if (window.onMermaidPageReady) window.onMermaidPageReady("READY");
              }
              if (typeof mermaid !== 'undefined') {
                reportReady();
              } else {
                window.addEventListener('load', reportReady);
              }

              if (typeof ResizeObserver !== 'undefined') {
                new ResizeObserver(function() {
                  var rect = host.getBoundingClientRect();
                  var h = Math.ceil(rect.height);
                  if (h > 0 && window.onMermaidHeight) window.onMermaidHeight(String(h));
                }).observe(host);
              }

              window.doRender = function(code, configJson) {
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
                    host.innerHTML = result.svg;
                    var svg = host.querySelector('svg');
                    if (svg) {
                      svg.style.maxWidth = '100%';
                      svg.style.width = '100%';
                      svg.style.height = 'auto';
                      svg.removeAttribute('height');
                    }
                  }).catch(function(err) {
                    var msg = err && err.message ? String(err.message) : String(err);
                    if (window.onMermaidError) window.onMermaidError(msg);
                  });
                } catch (err) {
                  var msg = err && err.message ? String(err.message) : String(err);
                  if (window.onMermaidError) window.onMermaidError(msg);
                }
              };
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}
