package xyz.emuci.inkcompose

import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import xyz.emuci.inkcompose.resources.Res

@Volatile
private var fontCache: MutableMap<String, String>? = null

/**
 * Compose Resources 真实现：按文件名读取 common/composeResources/font/ 下的打包 ttf 并转 Base64。
 * @Volatile 缓存 + 双读兜底（竞态最多多读一次，无害）；读取失败/资源缺失静默返回 null。
 */
@OptIn(ExperimentalEncodingApi::class)
internal actual suspend fun readBundledFontBase64(fontFileName: String): String? {
    fontCache?.get(fontFileName)?.takeIf { it.isNotEmpty() }?.let { return it }
    val encoded = try {
        Base64.encode(Res.readBytes("font/$fontFileName"))
    } catch (_: Throwable) {
        ""
    }
    if (encoded.isEmpty()) return null
    (fontCache ?: mutableMapOf<String, String>().also { fontCache = it })[fontFileName] = encoded
    return encoded
}

internal actual fun readBundledMermaidScript(): String? = null

internal actual suspend fun exportMarkdownHtmlToPdfPlatform(
    html: String,
    outputPath: String,
    options: PdfExportOptions,
): Result<String> {
    return Result.failure(UnsupportedOperationException("PDF export is not supported on Android yet."))
}
