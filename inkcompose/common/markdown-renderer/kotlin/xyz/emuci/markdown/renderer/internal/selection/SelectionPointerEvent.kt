package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.ui.input.pointer.PointerEvent

/** 平台相关：检测 PointerEvent 是否为鼠标右键（secondary button）按下。 */
internal expect fun PointerEvent.isSecondaryClick(): Boolean
