package xyz.emuci.latex.parser

import xyz.emuci.latex.parser.model.LatexNode
import xyz.emuci.latex.parser.model.SourceRange

/**
 * AST 源码位置导航工具
 *
 * 提供从源码偏移量到 AST 节点的双向映射能力，
 * 是编辑器集成（光标定位、选区高亮）的核心基础设施。
 */
object SourceMapper {

    /**
     * 获取包含指定偏移量的节点路径（从根到叶）
     *
     * @param root 文档根节点
     * @param offset 源码字符偏移量
     * @return 从根到最深匹配叶节点的路径，如果 offset 不在任何节点范围内则返回空列表
     */
    fun nodePathAt(root: LatexNode, offset: Int): List<LatexNode> {
        val path = mutableListOf<LatexNode>()
        findPath(root, offset, path)
        return path
    }

    /**
     * 获取包含指定偏移量的最深叶节点
     *
     * @param root 文档根节点
     * @param offset 源码字符偏移量
     * @return 最深的匹配节点，如果 offset 不在任何节点范围内则返回 null
     */
    fun leafNodeAt(root: LatexNode, offset: Int): LatexNode? {
        return nodePathAt(root, offset).lastOrNull()
    }

    /**
     * 收集 AST 中所有带 sourceRange 的叶节点（扁平化）
     * 按 sourceRange.start 排序
     */
    fun collectLeaves(root: LatexNode): List<LatexNode> {
        val leaves = mutableListOf<LatexNode>()
        collectLeavesImpl(root, leaves)
        return leaves.sortedBy { it.sourceRange?.start ?: 0 }
    }

    // --- 内部实现 ---

    private fun findPath(node: LatexNode, offset: Int, path: MutableList<LatexNode>): Boolean {
        val range = node.sourceRange ?: return findInChildren(node, offset, path)

        if (!range.contains(offset)) return false

        path.add(node)

        // 尝试在子节点中找到更深的匹配
        findInChildren(node, offset, path)
        return true
    }

    private fun findInChildren(node: LatexNode, offset: Int, path: MutableList<LatexNode>): Boolean {
        for (child in childrenOf(node)) {
            if (findPath(child, offset, path)) return true
        }
        return false
    }

    private fun collectLeavesImpl(node: LatexNode, result: MutableList<LatexNode>) {
        val children = childrenOf(node)
        if (children.isEmpty()) {
            if (node.sourceRange != null) {
                result.add(node)
            }
        } else {
            for (child in children) {
                collectLeavesImpl(child, result)
            }
        }
    }

    /**
     * 获取节点的直接子节点列表
     * 委托给 LatexNode 的自描述方法 children()
     */
    fun childrenOf(node: LatexNode): List<LatexNode> = node.children()
}
