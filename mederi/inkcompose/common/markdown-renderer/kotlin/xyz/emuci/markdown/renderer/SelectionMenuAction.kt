package xyz.emuci.markdown.renderer

/**
 * 选区右键菜单的自定义动作。
 *
 * InkCompose 内置"复制"，app 层可通过 [MarkdownView.selectionMenuActions]
 * 注入额外菜单项（如"添加到对话框"），label 与 onClick 由调用方决定。
 *
 * @param label 菜单显示文本
 * @param onClick 点击时回调，参数为当前选中文本
 */
data class SelectionMenuAction(
    val label: String,
    val onClick: (selectedText: String) -> Unit,
)
