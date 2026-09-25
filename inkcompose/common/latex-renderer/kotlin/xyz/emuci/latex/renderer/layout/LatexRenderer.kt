package xyz.emuci.latex.renderer.layout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.renderer.model.HighlightRange
import xyz.emuci.latex.renderer.model.RenderContext
import xyz.emuci.latex.renderer.utils.LayoutUtils
import xyz.emuci.latex.renderer.utils.MathConstants

/**
 * LaTeX 渲染结果：包含测量布局、padding、高亮区域等所有绘制所需信息。
 *
 * 由 [LatexRenderer.measure] 生成，[LatexRenderer.draw] 和外部导出共同消费。
 * 确保 Composable 渲染和离屏导出使用完全相同的逻辑，避免代码重复。
 *
 * @property layout 测量后的节点布局（含 draw lambda）
 * @property horizontalPadding 水平内边距（px）
 * @property verticalPadding 垂直内边距（px）
 * @property canvasWidth 完整画布宽度 = layout.width + 2 * horizontalPadding
 * @property canvasHeight 完整画布高度 = layout.height + 2 * verticalPadding
 * @property highlightRects 高亮矩形区域列表（与 HighlightRange 配对）
 */
internal class LatexRenderResult(
    val layout: NodeLayout,
    val horizontalPadding: Float,
    val verticalPadding: Float,
    val canvasWidth: Float,
    val canvasHeight: Float,
    val highlightRects: List<Pair<HighlightRect, HighlightRange>>
)

/**
 * LaTeX 核心渲染器：封装「测量 + 绘制」的共享逻辑。
 *
 * [LatexDocument][xyz.emuci.latex.renderer.Latex] Composable 和
 * [LatexExporterState][xyz.emuci.latex.renderer.export.LatexExporterState]
 * 共同使用此对象，确保两条路径的渲染结果完全一致。
 */
internal object LatexRenderer {

    /**
     * 测量 LaTeX 节点列表，生成 [LatexRenderResult]。
     *
     * 包含：measureGroup → padding 计算 → 高亮区域计算。
     *
     * @param highlightRanges 高亮区域配置（从 LatexConfig 传入，不参与渲染树遍历）
     * @param layoutMap 可选的布局映射表，用于编辑器集成。传入非 null 值时，
     *   测量过程中会将每个子节点的相对位置记录到其中。
     */
    fun measure(
        children: List<LatexNode>,
        context: RenderContext,
        textMeasurer: TextMeasurer,
        density: Density,
        highlightRanges: List<HighlightRange> = emptyList(),
        layoutMap: LayoutMap? = null,
        cache: LayoutCache? = null
    ): LatexRenderResult {
        // 清除每次测量周期的缓存（字体/字号可能已变化）
        LayoutUtils.clearCache()

        // 预计算公式编号：遍历 AST 建立 label → 编号映射
        val labelToNumber = EquationNumbering.buildLabelMap(children)
        val numberingState = EquationNumberingState(labelToNumber)
        val numberedContext = context.copy(equationNumbering = numberingState)

        val layout = measureGroup(children, numberedContext, textMeasurer, density, layoutMap, cache)

        val fontSizePx = with(density) { context.fontSize.toPx() }
        val horizontalPadding = fontSizePx * MathConstants.CANVAS_HORIZONTAL_PADDING
        val verticalPadding = fontSizePx * MathConstants.CANVAS_VERTICAL_PADDING

        val canvasWidth = layout.width + horizontalPadding * 2
        val canvasHeight = layout.height + verticalPadding * 2

        val highlightRects = if (highlightRanges.isEmpty()) {
            emptyList()
        } else {
            HighlightCalculator.computeHighlightRects(
                children, highlightRanges, context, textMeasurer, density, layout, cache
            )
        }

        return LatexRenderResult(
            layout = layout,
            horizontalPadding = horizontalPadding,
            verticalPadding = verticalPadding,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            highlightRects = highlightRects
        )
    }

    /**
     * 将 [LatexRenderResult] 绘制到 [DrawScope]。
     *
     * 绘制顺序：背景 → 高亮矩形 → 内容。
     * 与 LatexDocument 的 Canvas 绘制逻辑完全一致。
     *
     * @param backgroundColor 背景颜色（Transparent 或 Unspecified 时跳过）
     */
    fun DrawScope.draw(
        renderResult: LatexRenderResult,
        backgroundColor: Color = Color.Transparent
    ) {
        val hPad = renderResult.horizontalPadding
        val vPad = renderResult.verticalPadding

        // 1. 绘制背景
        if (backgroundColor != Color.Unspecified && backgroundColor != Color.Transparent) {
            drawRect(color = backgroundColor)
        }

        // 2. 绘制高亮背景（在内容之前绘制，作为底层）
        for ((rect, range) in renderResult.highlightRects) {
            drawRect(
                color = range.color,
                topLeft = Offset(rect.x + hPad, rect.y + vPad),
                size = Size(rect.width, rect.height)
            )
            range.borderColor?.let { borderColor ->
                drawRect(
                    color = borderColor,
                    topLeft = Offset(rect.x + hPad, rect.y + vPad),
                    size = Size(rect.width, rect.height),
                    style = Stroke(1f)
                )
            }
        }

        // 3. 绘制内容
        renderResult.layout.draw(this, hPad, vPad)
    }
}
