package xyz.emuci.syntax.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier

@Composable
fun InlineCode(
    text: String,
    modifier: Modifier = Modifier,
    style: InlineCodeStyle = InlineCodeDefaults.style(),
) {
    val observer = LocalCodeTextSelectionObserver.current
    DisposableEffect(observer) { onDispose { observer?.onDispose?.invoke(0) } }
    BasicText(
        text = text,
        style = style.textStyle,
        onTextLayout = { observer?.onTextLayout?.invoke(0, it) },
        modifier = modifier
            .background(
                color = style.containerColor,
                shape = style.shape,
            )
            .then(
                if (style.borderColor != null && style.borderWidth.value > 0f) {
                    Modifier.border(
                        width = style.borderWidth,
                        color = style.borderColor,
                        shape = style.shape,
                    )
                } else {
                    Modifier
                }
            )
            .padding(style.contentPadding)
            .onGloballyPositioned { observer?.onPositioned?.invoke(0, it) },
    )
}
