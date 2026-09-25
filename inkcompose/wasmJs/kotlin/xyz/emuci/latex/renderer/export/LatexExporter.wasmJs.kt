package xyz.emuci.latex.renderer.export

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * WasmJS 平台：使用 Skia Image.encodeToData() 将 ImageBitmap 编码为指定格式的字节数组
 */
actual fun ImageBitmap.encodeToFormat(format: ImageFormat, quality: Int): ByteArray? {
    return try {
        val skiaBitmap = this.asSkiaBitmap()
        val image = Image.makeFromBitmap(skiaBitmap)
        val skiaFormat = when (format) {
            ImageFormat.PNG -> EncodedImageFormat.PNG
            ImageFormat.JPEG -> EncodedImageFormat.JPEG
            ImageFormat.WEBP -> EncodedImageFormat.WEBP
        }
        val data = image.encodeToData(skiaFormat, quality.coerceIn(1, 100))
        data?.bytes
    } catch (e: Exception) {
        null
    }
}
