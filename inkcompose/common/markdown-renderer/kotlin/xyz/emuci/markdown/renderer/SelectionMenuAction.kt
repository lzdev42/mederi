package xyz.emuci.markdown.renderer

import androidx.compose.runtime.Composable

/**
 * 选区右键菜单的自定义动作。
 *
 * 菜单为纯渲染：库不内置"复制"，app 层通过 [MarkdownView.selectionMenuActions]
 * 注入菜单项（如"复制"、"添加到对话框"），label / onClick / leadingIcon 由调用方决定。
 *
 * @param label 菜单显示文本
 * @param onClick 点击时回调，参数为当前选中文本
 * @param leadingIcon 菜单项前导图标（可选，Composable）
 */
data class SelectionMenuAction(
    val label: String,
    val onClick: (selectedText: String) -> Unit,
    val leadingIcon: (@Composable () -> Unit)? = null,
)
