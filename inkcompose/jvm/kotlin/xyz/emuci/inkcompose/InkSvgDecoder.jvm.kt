package xyz.emuci.inkcompose

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLengthContext

import kotlin.math.roundToInt

/**
 * 基于 skia SVGDOM 的 SVG 解码器（jvm / ios / wasmJs 共用实现，各自编译进对应平台）。
 *
 * Coil 解码链：网络 / file:// / data: 统一产 SourceFetchResult，本解码器按
 * MIME → 扩展名 → 内容嗅探 三级识别 SVG，栅格化为 skia Image 交还 Coil。
 */
internal class InkSvgDecoder(
    private val source: SourceFetchResult,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val src = source.source.source()
        val bytes = try {
            src.readByteArray()
        } finally {
            src.close()
        }
        val rawSvg = bytes.decodeToString()
        val sanitizedSvg = sanitizeSvgForSkia(rawSvg)
        println("[InkSvgDecoder] Sanitize check: contains_url_grad=${rawSvg.contains("url(#")}, was_sanitized=${sanitizedSvg !== rawSvg}")
        val finalBytes = if (sanitizedSvg !== rawSvg) sanitizedSvg.encodeToByteArray() else bytes
        val dom = SVGDOM(Data.makeFromBytes(finalBytes))
        val root = dom.root

        // 尺寸优先级：intrinsic size → viewBox → 512 兜底
        val intrinsic = root?.getIntrinsicSize(SVGLengthContext(0f, 0f))
        var w = intrinsic?.x?.takeIf { it > 0f } ?: 0f
        var h = intrinsic?.y?.takeIf { it > 0f } ?: 0f
        val hasIntrinsic = w > 0f && h > 0f
        if (!hasIntrinsic) {
            root?.viewBox?.let { vb ->
                if (vb.width > 0f) w = vb.width
                if (vb.height > 0f) h = vb.height
            }
        }
        if (w <= 0f || h <= 0f) {
            w = 512f
            h = 512f
        }
        // 如果 SVG 自身未声明固定宽高（仅有 viewBox），需要设置容器尺寸以建立绘制视口
        if (!hasIntrinsic) {
            dom.setContainerSize(org.jetbrains.skia.Point(w, h))
        }

        // Density 优先级：Options.extras[InkDensityKey] → 屏幕物理缩放比 → 1.0f 兜底
        val densityFromOptions = options.extras[InkDensityKey]
        val fallbackDensity = getDesktopScreenDensity()
        val density = if (densityFromOptions != null && densityFromOptions > 0f) densityFromOptions else fallbackDensity
        val scale = density.coerceAtLeast(1f)

        val targetWidth = (w * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (h * scale).roundToInt().coerceAtLeast(1)
        println("[InkSvgDecoder] SVG logical size: ${w}x${h}, density: $density (fromOptions=$densityFromOptions, fallback=$fallbackDensity), scale=$scale, allocated bitmap: ${targetWidth}x${targetHeight}")

        val bitmap = Bitmap().apply { allocN32Pixels(targetWidth, targetHeight) }
        val canvas = Canvas(bitmap)
        // 清空画布为透明，防止未初始化像素产生脏数据
        canvas.clear(Color.TRANSPARENT)
        canvas.scale(scale, scale)
        dom.render(canvas)
        return DecodeResult(image = bitmap.asImage(shareable = true), isSampled = false)
    }
}

private fun getDesktopScreenDensity(): Float {
    return try {
        if (java.awt.GraphicsEnvironment.isHeadless()) 1f
        else java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice?.defaultConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
    } catch (_: Throwable) {
        1f
    }
}


/** SVG 文件扩展名（小写） */
private val SVG_EXTENSIONS = setOf("svg", "svgz")

internal class InkSvgDecoderFactory : Decoder.Factory {

    override fun create(data: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        // 1. MIME 类型判断（网络请求通常带 Content-Type，零 I/O）
        val mime = data.mimeType?.lowercase()
        if (mime != null && mime.contains("svg")) return InkSvgDecoder(data, options)

        // 2. 扩展名快速路径（本地 file:// 无 MIME 时，纯字符串操作零 I/O）
        val filePath = data.source.file()?.name
        if (filePath != null) {
            val ext = filePath.substringAfterLast('.', "").lowercase()
            if (ext in SVG_EXTENSIONS) return InkSvgDecoder(data, options)
        }

        // 3. 内容嗅探兜底（data: URI 或无扩展名场景）
        //    使用 peek + request 主动从上游拉取数据，避免读取空缓冲区
        val isSvg = try {
            val peek = data.source.source().peek()
            peek.request(1024)
            val buffered = peek.buffer
            val n = minOf(buffered.size, 1024L).toInt()
            n > 0 && buffered.readByteArray(n.toLong()).decodeToString().lowercase().contains("<svg")
        } catch (_: Exception) {
            false
        }
        return if (isSvg) InkSvgDecoder(data, options) else null
    }
}
