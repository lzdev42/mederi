package xyz.emuci.inkcompose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import xyz.emuci.syntax.renderer.CodeBlock
import xyz.emuci.syntax.theme.CodeTheme
import xyz.emuci.syntax.theme.LocalCodeTheme
import xyz.emuci.diagram.DiagramBlockView
import xyz.emuci.diagram.theme.DiagramTheme
import xyz.emuci.latex.renderer.Latex
import xyz.emuci.latex.renderer.model.LatexConfig
import xyz.emuci.latex.renderer.model.LatexTheme
import xyz.emuci.markdown.renderer.Markdown
import xyz.emuci.markdown.renderer.MarkdownConfig
import xyz.emuci.markdown.renderer.MarkdownTheme
import xyz.emuci.markdown.renderer.SelectionMenuAction
import xyz.emuci.vtext.VTextView

/**
 * 统一文本渲染入口。
 *
 * **通用模式(默认)**:`MarkdownView("任意字符串")`
 * - 走 Markdown 解析,文本里有什么就渲染什么
 * - 纯文本 → Text;代码块 → 高亮;`$...$` → LaTeX;` ```mermaid ` → 图表
 * - 就像网页一样:内容里有什么就显示什么
 *
 * **针对模式**:`MarkdownView(content, type = RenderType.MERMAID)`
 * - 跳过 Markdown 解析,强制按指定类型渲染
 * - 用于明确知道内容类型的场景(独立图表查看器、公式预览等)
 *
 * **流式渲染**:`MarkdownView(streamingText, isStreaming = true)`
 * - token-by-token 增量解析,16ms 节流,零闪烁
 *
 * 原则:只保证正确解析"正确"的字符串。输入本身有语法错误,按库默认行为处理,不做纠错。
 *
 * @param content 文本内容
 * @param modifier Compose modifier
 * @param type 渲染类型,默认 [RenderType.AUTO]
 * @param isStreaming 是否处于流式接收状态(LLM token-by-token 输出时传 true)
 * @param language 仅 [RenderType.CODE] 时生效,指定代码语言(如 "kotlin"、"python")
 * @param markdownTheme Markdown 主题(传递给底层 Markdown 渲染器)
 * @param codeTheme 代码高亮主题(传递给底层 CodeBlock / Markdown 代码块)
 * @param markdownConfig Markdown 行为配置
 * @param latexConfig LaTeX 行为配置(仅 [RenderType.LATEX] 单独渲染时生效;在 Markdown 内的 LaTeX 由 markdownConfig 控制)
 * @param diagramTheme 图表主题（仅 [RenderType.MERMAID] 单独渲染时生效）
 * @param onLinkClick 链接点击回调(仅 Markdown 模式生效)
 * @param enableSelection 是否启用文本选择(仅 Markdown/Text 模式生效)
 * @param enableScrollOverride 覆盖内部滚动检测。传 null（默认）时通过 BoxWithConstraints 自动检测；
 *   传 false 可跳过 BoxWithConstraints（SubcomposeLayout），消除 LazyColumn 反向滚动时的测量卡顿。
 *   在 LazyColumn item 中应始终传 false（高度无限 → StaticColumn 模式）。
 */
