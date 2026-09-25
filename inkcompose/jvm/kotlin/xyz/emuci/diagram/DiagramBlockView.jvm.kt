package xyz.emuci.diagram

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.delay
import xyz.emuci.diagram.mermaid.MermaidDiskCache
import xyz.emuci.diagram.mermaid.SingleMermaidWorker
import xyz.emuci.diagram.theme.DiagramTheme
import xyz.emuci.diagram.theme.mermaidConfigPayloadJson
import kotlin.math.roundToInt

private const val RENDER_DEBOUNCE_MS = 150L
private const val DEFAULT_HEIGHT_CSS = 160

/**
 * JVM actual：KBrowser JCEF 离屏 Worker 渲染。
 * 磁盘缓存命中 → 0 延迟秒开；未命中 → 串行排队渲染 + 原子落盘。
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
        MermaidDiskCache.computeKey(source, themeKey, sessionKey?.toString())
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

        // 1. 优先查磁盘自愈缓存（0 延迟秒开）
        val initialCached = remember(cacheKey) {
            MermaidDiskCache.readValidBitmap(cacheKey)
        }

        var imageBitmap by remember(cacheKey) {
            mutableStateOf(initialCached)
        }
        var renderError by remember(cacheKey) {
            mutableStateOf<String?>(null)
        }

        LaunchedEffect(cacheKey, widthCss) {
            if (widthCss <= 0) return@LaunchedEffect
            if (imageBitmap != null) return@LaunchedEffect

            val diskBitmap = MermaidDiskCache.readValidBitmap(cacheKey)
            if (diskBitmap != null) {
                imageBitmap = diskBitmap
                return@LaunchedEffect
            }

            delay(RENDER_DEBOUNCE_MS)

            val result = SingleMermaidWorker.renderOrLoad(
                key = cacheKey,
                code = source,
                widthCss = widthCss,
                themeKey = themeKey,
                configJson = mermaidConfigPayloadJson(theme),
            )

            if (result.bitmap != null) {
                renderError = null
                imageBitmap = result.bitmap
            } else if (result.error != null) {
                renderError = result.error
            }
        }

        val currentBitmap = imageBitmap
        if (currentBitmap != null) {
            val logicalW = (currentBitmap.width / 2).dp
            val logicalH = (currentBitmap.height / 2).dp

            val isConstrained = maxWidth.isSpecified && maxWidth.value > 0f && maxWidth != Dp.Infinity
            val boxModifier = if (isConstrained && logicalW > maxWidth) {
                val ratio = currentBitmap.width.toFloat() / currentBitmap.height.toFloat()
                Modifier.fillMaxWidth().height((maxWidth.value / ratio).dp)
            } else {
                Modifier.size(logicalW, logicalH)
            }

            Image(
                bitmap = currentBitmap,
                contentDescription = "Mermaid Diagram",
                modifier = boxModifier,
                contentScale = ContentScale.Fit,
            )
        } else if (renderError != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DEFAULT_HEIGHT_CSS.dp),
                contentAlignment = Alignment.Center,
            ) {
                DiagramCodeFallback(
                    code = source.trimEnd('\n'),
                    typeName = "Mermaid ($renderError)",
                    modifier = Modifier.fillMaxWidth(),
                    decorate = false,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DEFAULT_HEIGHT_CSS.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Rendering diagram...",
                    color = theme.colors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
