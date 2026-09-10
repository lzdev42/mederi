package xyz.emuci.latex.renderer.layout.measurer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.renderer.layout.NodeLayout
import xyz.emuci.latex.renderer.model.RenderContext
import kotlin.reflect.KClass

/**
 * 否定修饰测量器 — 处理 \not
 */
internal class NegationMeasurer : NodeMeasurer {

    override val handledNodeTypes: Set<KClass<out LatexNode>> = setOf(
        LatexNode.Negation::class
    )

    override fun measure(
        node: LatexNode,
        context: RenderContext,
        measurer: TextMeasurer,
        density: Density,
        measureNode: (LatexNode, RenderContext) -> NodeLayout,
        measureGroup: (List<LatexNode>, RenderContext) -> NodeLayout
    ): NodeLayout {
        node as LatexNode.Negation
        val contentLayout = measureNode(node.content, context)
        val strokeWidth = with(density) { 1.5f.dp.toPx() }
        val slashPadding = contentLayout.width * 0.1f

        return NodeLayout(
            contentLayout.width,
            contentLayout.height,
            contentLayout.baseline
        ) { x, y ->
            contentLayout.draw(this, x, y)
            drawLine(
                color = context.color,
                start = Offset(x + slashPadding, y + contentLayout.height * 0.85f),
                end = Offset(
                    x + contentLayout.width - slashPadding,
                    y + contentLayout.height * 0.15f
                ),
                strokeWidth = strokeWidth
            )
        }
    }
}
