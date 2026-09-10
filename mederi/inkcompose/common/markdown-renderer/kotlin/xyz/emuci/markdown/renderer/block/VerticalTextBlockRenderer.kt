package xyz.emuci.markdown.renderer.block

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import xyz.emuci.markdown.parser.ast.VerticalTextBlock
import xyz.emuci.markdown.renderer.LocalMarkdownTheme
import xyz.emuci.markdown.renderer.internal.core.identity.RenderIdentity
import xyz.emuci.markdown.renderer.internal.core.model.VerticalTextBlockWidgetModel
import xyz.emuci.vtext.VTextView

/**
 * 竖排文字块渲染器。
 *
 * 将 ` ```vlr ` 代码块内容交给 vtext-render 的 [VTextView] 以竖排方式渲染。
 * 蒙古文/满文随列旋转保持连写形态，汉字/假名/韩文等直立字符反向补偿保持直立。
 */
@Composable
internal fun VerticalTextBlockRenderer(
    node: VerticalTextBlock,
    modifier: Modifier = Modifier,
) {
    RenderVerticalTextBlockWidgetModel(
        model = VerticalTextBlockWidgetModel(
            identity = RenderIdentity(
                stableId = node.stableKey.toLong(),
                contentRevision = node.contentHash,
                layoutRevision = node.contentHash,
                paintRevision = 0L,
            ),
            text = node.literal,
        ),
        modifier = modifier,
    )
}

@Composable
internal fun RenderVerticalTextBlockWidgetModel(
    model: VerticalTextBlockWidgetModel,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMarkdownTheme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.codeBlockCornerRadius))
            .background(theme.codeBlockBackground)
            .padding(theme.codeBlockPadding)
            .heightIn(min = 120.dp),
    ) {
        VTextView(
            text = model.text.trimEnd('\n'),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
