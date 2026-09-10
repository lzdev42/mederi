package xyz.emuci.latex.parser.incremental

import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.parser.model.SourceRange

/**
 * AST 子树复用工具
 *
 * 参考 tree-sitter 的树复用策略：
 * 编辑发生后，根据 [TextEdit] 将旧 AST 的顶层子节点分为三类：
 * 1. **前缀节点**：sourceRange 完全在编辑起点之前 → 直接保留
 * 2. **脏节点**：sourceRange 与编辑区域重叠 → 必须重新解析
 * 3. **后缀节点**：sourceRange 完全在编辑终点之后 → 偏移平移后复用
 */
internal object TreeReuser {

    /**
     * 对旧 AST 的顶层子节点进行分区
     *
     * @param oldChildren 旧文档的顶层子节点
     * @param edit 编辑操作描述
     * @return [ReusePartition]：前缀/脏区域/后缀三段划分
     */
    fun partition(oldChildren: List<LatexNode>, edit: TextEdit): ReusePartition {
        if (oldChildren.isEmpty() || (edit.oldLength == 0 && edit.delta == 0)) {
            return ReusePartition(oldChildren, emptyList(), emptyList())
        }

        val prefixNodes = mutableListOf<LatexNode>()
        val dirtyNodes = mutableListOf<LatexNode>()
        val suffixNodes = mutableListOf<LatexNode>()

        for (node in oldChildren) {
            val range = node.sourceRange
            if (range == null) {
                // 无源位置信息的节点视为脏节点
                dirtyNodes.add(node)
                continue
            }

            when {
                // 节点完全在编辑起点之前 → 前缀
                range.end <= edit.startOffset -> {
                    if (dirtyNodes.isEmpty()) {
                        prefixNodes.add(node)
                    } else {
                        // 如果已经有脏节点，后续不能再归入前缀
                        dirtyNodes.add(node)
                    }
                }
                // 节点完全在编辑终点之后 → 后缀（需平移）
                range.start >= edit.oldEndOffset -> {
                    suffixNodes.add(node)
                }
                // 与编辑区域重叠 → 脏节点
                else -> {
                    dirtyNodes.add(node)
                }
            }
        }

        return ReusePartition(prefixNodes, dirtyNodes, suffixNodes)
    }

    /**
     * 将后缀节点的 sourceRange 平移 delta 偏移量
     *
     * @param nodes 后缀节点列表
     * @param delta 偏移量变化（新长度 - 旧长度）
     * @return 偏移平移后的节点列表
     */
    fun shiftNodes(nodes: List<LatexNode>, delta: Int): List<LatexNode> {
        if (delta == 0 || nodes.isEmpty()) return nodes
        return nodes.map { node -> shiftNode(node, delta) }
    }

    /**
     * 递归平移单个节点及其所有子节点的 sourceRange
     */
    private fun shiftNode(node: LatexNode, delta: Int): LatexNode {
        val oldRange = node.sourceRange ?: return node
        val newRange = SourceRange(oldRange.start + delta, oldRange.end + delta)
        val shiftedNode = node.withSourceRange(newRange)

        val children = node.children()
        if (children.isEmpty()) return shiftedNode

        val shiftedChildren = children.map { child -> shiftNode(child, delta) }
        return shiftedNode.withChildren(shiftedChildren)
    }
}

/**
 * AST 子树复用的三段划分结果
 *
 * @param prefix 编辑区域前的节点（可直接复用）
 * @param dirty 与编辑区域重叠的节点（需重新解析）
 * @param suffix 编辑区域后的节点（偏移平移后可复用）
 */
data class ReusePartition(
    val prefix: List<LatexNode>,
    val dirty: List<LatexNode>,
    val suffix: List<LatexNode>
)
