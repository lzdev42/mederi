package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/** 保存平台 TextToolbar 的完整动作契约，由 [PopupTextToolbarHost] 渲染。 */
@Stable
internal class PopupTextToolbar : TextToolbar {

    data class MenuState(
        val rect: Rect,
        val onCopyRequested: (() -> Unit)? = null,
        val onPasteRequested: (() -> Unit)? = null,
        val onCutRequested: (() -> Unit)? = null,
        val onSelectAllRequested: (() -> Unit)? = null,
    )

    var menuState: MenuState? by mutableStateOf(null)
        private set

    override val status: TextToolbarStatus
        get() = if (menuState != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        menuState = MenuState(
            rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested,
        )
    }

    override fun hide() {
        menuState = null
    }
}
