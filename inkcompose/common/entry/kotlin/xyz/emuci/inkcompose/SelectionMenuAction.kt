package xyz.emuci.inkcompose

import androidx.compose.runtime.Composable

/**
 * 强类型选区菜单项。数组即完整菜单，顺序由调用方决定；空数组隐藏菜单。
 * 默认提供 [Copy] 和 [Paste]。只读阅读器的粘贴目标由宿主的 onPasteRequested 接入。
 * 自定义动作直接收到选中文本，不以文案或字符串 ID 判断行为。
 */
sealed interface SelectionMenuAction {
    data object Copy : SelectionMenuAction
    data object Paste : SelectionMenuAction

    data class Custom(
        val label: String,
        val onClick: (selectedText: String) -> Unit,
        val leadingIcon: (@Composable () -> Unit)? = null,
        val enabled: Boolean = true,
    ) : SelectionMenuAction
}
