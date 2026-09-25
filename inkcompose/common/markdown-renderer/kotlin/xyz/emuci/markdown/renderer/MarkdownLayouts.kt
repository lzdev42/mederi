package xyz.emuci.markdown.renderer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import xyz.emuci.markdown.parser.ast.BlankLine
import xyz.emuci.markdown.parser.ast.ContainerNode
import xyz.emuci.markdown.parser.ast.Node
import xyz.emuci.markdown.renderer.block.BlockRenderer

@Composable
internal fun MarkdownBlockChildren(
    parent: ContainerNode,
    modifier: Modifier = Modifier,
) {
    val blockNodes = parent.children.filter { it !is BlankLine }
    MarkdownBlockColumn(blocks = blockNodes, modifier = modifier)
}

@Composable
internal fun MarkdownBlockColumn(
    blocks: List<Node>,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMarkdownTheme.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(theme.blockSpacing),
    ) {
        MarkdownBlockItems(blocks)
    }
}

@Composable
private fun MarkdownBlockItems(blocks: List<Node>) {
    for (node in blocks) {
        key(node::class, node.stableKey) {
            BlockRenderer(node = node)
        }
    }
}
