package xyz.emuci.latex.renderer.utils

/**
 * 字形精确墨水边界。
 *
 * 由平台原生 API 测量得到，表示字形实际绘制像素的精确矩形区域。
 * 所有值均为像素 (px) 单位。
 *
 * 坐标系以 baseline 为 y=0 原点：
 * - ascentPx > 0 表示 baseline 以上的墨水高度
 * - descentPx > 0 表示 baseline 以下的墨水高度
 * - inkHeight = ascentPx + descentPx
 *
 * @property ascentPx baseline 以上的墨水高度（正值，向上为正）
 * @property descentPx baseline 以下的墨水高度（正值，向下为正）
 * @property inkWidth 墨水区域宽度
 * @property inkHeight 墨水区域总高度 = ascentPx + descentPx
 */
class GlyphBounds(
    val ascentPx: Float,
    val descentPx: Float,
    val inkWidth: Float
) {
    val inkHeight: Float get() = ascentPx + descentPx
}

/**
 * 使用平台原生 API 测量字形的精确墨水边界。
 *
 * 各平台实现：
 * - Android: android.graphics.Paint.getTextBounds() + Typeface.createFromAsset()
 * - JVM: org.jetbrains.skia.Font.measureText() + Typeface.makeFromData()
 * - iOS: org.jetbrains.skia.Font (Compose for iOS 底层也是 Skia)
 * - JS/WASM: org.jetbrains.skia.Font (Compose for Web 底层也是 Skia)
 *
 * @param text 要测量的文本（通常是单个字符或运算符符号）
 * @param fontSizePx 字号大小，像素单位
 * @param fontBytes KaTeX TTF 字体文件的字节数据，用于创建原生字体对象
 * @param fontWeightValue 字重值 (100-900)，默认 400 (Normal)
 * @return 精确的墨水边界，如果测量失败返回 null
 */
expect fun measureGlyphBounds(
    text: String,
    fontSizePx: Float,
    fontBytes: ByteArray,
    fontWeightValue: Int = 400
): GlyphBounds?