@Composable
fun MarkdownView(
    content: String,
    modifier: Modifier = Modifier,
    type: RenderType = RenderType.AUTO,
    isStreaming: Boolean = false,
    language: String? = null,
    style: RenderStyle = RenderStyle.Chat,
    colors: MarkdownColors? = null,
    markdownTheme: MarkdownTheme? = null,
    codeTheme: CodeTheme? = null,
    markdownConfig: MarkdownConfig = LocalMarkdownConfig.current,
    latexConfig: LatexConfig = LatexConfig(),
    diagramTheme: DiagramTheme = DiagramTheme.Default,
    onLinkClick: ((String) -> Unit)? = null,
    enableSelection: Boolean = true,
    selectionMenuActions: List<SelectionMenuAction> = emptyList(),
    enableScrollOverride: Boolean? = null,
    sessionKey: String? = null,
) {
    val effectiveType = if (type == RenderType.AUTO) RenderType.MARKDOWN else type
    val effectiveSessionKey = sessionKey ?: LocalSessionKey.current

    val colorScheme = MaterialTheme.colorScheme
    val effectiveColors = colors ?: remember(colorScheme) {
        MarkdownColors.fromMaterial3(colorScheme)
    }

    val contentComposable: @Composable () -> Unit = when (effectiveType) {
        RenderType.TEXT -> {
            {
                val text: @Composable () -> Unit = { Text(text = content) }
                if (enableSelection) SelectionContainer(modifier = modifier) { text() }
                else Box(modifier = modifier) { text() }
            }
        }

        RenderType.MARKDOWN -> {
            {
                val effectiveTheme = markdownTheme ?: remember(style, effectiveColors) {
                    MarkdownTheme.from(style = style, colors = effectiveColors)
                }
                if (enableScrollOverride != null) {
                    // 调用方已知 enableScroll，跳过 BoxWithConstraints（SubcomposeLayout），
                    // 消除 LazyColumn 反向滚动时同步 subcompose 的测量卡顿。
                    Markdown(
                        markdown = content,
                        modifier = modifier.fillMaxWidth(),
                        theme = effectiveTheme,
                        codeTheme = codeTheme,
                        config = markdownConfig,
                        isStreaming = isStreaming,
                        enableScroll = enableScrollOverride,
                        enableSelection = enableSelection,
                        onLinkClick = onLinkClick,
                        selectionMenuActions = selectionMenuActions,
                    )
                } else {
                    // 自动检测：高度有限 → enableScroll=true（内部 LazyColumn）；高度无限 → enableScroll=false（StaticColumn）
                    BoxWithConstraints(modifier = modifier) {
                        val enableScroll = constraints.hasBoundedHeight
                        Markdown(
                            markdown = content,
                            modifier = Modifier.fillMaxWidth(),
                            theme = effectiveTheme,
                            codeTheme = codeTheme,
                            config = markdownConfig,
                            isStreaming = isStreaming,
                            enableScroll = enableScroll,
                            enableSelection = enableSelection,
                            onLinkClick = onLinkClick,
                            selectionMenuActions = selectionMenuActions,
                        )
                    }
                }
            }
        }

        RenderType.LATEX -> {
            {
                Latex(
                    latex = content,
                    modifier = modifier,
                    config = if (latexConfig == LatexConfig()) {
                        latexConfig.copy(theme = LatexTheme.material3())
                    } else {
                        latexConfig
                    },
                )
            }
        }

        RenderType.MERMAID -> {
            {
                DiagramBlockView(
                    source = content,
                    theme = diagramTheme,
                    languageHint = "mermaid",
                    sessionKey = effectiveSessionKey,
                    modifier = modifier,
                )
            }
        }

        RenderType.VLR -> {
            {
                val effectiveTheme = markdownTheme ?: remember(style, effectiveColors) {
                    MarkdownTheme.from(style = style, colors = effectiveColors)
                }
                VTextView(
                    text = content,
                    modifier = modifier,
                    style = effectiveTheme.verticalTextStyle,
                )
            }
        }

        RenderType.CODE -> {
            {
                CodeBlock(
                    code = content,
                    language = language ?: "",
                    modifier = modifier,
                    isStreaming = isStreaming,
                    theme = codeTheme ?: LocalCodeTheme.current,
                )
            }
        }

        RenderType.AUTO -> error("unreachable: AUTO should be normalized to MARKDOWN above")
    }

    CompositionLocalProvider(
        LocalSessionKey provides effectiveSessionKey,
        LocalRenderStyle provides style,
        LocalMarkdownColors provides effectiveColors,
        LocalMarkdownConfig provides markdownConfig,
    ) {
        contentComposable()
    }
}

