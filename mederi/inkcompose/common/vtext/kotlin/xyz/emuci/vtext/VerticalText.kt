package xyz.emuci.vtext

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * 每列（竖排中的一"行"）的布局指标。
 *
 * @param width     列宽（px），等于横排时的行高
 * @param inkCenter 墨水中心 Y（px），用于绘制时对齐旋转轴
 */
internal data class ColumnMetrics(
    val width: Float,
    val inkCenter: Float,
)

/** 根据 TextLayoutResult 计算每列的 ColumnMetrics。 */
internal fun TextLayoutResult.computeColumnMetrics(
    config: VTextInternalConfig,
    density: Density,
): List<ColumnMetrics> {
    var maxLineHeight = 0f
    for (i in 0 until lineCount) {
        val h = getLineBottom(i) - getLineTop(i)
        if (h > maxLineHeight) maxLineHeight = h
    }

    return List(lineCount) { i ->
        val layoutTop = getLineTop(i)
        val layoutBottom = getLineBottom(i)
        val lineHeight = layoutBottom - layoutTop
        val layoutCenter = (layoutTop + layoutBottom) / 2f

        var minTop = Float.MAX_VALUE
        var maxBottom = -Float.MAX_VALUE

        var j = getLineStart(i)
        while (j < getLineEnd(i)) {
            if (j > 0 && layoutInput.text[j].isLowSurrogate()) { j++; continue }
            val rect = getBoundingBox(j)
            if (rect.height > 0) {
                if (rect.top < minTop) minTop = rect.top
                if (rect.bottom > maxBottom) maxBottom = rect.bottom
            }
            j++
        }

        val finalWidth = if (config.fixedWidth) maxLineHeight else lineHeight

        if (minTop == Float.MAX_VALUE) {
            ColumnMetrics(finalWidth, layoutCenter)
        } else {
            val baseline = getLineBaseline(i)
            val trimmedTop = minTop + config.ascentTrim * (baseline - minTop)
            ColumnMetrics(finalWidth, (trimmedTop + maxBottom) / 2f)
        }
    }
}

private class VerticalLayoutHolder {
    var layoutResult: TextLayoutResult? = null
    var cachedMetrics: List<ColumnMetrics> = emptyList()
}

/**
 * 竖排文字渲染核心。
 *
 * 原理：用横排 TextMeasurer 测量文字，然后将每一"行"旋转 90° 作为竖排的一"列"。
 * 直立字符（汉字、假名、韩文等）在旋转后再反向旋转 -90° 补偿，保持直立。
 * 蒙古文/满文等原生竖排字符随列旋转，不做补偿。
 *
 * 布局模式为多列：列高 = 组件高度，填满换下一列。
 */
