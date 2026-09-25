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
                    val pairs = child.attributes.pairs

                    val height = pairs["height"] ?: pairs["h"]
                    val fontSize = pairs["fontSize"] ?: pairs["font-size"] ?: pairs["size"]
                    val wrapStr = pairs["wrap"] ?: pairs["autowrap"] ?: pairs["auto-wrap"]
                    val wrap = when (wrapStr?.lowercase()?.trim()) {
                        "true", "1", "yes" -> true
                        "false", "0", "no" -> false
                        else -> {
                            if (child.attributes.classes.contains("wrap")) true
                            else if (child.attributes.classes.contains("nowrap") || child.attributes.classes.contains("no-wrap")) false
                            else null
                        }
                    }

                    val vtext = VerticalTextBlock(
                        literal = child.literal,
                        height = height,
                        fontSize = fontSize,
                        wrap = wrap,
                        attributes = child.attributes,
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
