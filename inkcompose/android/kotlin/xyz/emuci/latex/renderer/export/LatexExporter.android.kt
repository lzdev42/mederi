package xyz.emuci.latex.renderer.export

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

/**
 * Android 平台：使用 Bitmap.compress() 将 ImageBitmap 编码为指定格式的字节数组
 */
actual fun ImageBitmap.encodeToFormat(format: ImageFormat, quality: Int): ByteArray? {
    return try {
        val androidBitmap = this.asAndroidBitmap()
        val compressFormat = when (format) {
            ImageFormat.PNG -> Bitmap.CompressFormat.PNG
            ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
            @Suppress("DEPRECATION")
            ImageFormat.WEBP -> Bitmap.CompressFormat.WEBP
        }
        val outputStream = ByteArrayOutputStream()
        androidBitmap.compress(compressFormat, quality.coerceIn(1, 100), outputStream)
        outputStream.toByteArray()
    } catch (e: Exception) {
        null
    }
}
