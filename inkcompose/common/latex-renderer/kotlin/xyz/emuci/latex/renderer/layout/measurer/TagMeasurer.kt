package xyz.emuci.latex.renderer.layout.measurer

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.renderer.layout.NodeLayout
import xyz.emuci.latex.renderer.model.RenderContext
import kotlin.reflect.KClass

/**
 * 标签测量器 — 处理 \tag{label}, \tag*{label}
 */
internal class TagMeasurer : NodeMeasurer {

    override val handledNodeTypes: Set<KClass<out LatexNode>> = setOf(
        LatexNode.Tag::class
    )

    override fun measure(
        node: LatexNode,
        context: RenderContext,
        measurer: TextMeasurer,
        density: Density,
        measureNode: (LatexNode, RenderContext) -> NodeLayout,
        measureGroup: (List<LatexNode>, RenderContext) -> NodeLayout
    ): NodeLayout {
        node as LatexNode.Tag
        if (node.starred && node.label.children().isEmpty()) {
            return NodeLayout(0f, 0f, 0f) { _, _ -> }
        }
        val labelLayout = measureNode(node.label, context)
        val fontSizePx = with(density) { context.fontSize.toPx() }
        val gap = fontSizePx * 1.5f

        if (node.starred) {
            val totalWidth = gap + labelLayout.width
            return NodeLayout(totalWidth, labelLayout.height, labelLayout.baseline) { x, y ->
                labelLayout.draw(this, x + gap, y)
            }
        } else {
            // Parentheses must use the same KaTeX glyph measurement as the
            // label. Drawing raw TextLayoutResults from the same top position
            // mixes a full line box with the label's trimmed ink box and makes
            // the parentheses appear lower.
            val leftParen = measureNode(LatexNode.Text("("), context)
            val rightParen = measureNode(LatexNode.Text(")"), context)

            val baseline = maxOf(leftParen.baseline, labelLayout.baseline, rightParen.baseline)
            val depth = maxOf(
                leftParen.height - leftParen.baseline,
                labelLayout.height - labelLayout.baseline,
                rightParen.height - rightParen.baseline
            )
            val totalWidth = gap + leftParen.width + labelLayout.width + rightParen.width
            val height = baseline + depth

            return NodeLayout(totalWidth, height, baseline) { x, y ->
                leftParen.draw(this, x + gap, y + baseline - leftParen.baseline)
                labelLayout.draw(
                    this,
                    x + gap + leftParen.width,
                    y + baseline - labelLayout.baseline
                )
                rightParen.draw(
                    this,
                    x + gap + leftParen.width + labelLayout.width,
                    y + baseline - rightParen.baseline
                )
            }
        }
    }
}