@Composable
internal fun VerticalText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    config: VTextInternalConfig,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val density = LocalDensity.current
    val columnSpacingPx = with(density) { config.columnSpacing.toPx() }
    val textMeasurer = rememberTextMeasurer()

    val contentColor = LocalContentColor.current
    val effectiveColor = if (style.color.isSpecified) {
        style.color
    } else if (contentColor.isSpecified) {
        contentColor
    } else {
        Color.Unspecified
    }

    val baseStyle = (if (config.baselineShift != 0f)
        style.copy(baselineShift = BaselineShift(config.baselineShift))
    else style).let {
        if (effectiveColor.isSpecified) it.copy(color = effectiveColor) else it
    }

    val measuredStyle = baseStyle.withNormalizedLineHeight()

    // 对蒙古文/满文区段应用竖排字体，其余区段保持原字体
    val annotatedText = remember(text, config.verticalFontFamily) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val segStart = i
                val cp = codePointAt(text, i)
                if (cp == 0) { i++; continue }
                val isVertical = isVerticalFontSystem(cp)

                while (i < text.length) {
                    val c = codePointAt(text, i)
                    if (c == 0) { i++; continue }
                    if (isVerticalFontSystem(c) != isVertical) break
                    i += if (c > 0xFFFF) 2 else 1
                }

                // VTextInternalConfig.verticalFontFamily 已保证非空，只需按竖排区段判断
                if (isVertical) {
                    withStyle(SpanStyle(fontFamily = config.verticalFontFamily)) {
                        append(text.substring(segStart, i))
                    }
                } else {
                    append(text.substring(segStart, i))
                }
            }
        }
    }

    val holder = remember { VerticalLayoutHolder() }

    Spacer(
        modifier = modifier
            .layout { measurable, constraints ->
                // 横排 maxWidth = 竖排列高，决定何时换列
                val measuredMaxWidth = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
                val result = textMeasurer.measure(
                    text = annotatedText,
                    style = measuredStyle,
                    constraints = Constraints(maxWidth = measuredMaxWidth),
                    softWrap = true,
                )
                onTextLayout(result)

                val metrics = result.computeColumnMetrics(config, density)
                holder.layoutResult = result
                holder.cachedMetrics = metrics

                // 组件尺寸：有界则填满父约束，无界则按内容尺寸
                val w = if (constraints.hasBoundedWidth) constraints.maxWidth else {
                    val spacingTotal = ((result.lineCount - 1) * columnSpacingPx).coerceAtLeast(0f)
                    (metrics.sumOf { it.width.toDouble() }.toFloat() + spacingTotal).toInt()
                        .coerceAtLeast(constraints.minWidth)
                }
                val h = if (constraints.hasBoundedHeight) constraints.maxHeight else {
                    result.size.width.coerceAtLeast(constraints.minHeight)
                }
                layout(w, h) { measurable.measure(Constraints.fixed(w, h)).place(0, 0) }
            }
            .drawBehind {
                val result = holder.layoutResult ?: return@drawBehind
                val metrics = holder.cachedMetrics
                val viewportWidth = size.width

                var currentX = 0f
                for (i in 0 until result.lineCount) {
                    val col = metrics.getOrNull(i) ?: continue
                    if (col.width <= 0f) continue

                    val halfW = col.width / 2f
                    val centerX = currentX + halfW + (i * columnSpacingPx)

                    // X 方向视口裁剪：列完全不可见则跳过
                    val screenLeft = centerX - halfW
                    val screenRight = centerX + halfW
                    if (screenRight < 0f || screenLeft > viewportWidth) {
                        currentX += col.width
                        continue
                    }

                    val lineStart = result.getLineStart(i)
                    val lineEnd = result.getLineEnd(i)
                    val clipTop = result.getLineTop(i)
                    val clipBottom = result.getLineBottom(i)

                    translate(left = centerX + col.inkCenter) {
                        rotate(degrees = 90f, pivot = Offset.Zero) {
                            clipRect(
                                left = 0f, top = clipTop,
                                right = result.size.width.toFloat(), bottom = clipBottom,
                                clipOp = ClipOp.Intersect,
                            ) {
                                drawColumnText(text, result, lineStart, lineEnd, color = effectiveColor)
                            }
                        }
                    }
                    currentX += col.width
                }
            }
    )
}

/**
 * 绘制一列内的文字，处理直立字符与旋转字符的混排。
 */
private fun DrawScope.drawColumnText(
    text: String,
    result: TextLayoutResult,
    lineStart: Int,
    lineEnd: Int,
    color: Color = Color.Unspecified,
) {
    // 先扫描本列是否含直立字符，没有则整列一次性绘制
    var hasUpright = false
    var j = lineStart
    while (j < lineEnd) {
        if (shouldBeUpright(text, j)) { hasUpright = true; break }
        if (text[j].isHighSurrogate() && j + 1 < lineEnd) j += 2 else j++
    }

    if (!hasUpright) {
        drawText(result, color = color)
        return
    }

    // 混排：直立字符单独反向旋转，其余字符合并区间绘制（保证蒙古文连写不被切割）
    j = lineStart
    while (j < lineEnd) {
        if (text[j].isLowSurrogate()) { j++; continue }
        val cp = codePointAt(text, j)
        if (cp == 0xFE0F || cp == 0x200D) { j++; continue }

        if (shouldBeUprightCodePoint(cp)) {
            val rect = result.getBoundingBox(j)
            if (rect.width > 0 && rect.height > 0) {
                rotate(degrees = -90f, pivot = rect.center) {
                    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                        drawText(result, color = color)
                    }
                }
            }
            j += if (cp > 0xFFFF) 2 else 1
        } else {
            // 合并连续的非直立字符区间
            var minLeft = Float.MAX_VALUE; var minTop = Float.MAX_VALUE
            var maxRight = -Float.MAX_VALUE; var maxBottom = -Float.MAX_VALUE
            var hasRect = false

            while (j < lineEnd) {
                val c = codePointAt(text, j)
                if (c == 0) { j++; continue }
                if (c == 0xFE0F || c == 0x200D) { j++; continue }
                if (shouldBeUprightCodePoint(c)) break
                val rect = result.getBoundingBox(j)
                if (rect.width > 0 && rect.height > 0) {
                    if (rect.left < minLeft) minLeft = rect.left
                    if (rect.top < minTop) minTop = rect.top
                    if (rect.right > maxRight) maxRight = rect.right
                    if (rect.bottom > maxBottom) maxBottom = rect.bottom
                    hasRect = true
                }
                j += if (c > 0xFFFF) 2 else 1
            }

            if (hasRect) {
                clipRect(minLeft, minTop, maxRight, maxBottom) { drawText(result, color = color) }
            }
        }
    }
}

