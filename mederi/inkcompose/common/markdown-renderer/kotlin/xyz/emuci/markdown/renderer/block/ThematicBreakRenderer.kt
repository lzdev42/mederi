package xyz.emuci.markdown.renderer.block

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.emuci.markdown.renderer.LocalMarkdownTheme

/**
 * 水平分割线渲染器 (---, ***, ___)
 */
@Composable
internal fun ThematicBreakRenderer(
    modifier: Modifier = Modifier,
) {
    val theme = LocalMarkdownTheme.current
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = theme.dividerThickness,
        color = theme.dividerColor,
    )
}
