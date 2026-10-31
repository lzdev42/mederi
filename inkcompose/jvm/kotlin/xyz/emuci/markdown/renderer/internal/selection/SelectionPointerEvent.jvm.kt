package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.isCtrlPressed

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun PointerEvent.isSecondaryClick(): Boolean {
    return button == PointerButton.Secondary ||
        (System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
            button == PointerButton.Primary && keyboardModifiers.isCtrlPressed)
}
