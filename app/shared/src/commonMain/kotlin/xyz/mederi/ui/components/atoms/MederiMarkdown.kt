package xyz.mederi.ui.components.atoms

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.emuci.inkcompose.MarkdownView
import xyz.emuci.inkcompose.RenderStyle
import xyz.emuci.markdown.renderer.SelectionMenuAction
import xyz.mederi.theme.MederiMarkdownConfig
import xyz.mederi.theme.rememberMederiMarkdownTheme

/**
 * 统一 MarkdownView 包裹样板：内部创建 Mederi 排版主题（[rememberMederiMarkdownTheme]），
 * 调用方只需传内容与流式标志，消灭 6 处调用点各自 remember 主题 + 参数漂移的样板。
 *
 * 颜色（主题1）从 LocalMederiColors 内部派生、不对外暴露；style（主题2 RenderStyle）按场景传，会话默认 Chat；
 * onLinkClick/selectionMenuActions 原样透传底层 MarkdownView。
 *
 * @param compact 紧凑变体（思维链/折叠卡片等空间受限区域）
 * @param style 排版风格（主题2），会话场景默认 [RenderStyle.Chat]
 * @param onLinkClick 链接点击回调，透传底层 MarkdownView
 * @param selectionMenuActions 文本选择菜单动作，透传底层 MarkdownView
 * @param enableScrollOverride 覆盖内部滚动检测（LazyColumn item 内应传 false；null = 自动检测）
 */
@Composable
fun MederiMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    sessionKey: String? = null,
    compact: Boolean = false,
    style: RenderStyle = RenderStyle.Chat,
    onLinkClick: ((String) -> Unit)? = null,
    selectionMenuActions: List<SelectionMenuAction> = emptyList(),
    enableScrollOverride: Boolean? = null,
) {
    MarkdownView(
        content = content,
        modifier = modifier,
        isStreaming = isStreaming,
        markdownTheme = rememberMederiMarkdownTheme(style = style, compact = compact),
        onLinkClick = onLinkClick,
        selectionMenuActions = selectionMenuActions,
        enableScrollOverride = enableScrollOverride,
        sessionKey = sessionKey,
        markdownConfig = MederiMarkdownConfig,
    )
}
