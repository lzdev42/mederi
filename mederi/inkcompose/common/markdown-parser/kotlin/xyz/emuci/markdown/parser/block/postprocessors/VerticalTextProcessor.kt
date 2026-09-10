package xyz.emuci.markdown.parser.block.postprocessors

import xyz.emuci.markdown.parser.ast.*

/**
 * 后处理：将 info string 为 `vlr` 的 FencedCodeBlock 转换为 VerticalTextBlock。
 */
class VerticalTextProcessor : PostProcessor {
    override val priority: Int = 310

    override fun process(document: Document) {
        processRecursive(document)
    }

    private fun processRecursive(node: Node) {
        if (node is ContainerNode) {
            val children = node.children.toList()
            for (child in children) {
                if (child is FencedCodeBlock && child.language.lowercase() in VTEXT_LANGUAGES) {
                    val vtext = VerticalTextBlock(
                        literal = child.literal,
                    )
                    vtext.lineRange = child.lineRange
                    vtext.sourceRange = child.sourceRange
                    vtext.contentHash = child.contentHash
                    node.replaceChild(child, vtext)
                } else {
                    processRecursive(child)
                }
            }
        }
    }

    companion object {
        private val VTEXT_LANGUAGES = setOf("vlr")
    }
}
