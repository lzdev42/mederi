package xyz.emuci.latex.renderer.utils

import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr

/**
 * WASM JS 平台：使用 Skia (org.jetbrains.skia via Skiko) API 获取精确墨水边界。
 *
 * Compose for Web (WASM JS target) 底层通过 Skiko 使用 Skia 渲染引擎。
 * 实现与 JVM/iOS/JS 平台相同。
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

        val glyphIds = font.getStringGlyphs(text)
        if (glyphIds.isEmpty()) return null

        val glyphBoundsArray = font.getBounds(glyphIds)
        if (glyphBoundsArray.isEmpty()) return null

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
