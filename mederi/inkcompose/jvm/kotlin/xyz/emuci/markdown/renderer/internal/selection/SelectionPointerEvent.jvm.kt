package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun PointerEvent.isSecondaryClick(): Boolean {
    return button == PointerButton.Secondary
}
