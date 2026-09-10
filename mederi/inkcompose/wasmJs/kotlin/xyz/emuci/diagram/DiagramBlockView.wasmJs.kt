package xyz.emuci.diagram

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.delay
import xyz.emuci.diagram.theme.DiagramTheme
import xyz.emuci.diagram.theme.mermaidConfigPayloadJson
import kotlin.math.roundToInt


/** 渲染防抖（宽度变化与重组防抖）。 */
private const val RENDER_DEBOUNCE_MS = 150L

/** 未渲染完成前的默认占位高度（CSS px）。 */
private const val DEFAULT_HEIGHT_CSS = 160

private data class DiagramCacheEntry(
    val width: Int,
    val height: Int,
    val bitmap: ImageBitmap,
)

/**
 * 内存位图与尺寸缓存：(源码哈希, 宽度, 主题) -> 渲染产物。
 * 滚动回收重组时直接命中内存位图，0 秒立现，无需重新等待 JS 渲染。
 */
private object DiagramCache {
    private const val MAX_ENTRIES = 40
    private val map = LinkedHashMap<String, DiagramCacheEntry>()

    fun get(source: String, widthCss: Int, themeKey: Int): DiagramCacheEntry? {
        val key = "${source.hashCode()}:$widthCss:$themeKey"
        return map[key]
    }

    fun put(source: String, widthCss: Int, themeKey: Int, width: Int, height: Int, bitmap: ImageBitmap) {
        val key = "${source.hashCode()}:$widthCss:$themeKey"
        if (map.size >= MAX_ENTRIES) {
            val firstKey = map.keys.firstOrNull()
            if (firstKey != null) map.remove(firstKey)
        }
        map[key] = DiagramCacheEntry(width, height, bitmap)
    }
}

private var nextNodeId = 0

/**
 * wasmJs actual：使用官方 mermaid.js 离屏超采样栅格化为 PNG，再通过 Skia 解码为 Compose 原生 ImageBitmap。
 *
 * 核心特性：
 * 1. 纯 Compose Canvas 原生组件绘制（Image），享受 Compose 完整的 Z-order、Layer 与视口裁剪；
 * 2. 彻底废除 DOM 浮层覆盖层，不再有输入框遮挡问题；
 * 3. 鼠标滚动/手势 100% 自然透传，0 滚动拦截；
 * 4. 按屏幕物理 DPR（至少 2x）超分辨率光栅化，在 Retina 屏幕上清晰锐利。
 */
@Composable
internal actual fun DiagramBlockView(
    source: String,
    theme: DiagramTheme,
    languageHint: String?,
    sessionKey: Any?,
    modifier: Modifier,
) {
    val nodeId = remember { nextNodeId++ }
    val themeKey = remember(theme) { theme.hashCode() }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val widthCss = if (maxWidth.isSpecified && maxWidth.value > 0f && maxWidth != Dp.Infinity) {
            maxWidth.value.roundToInt()
        } else {
            600
        }

        val cached = remember(source, widthCss, themeKey) {
            DiagramCache.get(source, widthCss, themeKey)
        }

        var imageBitmap by remember(source, widthCss, themeKey) {
            mutableStateOf(cached?.bitmap)
        }
        var displayWidthCss by remember(source, widthCss, themeKey) {
            mutableStateOf(cached?.width)
        }
        var displayHeightCss by remember(source, widthCss, themeKey) {
            mutableStateOf(cached?.height)
        }
        var renderError by remember(source, widthCss, themeKey) {
            mutableStateOf<String?>(null)
        }

        LaunchedEffect(source, widthCss, themeKey) {
            if (widthCss <= 0) return@LaunchedEffect
            if (cached != null) {
                imageBitmap = cached.bitmap
                displayWidthCss = cached.width
                displayHeightCss = cached.height
                return@LaunchedEffect
            }
            delay(RENDER_DEBOUNCE_MS)
            println("[DiagramBlockView] renderToBitmap starting: nodeId=$nodeId, widthCss=$widthCss, sourceLen=${source.length}")
            val result = MermaidDiagramDom.renderToBitmap(
                id = nodeId,
                code = source,
                widthCss = widthCss,
                themeKey = themeKey,
                configJson = mermaidConfigPayloadJson(theme),
            )
            println("[DiagramBlockView] renderToBitmap finished: nodeId=$nodeId, hasBitmap=${result.bitmap != null}, w=${result.widthCss}, h=${result.heightCss}, error=${result.error}")
            if (result.error != null) {
                renderError = result.error
                imageBitmap = null
            } else if (result.bitmap != null) {
                renderError = null
                imageBitmap = result.bitmap
                displayWidthCss = result.widthCss
                displayHeightCss = result.heightCss
                DiagramCache.put(
                    source = source,
                    widthCss = widthCss,
                    themeKey = themeKey,
                    width = result.widthCss,
                    height = result.heightCss,
                    bitmap = result.bitmap,
                )
            }
        }

        val boxHeight = (displayHeightCss ?: DEFAULT_HEIGHT_CSS).dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(boxHeight),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = imageBitmap
            if (bmp != null) {
                val targetW = (displayWidthCss ?: widthCss).dp
                val targetH = boxHeight
                Image(
                    bitmap = bmp,
                    contentDescription = "Mermaid Diagram",
                    modifier = Modifier.size(targetW, targetH),
                    contentScale = ContentScale.Fit,
                )
            } else if (renderError != null) {
                Text(
                    text = renderError ?: "Error rendering diagram",
                    color = theme.colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}
