package xyz.mederi.ui.components.atoms

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.emuci.inkcompose.MarkdownView
import xyz.mederi.theme.rememberMederiMarkdownTheme

/**
 * 统一 MarkdownView 包裹样板：内部创建 Mederi 排版主题（[rememberMederiMarkdownTheme]），
 * 调用方只需传内容与流式标志，消灭 6 处调用点各自 remember 主题 + 参数漂移的样板。
 *
 * @param compact 紧凑变体（思维链/折叠卡片等空间受限区域）
 * @param enableScrollOverride 覆盖内部滚动检测（LazyColumn item 内应传 false；null = 自动检测）
 */
@Composable
fun MederiMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    sessionKey: String? = null,
    compact: Boolean = false,
    enableScrollOverride: Boolean? = null,
) {
    MarkdownView(
        content = content,
        modifier = modifier,
        isStreaming = isStreaming,
        markdownTheme = rememberMederiMarkdownTheme(compact = compact),
        enableScrollOverride = enableScrollOverride,
        sessionKey = sessionKey,
    )
}