package xyz.emuci.markdown.renderer.internal.layout.inline

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

private val WHITESPACE_BOUNDARY_REGEX = Regex("(?<=\\s)|(?=\\s)")

internal fun computeInlineFlowLayout(
    input: InlineFlowInput,
    style: TextStyle,
    density: Density,
    textMeasurer: TextMeasurer,
    maxWidthPx: Float,
    maxLines: Int,
): InlineFlowLayout {
    if (maxLines <= 0 || maxWidthPx <= 0f) {
        return InlineFlowLayout(
            widthPx = maxWidthPx.coerceAtLeast(0f),
            heightPx = 0f,
            lines = emptyList(),
        )
    }

    val textStyle = textMeasurementStyle(style)
    val baseLineHeightPx = baseLineHeightPx(style, density)
    val baseMetrics = textMeasurer.measure(
        text = "Ag",
        style = textStyle,
        constraints = Constraints(maxWidth = Int.MAX_VALUE),
        maxLines = 1,
        softWrap = false,
    )
    val baseTextHeightPx = baseMetrics.size.height.toFloat()
    val baseTextBaselinePx = baseMetrics.firstBaseline

    data class MeasuredText(val widthPx: Float, val heightPx: Float, val baselinePx: Float)

    fun measureText(a: AnnotatedString): MeasuredText {
        if (a.isEmpty()) return MeasuredText(0f, 0f, 0f)
        val res = textMeasurer.measure(
            text = a,
            style = textStyle,
            constraints = Constraints(maxWidth = Int.MAX_VALUE),
            maxLines = 1,
            softWrap = false,
        )
        return MeasuredText(
            widthPx = res.size.width.toFloat(),
            heightPx = res.size.height.toFloat(),
            baselinePx = res.firstBaseline,
        )
    }

    fun inlineSizePx(placeholder: InlinePlaceholderLayoutSpec): Pair<Float, Float> {
        return placeholderSizePx(placeholder)
    }

    val lines = ArrayList<InlineFlowLine>()
    var currentItems = ArrayList<LineItem>()
    var currentWidth = 0f
    var maxItemHeight = 0f
    var lineCount = 0

    fun appendTextItem(
        text: AnnotatedString,
        measured: MeasuredText,
        widthPx: Float = measured.widthPx,
        sourceStart: Int? = null,
    ) {
        currentItems.add(
            LineItem.TextItem(
                text = text,
                widthPx = widthPx.coerceAtLeast(0f),
                heightPx = measured.heightPx,
                baselinePx = measured.baselinePx,
                sourceStart = sourceStart,
                sourceEnd = sourceStart?.plus(text.length),
            )
        )
        currentWidth += widthPx.coerceAtLeast(0f)
        maxItemHeight = maxOf(maxItemHeight, measured.heightPx)
    }

    fun flushLine(force: Boolean = false) {
        if (!force && currentItems.isEmpty()) return
        val textItems = currentItems.filterIsInstance<LineItem.TextItem>()
        val maxTextAscentPx = textItems.maxOfOrNull { it.baselinePx } ?: baseTextBaselinePx
        val maxTextDescentPx = textItems.maxOfOrNull { it.heightPx - it.baselinePx }
            ?: (baseTextHeightPx - baseTextBaselinePx)
        val alignedTextHeightPx = maxTextAscentPx + maxTextDescentPx
        val lineHeightPx = maxOf(baseLineHeightPx, maxItemHeight, alignedTextHeightPx)
        val textLeadingPx = ((lineHeightPx - alignedTextHeightPx) / 2f).coerceAtLeast(0f)
        lines += InlineFlowLine(
            textStyle = textStyle,
            lineWidthPx = currentWidth,
            lineHeightPx = lineHeightPx,
            baselinePx = textLeadingPx + maxTextAscentPx,
            textAlign = style.textAlign,
            items = currentItems.toList(),
        )
        currentItems = ArrayList()
        currentWidth = 0f
        maxItemHeight = 0f
        lineCount++
    }

    for (token in input.segments) {
        if (lineCount >= maxLines) break
        when (token) {
            InlineFlowSegment.Newline -> flushLine(force = true)
            is InlineFlowSegment.InlineRun -> {
                val (w, h) = inlineSizePx(token.placeholder)
                if (w <= 0f || h <= 0f) continue
                if (currentWidth > 0f && currentWidth + w > maxWidthPx) {
                    flushLine(force = true)
                    if (lineCount >= maxLines) break
                }
                currentItems.add(
                    LineItem.InlineItem(
                        id = token.id,
                        widthPx = w,
                        heightPx = h,
                        alternateText = token.placeholder.alternateText,
                        sourceStart = token.sourceStart,
                        sourceEnd = token.sourceEnd,
                    )
                )
                currentWidth += w
                maxItemHeight = maxOf(maxItemHeight, h)
            }

            is InlineFlowSegment.TextRun -> {
                var remaining = token.annotated
                var remainingSourceStart = token.sourceStart
                while (remaining.isNotEmpty() && lineCount < maxLines) {
                    val available = (maxWidthPx - currentWidth).coerceAtLeast(0f)
                    if (available <= 0f && currentItems.isNotEmpty()) {
                        flushLine(force = true)
                        continue
                    }
                    if (available <= 0f) break

                    if (currentWidth > 0f) {
                        // 行中起排：先确认剩余文本能否整体放进 available
                        val measured = measureText(remaining)
                        if (measured.widthPx <= available) {
                            appendTextItem(remaining, measured, sourceStart = remainingSourceStart)
                            break
                        }
                        // 放不下：软换行探测 available 内的物理断点
                        val probe = textMeasurer.measure(
                            text = remaining,
                            style = textStyle,
                            constraints = Constraints(maxWidth = ceil(available).toInt().coerceAtLeast(1)),
                            maxLines = 1,
                            softWrap = true,
                        )
                        val probeEnd = if (probe.lineCount > 0) {
                            val vEnd = probe.getLineEnd(lineIndex = 0, visibleEnd = true)
                            if (vEnd > 0) vEnd else probe.getLineEnd(lineIndex = 0, visibleEnd = false)
                        } else {
                            0
                        }
                        val cut = safeBreakIndex(remaining.text, probeEnd)
                        if (cut in 1 until remaining.length) {
                            val fit = remaining.subSequence(0, cut)
                            val fitMeasured = measureText(fit)
                            if (fitMeasured.widthPx <= available) {
                                appendTextItem(fit, fitMeasured, sourceStart = remainingSourceStart)
                                flushLine(force = true)
                                val rest = remaining.subSequence(cut, remaining.length)
                                val leadingSpaces = rest.leadingSpaceCount()
                                remainingSourceStart = remainingSourceStart
                                    ?.plus(cut + leadingSpaces)
                                remaining = rest.dropLeadingSpaces(leadingSpaces)
                                continue
                            }
                        }
                        // 首个断点都放不进 available（如 CJK 行尾只剩 1px）：腾空当前行重试
                        flushLine(force = true)
                        continue
                    }

                    // 整行起排：一次软换行测量拿到该 run 的全部物理行，逐行产出。
                    // 折行成本 O(N)；旧实现每行都对剩余全文做无边界测量 + 二分查找，为 O(N²·logN)。
                    val lineLayout = textMeasurer.measure(
                        text = remaining,
                        style = textStyle,
                        constraints = Constraints(maxWidth = ceil(maxWidthPx).toInt().coerceAtLeast(1)),
                        maxLines = Int.MAX_VALUE,
                        softWrap = true,
                    )
                    val total = remaining.length
                    var cursor = 0
                    var lineIndex = 0
                    val epsilon = 0.5f
                    while (cursor < total && lineCount < maxLines) {
                        val rawEnd = if (lineIndex < lineLayout.lineCount) {
                            if (lineIndex + 1 < lineLayout.lineCount) {
                                val visibleEnd = lineLayout.getLineEnd(lineIndex, visibleEnd = true)
                                if (visibleEnd > cursor) visibleEnd else lineLayout.getLineEnd(lineIndex, visibleEnd = false)
                            } else {
                                lineLayout.getLineEnd(lineIndex, visibleEnd = false)
                            }
                        } else {
                            total
                        }
                        val end = safeBreakIndex(remaining.text, rawEnd).coerceIn(cursor + 1, total)
                        val segment = remaining.subSequence(cursor, end)
                        val measured = measureText(segment)
                        if (measured.widthPx > maxWidthPx + epsilon && segment.length > 1) {
                            // 不可断行的超长内容（超长单词/URL 无断点）：按字形紧急断行，贪心填满可用宽度
                            var pieceCursor = cursor
                            while (pieceCursor < end && lineCount < maxLines) {
                                val step = glyphAdvanceAt(remaining.text, pieceCursor, end)
                                val piece = remaining.subSequence(pieceCursor, pieceCursor + step)
                                val pieceMeasured = measureText(piece)
                                if (currentWidth > 0f && currentWidth + pieceMeasured.widthPx > maxWidthPx + epsilon) {
                                    flushLine(force = true)
                                    if (lineCount >= maxLines) break
                                }
                                appendTextItem(
                                    piece,
                                    pieceMeasured,
                                    sourceStart = remainingSourceStart?.plus(pieceCursor),
                                )
                                pieceCursor += step
                            }
                            val nextCursor = if (lineIndex + 1 < lineLayout.lineCount) {
                                lineLayout.getLineStart(lineIndex + 1).coerceAtLeast(end)
                            } else {
                                total
                            }
                            if (nextCursor < total && currentItems.isNotEmpty()) {
                                flushLine(force = true)
                            }
                            cursor = nextCursor
                        } else {
                            appendTextItem(segment, measured, sourceStart = remainingSourceStart?.plus(cursor))
                            val nextCursor = if (lineIndex + 1 < lineLayout.lineCount) {
                                lineLayout.getLineStart(lineIndex + 1).coerceAtLeast(end)
                            } else {
                                total
                            }
                            if (nextCursor < total) {
                                // 本 run 还有后续物理行：收行；末行保持开放给后续 token
                                flushLine(force = true)
                            }
                            cursor = nextCursor
                        }
                        lineIndex++
                    }
                    break
                }
            }
        }
    }
    if (lineCount < maxLines) flushLine(force = false)


    val totalHeightPx = lines.sumOf { it.lineHeightPx.toDouble() }.toFloat()
    val firstBaselinePx = lines.firstOrNull()?.baselinePx
    val lastBaselinePx = if (lines.isEmpty()) null else {
        var y = 0f
        for (i in 0 until lines.lastIndex) {
            y += lines[i].lineHeightPx
        }
        y + lines.last().baselinePx
    }

    return InlineFlowLayout(
        widthPx = maxWidthPx,
        heightPx = totalHeightPx,
        firstBaselinePx = firstBaselinePx,
        lastBaselinePx = lastBaselinePx,
        lines = lines,
    )
}

