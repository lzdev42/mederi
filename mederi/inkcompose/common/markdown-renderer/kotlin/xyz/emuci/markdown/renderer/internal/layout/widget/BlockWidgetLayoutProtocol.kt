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
    val preferredHeight = if (widget.height != null) {
        widget.height.value * 2f
    } else {
        val lines = widget.text.lines()
        val maxCharsPerColumn = lines.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        maxCharsPerColumn * 24f + 20f
    }
    return BlockWidgetMeasurement(
        widthPx = viewportWidthPx,
        heightPx = preferredHeight.coerceAtLeast(24f),
    )
}
