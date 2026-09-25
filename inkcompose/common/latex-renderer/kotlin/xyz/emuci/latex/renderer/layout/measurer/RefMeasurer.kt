package xyz.emuci.latex.renderer.layout.measurer

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.renderer.layout.NodeLayout
import xyz.emuci.latex.renderer.model.RenderContext
import kotlin.reflect.KClass

/**
 * 引用测量器 — 处理 \ref{key}, \eqref{key}
 */
internal class RefMeasurer : NodeMeasurer {

    override val handledNodeTypes: Set<KClass<out LatexNode>> = setOf(
        LatexNode.Ref::class,
        LatexNode.EqRef::class
    )

    override fun measure(
        node: LatexNode,
        context: RenderContext,
        measurer: TextMeasurer,
        density: Density,
        measureNode: (LatexNode, RenderContext) -> NodeLayout,
        measureGroup: (List<LatexNode>, RenderContext) -> NodeLayout
    ): NodeLayout = when (node) {
        is LatexNode.Ref -> measureRef(node, context, measureNode)
        is LatexNode.EqRef -> measureEqRef(node, context, measureNode)
        else -> throw IllegalArgumentException("Unsupported node type: ${node::class.simpleName}")
    }

    private fun measureRef(
        node: LatexNode.Ref,
        context: RenderContext,
        measureNode: (LatexNode, RenderContext) -> NodeLayout
    ): NodeLayout {
        // 优先从编号映射中查找实际编号，找不到则降级为键名
        val displayText = context.equationNumbering?.resolveLabel(node.key) ?: node.key
        return measureNode(LatexNode.Text(displayText), context)
    }

    private fun measureEqRef(
        node: LatexNode.EqRef,
        context: RenderContext,
        measureNode: (LatexNode, RenderContext) -> NodeLayout
    ): NodeLayout {
        // 优先从编号映射中查找实际编号，找不到则降级为键名
        val resolvedNumber = context.equationNumbering?.resolveLabel(node.key) ?: node.key
        return measureNode(LatexNode.Text("($resolvedNumber)"), context)
    }
}
