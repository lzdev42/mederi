package xyz.emuci.latex.renderer.layout.measurer

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.renderer.layout.NodeLayout
import xyz.emuci.latex.renderer.model.RenderContext
import xyz.emuci.latex.renderer.model.shrink
import xyz.emuci.latex.renderer.utils.MathConstants
import kotlin.reflect.KClass

/**
 * Substack 测量器 — 处理 \substack{line1 \\\\ line2}
 */
internal class SubstackMeasurer : NodeMeasurer {

    override val handledNodeTypes: Set<KClass<out LatexNode>> = setOf(
        LatexNode.Substack::class
    )

    override fun measure(
        node: LatexNode,
        context: RenderContext,
        measurer: TextMeasurer,
        density: Density,
        measureNode: (LatexNode, RenderContext) -> NodeLayout,
        measureGroup: (List<LatexNode>, RenderContext) -> NodeLayout
    ): NodeLayout {
        node as LatexNode.Substack
        if (node.rows.isEmpty()) {
            return NodeLayout(0f, 0f, 0f) { _, _ -> }
        }

        val substackContext = context.shrink(MathConstants.SCRIPT_SCALE)
        val fontSizePx = with(density) { substackContext.fontSize.toPx() }
        val rowSpacing = fontSizePx * 0.15f

        val rowLayouts = node.rows.map { measureGroup(it, substackContext) }
        val maxWidth = rowLayouts.maxOf { it.width }

        var totalHeight = 0f
        val positions = rowLayouts.map { layout ->
            val y = totalHeight
            totalHeight += layout.height + rowSpacing
            y
        }
        if (positions.isNotEmpty()) totalHeight -= rowSpacing

        val baseline = totalHeight / 2f

        return NodeLayout(maxWidth, totalHeight, baseline) { x, y ->
            rowLayouts.forEachIndexed { i, layout ->
                val rowX = x + (maxWidth - layout.width) / 2f
                layout.draw(this, rowX, y + positions[i])
            }
        }
    }
}
