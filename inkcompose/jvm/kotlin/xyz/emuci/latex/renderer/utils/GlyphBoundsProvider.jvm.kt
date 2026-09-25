package xyz.emuci.latex.renderer.utils

import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr

/**
 * JVM Desktop 平台：使用 Skia (org.jetbrains.skia) API 获取精确墨水边界。
 *
 * Compose for Desktop 底层使用 Skia 渲染引擎，可以直接访问 Skia API。
 * FontMgr.makeFromData() 从字节数据创建 Typeface，
 * Font.getBounds() 获取 glyph 的精确 bounding box。
 */
actual fun measureGlyphBounds(
    text: String,
    fontSizePx: Float,
    fontBytes: ByteArray,
    fontWeightValue: Int
): GlyphBounds? {
    if (text.isEmpty()) return null

    return try {
        val data = Data.makeFromBytes(fontBytes)
        val typeface = FontMgr.default.makeFromData(data) ?: return null
        val font = Font(typeface, fontSizePx)

        // 获取字符串对应的 glyph IDs
        val glyphIds = font.getStringGlyphs(text)
        if (glyphIds.isEmpty()) return null

        // 获取每个 glyph 的精确 bounding box
        val glyphBoundsArray = font.getBounds(glyphIds)
        if (glyphBoundsArray.isEmpty()) return null

        // 合并所有 glyph 的 bounds（处理多字符文本）
        // bounds 坐标系：原点在 glyph 的基线左端
        // top 为负值（baseline 以上），bottom 为正值（baseline 以下）
        var minTop = Float.MAX_VALUE
        var maxBottom = Float.MIN_VALUE

        for (rect in glyphBoundsArray) {
            if (rect.width > 0 || rect.height > 0) {
                minTop = minOf(minTop, rect.top)
                maxBottom = maxOf(maxBottom, rect.bottom)
            }
        }

        if (minTop == Float.MAX_VALUE) return null

        val ascentPx = (-minTop).coerceAtLeast(0f)
        val descentPx = maxBottom.coerceAtLeast(0f)
        val inkWidth = font.measureTextWidth(text)

        GlyphBounds(ascentPx, descentPx, inkWidth)
    } catch (e: Exception) {
        null
    }
}
