@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package xyz.emuci.diagram

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class MermaidBitmapResult(
    val bitmap: ImageBitmap?,
    val widthCss: Int,
    val heightCss: Int,
    val error: String?,
)

/**
 * wasmJs 端 Mermaid 渲染器：
 * - 纯离屏渲染：使用官方 mermaid.js 解析生成 SVG；
 * - 超采样栅格化：在离屏 Canvas 上以物理分辨率（DPR 至少 2x）绘制 SVG 并导出高清 PNG；
 * - Skia 原生解码：解码 Base64 为 PNG 字节数组后，由 Compose Skia 生成原生 ImageBitmap；
 * - 彻底避免 DOM 覆盖层引起的 Z-order 错乱、视口裁剪失败以及滚动拦截问题。
 */
internal object MermaidDiagramDom {

    private var injected = false

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun renderToBitmap(
        id: Int,
        code: String,
        widthCss: Int,
        themeKey: Int,
        configJson: String,
    ): MermaidBitmapResult {
        ensureBridge()
        println("[MermaidDiagramDom] renderToBitmap called: id=$id, widthCss=$widthCss, codeLen=${code.length}")
        bridgeRender(id, code, widthCss, themeKey, configJson)
        var count = 0
        while (count < 140) { // 最多等待 7 秒
            val state = bridgeGetState(id)
            if (state != 0) break
            delay(50)
            count++
        }
        val state = bridgeGetState(id)
        val w = bridgeGetWidth(id)
        val h = bridgeGetHeight(id)
        val err = bridgeGetError(id)
        val base64 = bridgeGetBase64(id)
        bridgeClear(id)

        if (state == 2 || err.isNotEmpty()) {
            println("[MermaidDiagramDom] render failed for id=$id: $err")
            return MermaidBitmapResult(null, 0, 0, err.ifEmpty { "Render failed" })
        }
        if (state == 0) {
            println("[MermaidDiagramDom] render timed out for id=$id")
            return MermaidBitmapResult(null, 0, 0, "Render timed out")
        }
        if (base64.isEmpty()) {
            println("[MermaidDiagramDom] empty base64 for id=$id")
            return MermaidBitmapResult(null, 0, 0, "Empty image data received")
        }

        println("[MermaidDiagramDom] base64 received: len=${base64.length}, logical=${w}x${h}. Decoding to Skia ImageBitmap...")
        val bitmap = try {
            val bytes = Base64.Default.decode(base64)
            println("[MermaidDiagramDom] decoded PNG bytes size=${bytes.size}. Creating Skia Image...")
            val skiaImage = Image.makeFromEncoded(bytes)
            val composeBmp = skiaImage.toComposeImageBitmap()
            println("[MermaidDiagramDom] Compose ImageBitmap created: ${composeBmp.width}x${composeBmp.height}")
            composeBmp
        } catch (e: Throwable) {
            println("[MermaidDiagramDom] error decoding bitmap: ${e.message}")
            return MermaidBitmapResult(null, w, h, "Bitmap decode error: ${e.message}")
        }

        return MermaidBitmapResult(bitmap, w, h, null)
    }

    private fun ensureBridge() {
        if (injected) return
        injected = true
        bridgeEval(BRIDGE_JS)
    }

