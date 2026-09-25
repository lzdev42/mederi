package xyz.emuci.markdown.renderer.internal.layout.inline

/**
 * 内联布局的稳定环境标识（内容无关实例身份）。
 *
 * 只保留**跨 item 回收仍然稳定**、且真正影响布局结果的输入：
 * - theme / codeTheme / config / directivePlugins：内容哈希（均为 data class 或稳定实例）
 * - density / fontScale：像素密度与字体缩放（同一窗口内恒定）
 *
 * 刻意**不包含** textMeasurer / latexMeasurer / 回调 lambda 的实例哈希：
 * 它们每个 LazyColumn item 重建都会产生新实例（rememberTextMeasurer / rememberLatexMeasurer），
 * 若进入 epoch 会导致布局缓存跨回收永远 miss → item 每次滚回都要全量重新测量文本。
 * 同一应用内字体族与回调行为不变，测量结果与具体测量器实例无关。
 */
internal data class InlineLayoutEpoch(
    val themeHash: Int,
    val codeThemeHash: Int,
    val directivePluginsHash: Int,
    val configHash: Int,
    val densityBits: Int,
    val fontScaleBits: Int,
)
