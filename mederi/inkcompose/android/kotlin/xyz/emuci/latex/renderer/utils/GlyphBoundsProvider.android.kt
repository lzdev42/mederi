package xyz.emuci.latex.renderer.utils

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Android 平台：使用 android.graphics.Paint.getTextBounds() 获取精确墨水边界。
 *
 * android.graphics.Paint.getTextBounds() 返回的是字形的精确像素边界矩形 (tight bounding box)，
 * 不包含行框的 ascent/descent 空白。
 *
 * Rect 坐标系：baseline 在 y=0，上方为负值，下方为正值。
 * - rect.top 为负值（baseline 以上）
 * - rect.bottom 为正值或零（baseline 以下）
 */
actual fun measureGlyphBounds(
    text: String,
    fontSizePx: Float,
    fontBytes: ByteArray,
    fontWeightValue: Int
): GlyphBounds? {
    if (text.isEmpty()) return null

    return try {
        // 从字节数据创建 Typeface
        // Android 没有直接从字节创建 Typeface 的 API，需要写入临时文件
        val tempFile = File.createTempFile("glyph_font_", ".ttf")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(fontBytes)
            }
            val typeface = Typeface.createFromFile(tempFile)

            val paint = Paint().apply {
                this.typeface = typeface
                this.textSize = fontSizePx
                this.isAntiAlias = true
            }

            val rect = Rect()
            paint.getTextBounds(text, 0, text.length, rect)

            // rect.top 是负值（baseline 以上的部分），取绝对值得到 ascent
            // rect.bottom 是正值或零（baseline 以下的部分）
            val ascentPx = (-rect.top).toFloat().coerceAtLeast(0f)
            val descentPx = rect.bottom.toFloat().coerceAtLeast(0f)
            val inkWidth = rect.width().toFloat().coerceAtLeast(0f)

            GlyphBounds(ascentPx, descentPx, inkWidth)
        } finally {
            tempFile.delete()
        }
    } catch (e: Exception) {
        null
    }
}