    private const val BRIDGE_JS = """
        (function() {
          console.log('[MermaidDiagramJS] bridge initializing...');
          var seq = 0, chain = Promise.resolve(), lastThemeKey = null, libState = 0, waiters = [];
          var renderStates = {};

          function flushLib(ok, message) {
            var ws = waiters; waiters = [];
            for (var i = 0; i < ws.length; i++) { if (ok) { ws[i][0](); } else { ws[i][1](message); } }
          }

          function ensureLib(onOk, onFail) {
            if (typeof window.mermaid !== 'undefined') {
              libState = 2;
              onOk();
              return;
            }
            if (libState === 2) { onOk(); return; }
            waiters.push([onOk, onFail]);
            if (libState !== 0) return;
            libState = 1;
            var urls = ['https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js', './mermaid.min.js'];
            var attempt = function(i) {
              console.log('[MermaidDiagramJS] loading mermaid lib, attempt: ' + urls[i]);
              var s = document.createElement('script');
              s.src = urls[i];
              s.crossorigin = 'anonymous';
              s.onload = function() {
                console.log('[MermaidDiagramJS] mermaid lib loaded successfully from ' + urls[i]);
                libState = 2;
                flushLib(true, null);
              };
              s.onerror = function(err) {
                console.warn('[MermaidDiagramJS] failed to load mermaid lib from ' + urls[i], err);
                if (i + 1 < urls.length) {
                  attempt(i + 1);
                } else {
                  libState = 3;
                  var msg = 'mermaid 加载失败（jsdelivr CDN 不可达且本地文件缺失）';
                  console.error('[MermaidDiagramJS] ' + msg);
                  flushLib(false, msg);
                }
              };
              document.head.appendChild(s);
            };
            attempt(0);
          }

          window.inkcomposeDiagram = {
            render: function(id, code, widthCss, themeKey, configJson) {
              renderStates[id] = { state: 0, width: 0, height: 0, base64: '', error: '' };
              console.log('[MermaidDiagramJS] render called: id=' + id + ', widthCss=' + widthCss);
              ensureLib(function() {
                chain = chain.then(function() {
                  if (lastThemeKey !== themeKey) {
                    console.log('[MermaidDiagramJS] initializing mermaid with new themeKey=' + themeKey);
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
                    lastThemeKey = themeKey;
                  }
                  seq++;
                  var renderId = 'ink-m' + id + '-' + seq;
                  console.log('[MermaidDiagramJS] calling mermaid.render: ' + renderId);
                  return mermaid.render(renderId, code).then(function(result) {
                    return new Promise(function(resolve) {
                      try {
                        var parser = new DOMParser();
                        var doc = parser.parseFromString(result.svg, 'image/svg+xml');
                        var svg = doc.documentElement;

                        var vb = (svg.getAttribute('viewBox') || '').trim().split(/[\s,]+/);
                        var vbW = (vb.length >= 4) ? parseFloat(vb[2]) : (parseFloat(svg.getAttribute('width')) || 0);
                        var vbH = (vb.length >= 4) ? parseFloat(vb[3]) : (parseFloat(svg.getAttribute('height')) || 0);

                        if (!vbW || vbW <= 0) vbW = 400;
                        if (!vbH || vbH <= 0) vbH = 200;

                        var targetW = (widthCss > 0 && vbW > widthCss) ? widthCss : vbW;
                        var targetH = Math.ceil(vbH * (targetW / vbW));
                        if (targetH <= 0) targetH = 160;

                        var dpr = Math.max(2, window.devicePixelRatio || 2);
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
                            renderStates[id] = {
                              state: 1,
                              width: Math.round(targetW),
                              height: Math.round(targetH),
                              base64: base64,
                              error: ''
                            };
                            console.log('[MermaidDiagramJS] rasterized to PNG: id=' + id + ' logical=' + targetW + 'x' + targetH + ' canvas=' + canvasW + 'x' + canvasH + ' dpr=' + dpr + ' b64Len=' + base64.length);
                            resolve();
                          } catch (err) {
                            var msg = err && err.message ? String(err.message) : String(err);
                            console.error('[MermaidDiagramJS] canvas export error: ' + msg);
                            renderStates[id] = { state: 2, width: 0, height: 0, base64: '', error: msg };
                            resolve();
                          }
                        };
                        img.onerror = function(err) {
                          console.error('[MermaidDiagramJS] img load error: ', err);
                          renderStates[id] = { state: 2, width: 0, height: 0, base64: '', error: 'SVG image load failed' };
                          resolve();
                        };
                        img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(updatedSvg);
                      } catch (err) {
                        var msg = err && err.message ? String(err.message) : String(err);
                        console.error('[MermaidDiagramJS] rasterization setup error: ' + msg);
                        renderStates[id] = { state: 2, width: 0, height: 0, base64: '', error: msg };
                        resolve();
                      }
                    });
                  }).catch(function(e) {
                    var errMsg = e && e.message ? String(e.message) : String(e);
                    console.error('[MermaidDiagramJS] render failed: ' + errMsg);
                    renderStates[id] = { state: 2, width: 0, height: 0, base64: '', error: errMsg };
                  });
                });
              }, function(message) {
                console.error('[MermaidDiagramJS] ensureLib failed: ' + message);
                renderStates[id] = { state: 2, width: 0, height: 0, base64: '', error: message };
              });
            },
            getState: function(id) {
              var s = renderStates[id];
              return s ? s.state : 0;
            },
            getWidth: function(id) {
              var s = renderStates[id];
              return s ? s.width : 0;
            },
            getHeight: function(id) {
              var s = renderStates[id];
              return s ? s.height : 0;
            },
            getBase64: function(id) {
              var s = renderStates[id];
              return (s && s.base64) ? s.base64 : '';
            },
            getError: function(id) {
              var s = renderStates[id];
              return (s && s.error) ? s.error : '';
            },
            clear: function(id) {
              delete renderStates[id];
            }
          };
        })();
    """
}

private fun bridgeEval(code: String): Unit = js("eval(code)")

private fun bridgeRender(
    id: Int,
    code: String,
    widthCss: Int,
    themeKey: Int,
    configJson: String,
): Unit = js("window.inkcomposeDiagram.render(id, code, widthCss, themeKey, configJson)")

private fun bridgeGetState(id: Int): Int = js("window.inkcomposeDiagram.getState(id)")

private fun bridgeGetWidth(id: Int): Int = js("window.inkcomposeDiagram.getWidth(id)")

private fun bridgeGetHeight(id: Int): Int = js("window.inkcomposeDiagram.getHeight(id)")

private fun bridgeGetBase64(id: Int): String = js("window.inkcomposeDiagram.getBase64(id)")

private fun bridgeGetError(id: Int): String = js("window.inkcomposeDiagram.getError(id)")

private fun bridgeClear(id: Int): Unit = js("window.inkcomposeDiagram.clear(id)")
