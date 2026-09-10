package xyz.emuci.markdown.renderer.internal.layout.widget

import xyz.emuci.markdown.renderer.internal.core.model.BlockWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.CodeBlockWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.DiagramBlockWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.MathBlockWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.VerticalTextBlockWidgetModel
import xyz.emuci.markdown.renderer.internal.layout.engine.LayoutEnvironment
import xyz.emuci.markdown.renderer.internal.layout.model.BlockWidgetMeasurement
import kotlin.math.ceil
import kotlin.math.max

internal fun measureBlockWidget(
    widget: BlockWidgetModel,
    viewportWidthPx: Float,
    environment: LayoutEnvironment,
): BlockWidgetMeasurement {
    return when (widget) {
        is CodeBlockWidgetModel -> measureCodeWidget(widget, viewportWidthPx)
        is MathBlockWidgetModel -> measureMathWidget(widget, viewportWidthPx)
        is DiagramBlockWidgetModel -> measureDiagramWidget(widget, viewportWidthPx, environment)
        is VerticalTextBlockWidgetModel -> measureVerticalTextWidget(widget, viewportWidthPx)
    }
}

private fun measureCodeWidget(
    widget: CodeBlockWidgetModel,
    viewportWidthPx: Float,
): BlockWidgetMeasurement {
    val lineCount = widget.code.lineSequence().count().coerceAtLeast(1)
    val longestLine = widget.code.lineSequence().maxOfOrNull { it.length } ?: 0
    val charWidthPx = 8f
    val titleHeightPx = if (widget.title.isNullOrBlank()) 0f else 28f
    val horizontalPaddingPx = 24f
    val contentWidthPx = max(viewportWidthPx, longestLine * charWidthPx + horizontalPaddingPx)
    return BlockWidgetMeasurement(
        widthPx = contentWidthPx,
        heightPx = titleHeightPx + lineCount * 22f + 24f,
        scrollableHorizontally = contentWidthPx > viewportWidthPx,
    )
}

private fun measureMathWidget(
    widget: MathBlockWidgetModel,
    viewportWidthPx: Float,
): BlockWidgetMeasurement {
    val length = widget.latex.trim().length.coerceAtLeast(1)
    val wrappedLines = ceil((length * 10f) / viewportWidthPx.coerceAtLeast(160f)).toInt().coerceAtLeast(1)
    return BlockWidgetMeasurement(
        widthPx = viewportWidthPx,
        heightPx = 40f + wrappedLines * 28f,
    )
}

private fun measureDiagramWidget(
    widget: DiagramBlockWidgetModel,
    viewportWidthPx: Float,
    environment: LayoutEnvironment,
): BlockWidgetMeasurement {
    val cachedHeight = environment.diagramHostRegistry.cachedHeightPx(widget.hostKey)
    if (cachedHeight != null) {
        return BlockWidgetMeasurement(
            widthPx = viewportWidthPx,
            heightPx = cachedHeight,
        )
    }
    val lineCount = widget.code.lineSequence().count().coerceAtLeast(3)
    val preferredHeight = when (widget.diagramType.lowercase()) {
        "mermaid" -> 120f + lineCount * 10f
        else -> 100f + lineCount * 8f
    }
    return BlockWidgetMeasurement(
        widthPx = viewportWidthPx,
        heightPx = preferredHeight.coerceAtLeast(140f),
    )
}

private fun measureVerticalTextWidget(
    widget: VerticalTextBlockWidgetModel,
    viewportWidthPx: Float,
): BlockWidgetMeasurement {
    // 竖排文字：每列宽度约等于行高（字号 × 1.6），列数由文字长度决定
    val charCount = widget.text.length.coerceAtLeast(1)
    // 估算：每列可容纳字符数 = 视口高度 / 行高，按视口高约 200px 的保守估算
    val charsPerColumn = 8
    val preferredHeight = minOf(charCount, charsPerColumn) * 24f + 24f
    return BlockWidgetMeasurement(
        widthPx = viewportWidthPx,
        heightPx = preferredHeight.coerceAtLeast(120f),
    )
}
