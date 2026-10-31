package xyz.emuci.syntax.renderer

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult

/** Optional host bridge; syntax rendering remains independent of the host selection engine. */
internal class CodeTextSelectionObserver(
    val onPositioned: (Int, LayoutCoordinates) -> Unit,
    val onTextLayout: (Int, TextLayoutResult) -> Unit,
    val onDispose: (Int) -> Unit,
)

internal val LocalCodeTextSelectionObserver = staticCompositionLocalOf<CodeTextSelectionObserver?> { null }
