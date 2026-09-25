package xyz.emuci.markdown.renderer.block

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import xyz.emuci.markdown.parser.ast.BlockQuote
import xyz.emuci.markdown.renderer.LocalMarkdownTheme
import xyz.emuci.markdown.renderer.MarkdownBlockChildren
import xyz.emuci.markdown.renderer.internal.core.model.BlockQuoteBlockModel

/**
 * 块引用渲染器 (> ...)
 * 左侧绘制竖线，内部递归渲染子块。
 */
@Composable
internal fun BlockQuoteRenderer(
    node: BlockQuote,
    modifier: Modifier = Modifier,
) {
    RenderBlockQuoteContainer(modifier = modifier) {
        MarkdownBlockChildren(node)
    }
}

@Composable
internal fun RenderBlockQuoteBlockModel(
    model: BlockQuoteBlockModel,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    RenderBlockQuoteContainer(modifier = modifier, content = content)
}

@Composable
private fun RenderBlockQuoteContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val theme = LocalMarkdownTheme.current
    val borderColor = theme.blockQuoteBorderColor
    val borderWidthPx = theme.blockQuoteBorderWidth

    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val strokeWidth = borderWidthPx.toPx()
                drawLine(
                    color = borderColor,
                    start = Offset(strokeWidth / 2, 0f),
                    end = Offset(strokeWidth / 2, size.height),
                    strokeWidth = strokeWidth,
                )
            }
            .padding(start = theme.blockQuotePadding + theme.blockQuoteBorderWidth),
    ) {
        content()
    }
}
