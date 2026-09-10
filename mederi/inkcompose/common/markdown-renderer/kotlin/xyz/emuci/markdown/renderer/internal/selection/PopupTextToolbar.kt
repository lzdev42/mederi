package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * 自实现 [TextToolbar]：用 Compose `DropdownMenu` 替代桌面端空壳 `DefaultTextToolbar`。
 *
 * 统一所有平台：`SelectionToolbarHost` 调 `showMenu()` → 此类存状态 →
 * [PopupTextToolbarHost] 监听状态弹菜单。不再需要平台判断分支。
 */
@Stable
internal class PopupTextToolbar : TextToolbar {

    data class MenuState(
        val rect: Rect,
        val onCopyRequested: (() -> Unit)?,
        val onPasteRequested: (() -> Unit)?,
        val onCutRequested: (() -> Unit)?,
        val onSelectAllRequested: (() -> Unit)?,
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
        menuState = MenuState(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        menuState = null
    }
}