/**
 * [start, end) 内从 [start] 开始取一个"字形安全"的步长：
 * 普通字符 1，代理对（emoji 等）2。用于超长不可断内容的紧急按字形断行。
 */
private fun glyphAdvanceAt(text: String, start: Int, end: Int): Int {
    val ch = text[start]
    return if (ch.isHighSurrogate() && start + 1 < end && text[start + 1].isLowSurrogate()) 2 else 1
}

private fun safeBreakIndex(text: String, index: Int): Int {
    val bounded = index.coerceIn(0, text.length)
    if (bounded in 1 until text.length &&
        text[bounded - 1].isHighSurrogate() &&
        text[bounded].isLowSurrogate()
    ) {
        return bounded - 1
    }
    return bounded
}

private fun AnnotatedString.leadingSpaceCount(): Int {
    val s = text
    var i = 0
    while (i < s.length && s[i].isWhitespace() && s[i] != '\n') i++
    return i
}

private fun AnnotatedString.dropLeadingSpaces(count: Int): AnnotatedString {
    return if (count == 0) this else subSequence(count, length)
}

internal fun textMeasurementStyle(style: TextStyle): TextStyle {
    return if (style.lineHeight.value.isNaN()) style else style.copy(lineHeight = TextUnit.Unspecified)
}

