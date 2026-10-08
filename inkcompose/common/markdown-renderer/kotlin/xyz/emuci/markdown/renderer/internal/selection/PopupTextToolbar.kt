package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * 自实现 [TextToolbar]：用 Compose `Popup` 替代桌面端空壳 `DefaultTextToolbar`。
 *
 * 统一所有平台：`SelectionToolbarHost` 调 `showMenu()` → 此类存状态 →
 * [PopupTextToolbarHost] 监听状态弹菜单。不再需要平台判断分支。
 *
 * 菜单为纯渲染：[MenuState] 只保存锚点 rect，TextToolbar 接口的
 * copy/paste/cut/selectAll 回调被忽略（菜单不再渲染它们），
 * 复制等动作由 app 层经 selectionMenuActions 提供。
 */
@Stable
internal class PopupTextToolbar : TextToolbar {

    data class MenuState(
        val rect: Rect,
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
        // 纯渲染协议：只记录锚点 rect，忽略 TextToolbar 回调（菜单不再渲染它们）。
        menuState = MenuState(rect)
    }

    override fun hide() {
        menuState = null
    }
}
