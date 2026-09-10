package xyz.emuci.vtext

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal const val DEFAULT_ASCENT_TRIM = 0.15f
internal const val DEFAULT_BASELINE_SHIFT = 0f
internal val DEFAULT_COLUMN_SPACING = 0.dp

/**
 * 竖排文字渲染配置。
 *
 * 控制蒙古文/满文竖排渲染的布局参数。所有参数均有合理默认值，
 * 多数场景下使用 [VTextConfig] 默认值即可。
 *
 * @param verticalFontFamily 蒙古文/满文专用字体，仅应用于对应字符区段。
 *   默认使用随库打包的 Noto Sans Mongolian。
 * @param ascentTrim 削减 Ascent 比例（0~1），调整列间距视觉效果。
 * @param baselineShift 基线偏移，正值下移、负值上移。
 * @param fixedWidth 是否强制等宽列（所有列宽取最大行高）。
 * @param columnSpacing 列间距。
 */
data class VTextConfig(
    val verticalFontFamily: FontFamily? = null,
    val ascentTrim: Float = DEFAULT_ASCENT_TRIM,
    val baselineShift: Float = DEFAULT_BASELINE_SHIFT,
    val fixedWidth: Boolean = true,
    val columnSpacing: Dp = DEFAULT_COLUMN_SPACING,
)

internal data class VTextInternalConfig(
    val verticalFontFamily: FontFamily,
    val ascentTrim: Float = DEFAULT_ASCENT_TRIM,
    val baselineShift: Float = DEFAULT_BASELINE_SHIFT,
    val fixedWidth: Boolean = true,
    val columnSpacing: Dp = DEFAULT_COLUMN_SPACING,
)

/**
 * 判断码点是否属于原生竖排文字系统（蒙古文/满文/锡伯文/八思巴文等）。
 * 这类字符在 Unicode 中侧卧存储，渲染时整体随列旋转，不做直立补偿。
 */
internal fun isVerticalFontSystem(codePoint: Int): Boolean = when {
    codePoint in 0x1800..0x18AF -> true   // 蒙古文、满文、锡伯文、托忒文等
    codePoint in 0x11660..0x1167F -> true // 蒙古文补充
    codePoint in 0xA840..0xA87F -> true   // 八思巴文
    else -> false
}