internal fun baseLineHeightPx(style: TextStyle, density: Density): Float = with(density) {
    val lh = style.lineHeight.value.takeUnless { it.isNaN() }
        ?: style.fontSize.value.takeUnless { it.isNaN() }?.times(1.5f)
        ?: 0f
    lh.sp.toPx()
}.coerceAtLeast(0f)

internal fun placeholderSizePx(
    placeholder: InlinePlaceholderLayoutSpec,
): Pair<Float, Float> {
    return placeholder.widthPx to placeholder.heightPx
}

internal fun computeMaxIntrinsicWidthPx(
    input: InlineFlowInput,
    style: TextStyle,
    textMeasurer: TextMeasurer,
): Int {
    val textStyle = textMeasurementStyle(style)
    var lineWidth = 0f
    var maxLineWidth = 0f
    for (token in input.segments) {
        when (token) {
            InlineFlowSegment.Newline -> {
                maxLineWidth = maxOf(maxLineWidth, lineWidth)
                lineWidth = 0f
            }

            is InlineFlowSegment.InlineRun -> {
                lineWidth += placeholderSizePx(token.placeholder).first
            }

            is InlineFlowSegment.TextRun -> {
                if (token.annotated.isEmpty()) continue
                val width = textMeasurer.measure(
                    text = token.annotated,
                    style = textStyle,
                    constraints = Constraints(maxWidth = Int.MAX_VALUE),
                    maxLines = 1,
                    softWrap = false,
                ).size.width.toFloat()
                lineWidth += width
            }
        }
    }
    maxLineWidth = maxOf(maxLineWidth, lineWidth)
    return ceil(maxLineWidth).toInt()
}

internal fun computeMinIntrinsicWidthPx(
    input: InlineFlowInput,
    style: TextStyle,
    textMeasurer: TextMeasurer,
): Int {
    val textStyle = textMeasurementStyle(style)
    var maxPieceWidth = 0f
    for (token in input.segments) {
        when (token) {
            InlineFlowSegment.Newline -> Unit
            is InlineFlowSegment.InlineRun -> {
                maxPieceWidth =
                    maxOf(maxPieceWidth, placeholderSizePx(token.placeholder).first)
            }

            is InlineFlowSegment.TextRun -> {
                val pieces = token.annotated.text.split(WHITESPACE_BOUNDARY_REGEX)
                var cursor = 0
                for (piece in pieces) {
                    if (piece.isEmpty()) continue
                    val end = cursor + piece.length
                    val sub = token.annotated.subSequence(cursor, end)
                    val width = textMeasurer.measure(
                        text = sub,
                        style = textStyle,
                        constraints = Constraints(maxWidth = Int.MAX_VALUE),
                        maxLines = 1,
                        softWrap = false,
                    ).size.width.toFloat()
                    maxPieceWidth = maxOf(maxPieceWidth, width)
                    cursor = end
                }
            }
        }
    }
    return ceil(maxPieceWidth).toInt()
}
