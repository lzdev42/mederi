package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.isSecondaryPressed

internal actual fun PointerEvent.isSecondaryClick(): Boolean = buttons.isSecondaryPressed
