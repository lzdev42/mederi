package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.ui.input.pointer.PointerEvent

internal actual fun PointerEvent.isSecondaryClick(): Boolean = false
