package xyz.emuci.markdown.renderer.block

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.dp
import xyz.emuci.markdown.parser.ast.VerticalTextBlock
import xyz.emuci.markdown.renderer.LocalMarkdownTheme
import xyz.emuci.markdown.renderer.internal.core.identity.RenderIdentity
import xyz.emuci.markdown.renderer.internal.core.model.VerticalTextBlockWidgetModel
import xyz.emuci.markdown.renderer.internal.util.parseDimensionDp
import xyz.emuci.markdown.renderer.internal.util.parseFontSizeSp
import xyz.emuci.vtext.VTextConfig
import xyz.emuci.vtext.VTextView

/**
 * 竖排文字块渲染器。
 *
 * 将 ` ```vlr ` 代码块内容交给 vtext-render 的 [VTextView] 以竖排方式渲染。
 * 支持 height（高度约束）、fontSize（字号）与 wrap（自动折列换行）配置。
 */
@Composable
internal fun VerticalTextBlockRenderer(
    node: VerticalTextBlock,
    modifier: Modifier = Modifier,
) {
    val h = parseDimensionDp(node.height)
    val fs = parseFontSizeSp(node.fontSize)
    val isWrap = node.wrap ?: false

    RenderVerticalTextBlockWidgetModel(
        model = VerticalTextBlockWidgetModel(
            identity = RenderIdentity(
                stableId = node.stableKey.toLong(),
                contentRevision = node.contentHash,
                layoutRevision = node.contentHash,
                paintRevision = 0L,
            ),
            text = node.literal,
            height = h,
            fontSize = fs,
            wrap = isWrap,
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
    val effectiveColor = if (theme.verticalTextStyle.color.isSpecified) {
        theme.verticalTextStyle.color
    } else if (theme.bodyStyle.color.isSpecified) {
        theme.bodyStyle.color
    } else {
        Color.Unspecified
    }
    val baseVerticalStyle = if (effectiveColor.isSpecified) {
        theme.verticalTextStyle.copy(color = effectiveColor)
    } else {
        theme.verticalTextStyle
    }
    val finalStyle = if (model.fontSize != null) {
        baseVerticalStyle.copy(fontSize = model.fontSize)
    } else {
        baseVerticalStyle
    }

    val outerModifier = modifier
        .fillMaxWidth()
        .then(
            if (model.height != null) Modifier.height(model.height)
            else Modifier.wrapContentHeight()
        )
        .clip(RoundedCornerShape(theme.codeBlockCornerRadius))
        .background(theme.codeBlockBackground)
        .padding(theme.codeBlockPadding)

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    var scrollModifier = Modifier.horizontalScroll(hScroll)
    if (model.height != null && !model.wrap) {
        scrollModifier = scrollModifier.verticalScroll(vScroll)
    }

    Box(modifier = outerModifier) {
        Box(modifier = scrollModifier.wrapContentSize()) {
            VTextView(
                text = model.text.trimEnd('\n'),
                style = finalStyle,
                config = VTextConfig(softWrap = model.wrap),
            )
        }
    }
}
