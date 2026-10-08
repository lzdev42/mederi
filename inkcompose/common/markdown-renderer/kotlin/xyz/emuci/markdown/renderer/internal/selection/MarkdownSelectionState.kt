package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 选区当前由哪一端在跟随手指延展。 */
internal enum class SelectionActiveHandle { None, Start, End }

@Stable
internal class MarkdownSelectionState {
    var range: SelectionRange? by mutableStateOf(null)
    var activeHandle: SelectionActiveHandle by mutableStateOf(SelectionActiveHandle.None)
    var isHandleDrag: Boolean by mutableStateOf(false)
    var toolbarRequestKey: Int by mutableStateOf(0)
    /** 输入模式标志：true=鼠标，false=触屏。默认触屏。 */
    var isMouseInput: Boolean by mutableStateOf(false)

    val hasSelection: Boolean get() = range != null

    fun clear() {
        range = null
        activeHandle = SelectionActiveHandle.None
        isHandleDrag = false
        toolbarRequestKey = 0
        isMouseInput = false
    }
}