// ── 字符分类 ──────────────────────────────────────────────────────────────────

/** 判断指定索引处的字符是否需要直立显示（汉字、假名、韩文等）。 */
private fun shouldBeUpright(text: String, index: Int): Boolean {
    val cp = codePointAt(text, index)
    return cp != 0 && shouldBeUprightCodePoint(cp)
}

/**
 * 判断码点是否需要直立显示。
 * 蒙古文/满文/八思巴文返回 false（随列旋转，不做直立补偿）。
 */
internal fun shouldBeUprightCodePoint(codePoint: Int): Boolean = when {
    codePoint in 0x4E00..0x9FFF   -> true  // CJK 统一汉字
    codePoint in 0x3400..0x4DBF   -> true  // CJK 扩展 A
    codePoint in 0x20000..0x3134F -> true  // CJK 扩展 B-F
    codePoint in 0xF900..0xFAFF   -> true  // CJK 兼容汉字
    codePoint in 0x2E80..0x2FDF   -> true  // CJK 部首
    codePoint in 0x31C0..0x31EF   -> true  // CJK 笔画
    codePoint in 0x3190..0x319F   -> true  // 竖排文字标号
    codePoint in 0xFE30..0xFE4F   -> true  // CJK 兼容形式
    codePoint in 0xAC00..0xD7AF   -> true  // 韩文音节
    codePoint in 0x1100..0x11FF   -> true  // 韩文字母
    codePoint in 0xA960..0xA97F   -> true  // 韩文字母扩展 A
    codePoint in 0xD7B0..0xD7FF   -> true  // 韩文字母扩展 B
    codePoint in 0x3040..0x309F   -> true  // 平假名
    codePoint in 0x30A0..0x30FF   -> true  // 片假名
    codePoint in 0x31F0..0x31FF   -> true  // 片假名语音扩展
    codePoint in 0x3100..0x312F   -> true  // 注音符号
    codePoint in 0x3000..0x303F   -> true  // CJK 标点
    codePoint in 0xFF00..0xFFEF   -> true  // 全角字符
    codePoint in 0x3200..0x33FF   -> true  // CJK 兼容字符
    // 蒙古文体系：随列旋转，不直立
    codePoint in 0x1800..0x18AF   -> false
    codePoint in 0x11660..0x1167F -> false
    codePoint in 0xA840..0xA87F   -> false
    codePoint in 0x1F000..0x1FBFF -> true  // Emoji
    codePoint in 0x2300..0x2BFF   -> true  // 常用符号
    codePoint in 0x1F1E6..0x1F1FF -> true  // 国旗
    else -> false
}

// ── 工具函数 ──────────────────────────────────────────────────────────────────

/** Unicode 码点解码，支持代理对。low surrogate 返回 0。 */
internal fun codePointAt(text: String, index: Int): Int {
    val c = text[index]
    return when {
        c.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() ->
            (c.code - 0xD800 shl 10) + (text[index + 1].code - 0xDC00) + 0x10000
        c.isLowSurrogate() -> 0
        else -> c.code
    }
}

private fun TextStyle.withNormalizedLineHeight(): TextStyle {
    val effectiveFontSize = if (fontSize.isSpecified) fontSize else 14.sp
    val effectiveLineHeight = if (lineHeight.isSpecified) lineHeight else effectiveFontSize * 1.6f
    return if (fontSize.isSpecified && lineHeight.isSpecified) this
    else copy(fontSize = effectiveFontSize, lineHeight = effectiveLineHeight)
}
