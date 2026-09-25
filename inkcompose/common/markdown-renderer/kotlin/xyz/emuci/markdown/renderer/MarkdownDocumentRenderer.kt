package xyz.emuci.markdown.renderer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.rememberTextMeasurer
import xyz.emuci.syntax.theme.CodeTheme
import xyz.emuci.latex.renderer.measure.rememberLatexMeasurer
import xyz.emuci.markdown.parser.ast.Document
import xyz.emuci.markdown.renderer.internal.MarkdownEngineHost
import xyz.emuci.markdown.renderer.internal.RendererFacadeState
import xyz.emuci.markdown.renderer.internal.compose.ComposeRenderEnvironment
import xyz.emuci.markdown.renderer.internal.selection.LocalMarkdownSelectionController
import xyz.emuci.markdown.renderer.internal.selection.PopupTextToolbar
import xyz.emuci.markdown.renderer.internal.selection.PopupTextToolbarHost
import xyz.emuci.markdown.renderer.internal.selection.SelectionHandlesHost
import xyz.emuci.markdown.renderer.internal.selection.SelectionToolbarHost
import xyz.emuci.markdown.renderer.internal.selection.markdownSelectionGestures
import xyz.emuci.markdown.renderer.internal.selection.rememberMarkdownSelectionController
import xyz.emuci.markdown.runtime.MarkdownDirectiveRegistry

/**
 * 按 Document 隔离的 Markdown 容器宽度缓存（px），keyed by Document 引用。
 *
 * 用于 [MarkdownDocumentRenderer] 在 LazyColumn 回收重建 item 时提供正确的初始宽度：
 * - 避免 viewportWidthPx=0 → 内容不渲染 → 高度=0 → LazyColumn 判定不可见 → dispose → thrashing
 * - 按 Document 隔离避免不同宽度 item（用户消息 widthIn(680) vs 助手消息 fillMaxWidth）
 *   共享同一个全局宽度导致首帧宽度错误 → 布局重排 → 高度跳变 → 回弹
 *
 * Document 是普通类（引用相等），且 parsedDocumentCache 保证同一 markdown 返回同一实例，
 * 因此以 Document 为 key 是稳定且唯一的。
 *
 * 有界 LRU：防止长会话中无限持有 Document（连带 AST）引用。
 */
private val viewportWidthCache = object {
    private val map = LinkedHashMap<Document, Float>(64, 0.75f)
    private val maxEntries = 256

    operator fun get(key: Document): Float? = map[key]

    operator fun set(key: Document, value: Float) {
        map[key] = value
        if (map.size > maxEntries) {
            val eldest = map.keys.firstOrNull() ?: return
            map.remove(eldest)
        }
    }
}

@Composable
internal fun MarkdownDocumentRenderer(
    document: Document,
    modifier: Modifier = Modifier,
    theme: MarkdownTheme = MarkdownTheme.auto(),
    codeTheme: CodeTheme? = null,
    config: MarkdownConfig = MarkdownConfig.Default,
    scrollState: ScrollState = rememberScrollState(),
    isStreaming: Boolean = false,
    enableScroll: Boolean = true,
    enableSelection: Boolean = true,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    imageContent: MarkdownImageRenderer? = null,
    onLinkClick: ((String) -> Unit)? = null,
    directiveRegistry: MarkdownDirectiveRegistry = MarkdownDirectiveRegistry.Empty,
    selectionMenuActions: List<SelectionMenuAction> = emptyList(),
) {
    val renderMode = remember(enableScroll) {
        resolveMarkdownRenderMode(enableScroll = enableScroll)
    }
    val lazyListState = rememberLazyListState()
    val renderDocument = rememberRenderDocument(
        document = document,
        isStreaming = isStreaming,
    )
    ProvideMarkdownTheme(theme) {
        val engineHost = remember { MarkdownEngineHost() }
        val facadeState = remember(
            theme,
            config,
            codeTheme,
            imageContent,
            onLinkClick,
            directiveRegistry,
            isStreaming,
            enableSelection,
        ) {
            RendererFacadeState(
                theme = theme,
                config = config,
                codeTheme = codeTheme,
                imageRenderer = imageContent,
                onLinkClick = onLinkClick,
                directiveRegistry = directiveRegistry,
                isStreaming = isStreaming,
                enableSelection = enableSelection,
            )
        }
        val internalRenderDocument = remember(
            engineHost,
            renderDocument,
            theme,
            config,
            directiveRegistry,
            isStreaming,
        ) {
            engineHost.compile(
                document = renderDocument,
                facadeState = facadeState,
            )
        }
        val density = LocalDensity.current
        val latexMeasurer = rememberLatexMeasurer()
        val diagramHostRegistry = remember { DiagramHostRegistry() }
        val blockSpacingPx = with(density) { theme.blockSpacing.toPx() }

        // 不使用 BoxWithConstraints（SubcomposeLayout），避免 LazyColumn 反向滚动时
        // 同步 subcompose 整个 Markdown 内容导致测量卡顿。
        // 改用 onSizeChanged + mutableStateOf 在布局后获取宽度。
        // 关键：按 Document 隔离的宽度缓存做初始值，避免首帧 viewportWidthPx=0 → 高度=0 →
        // LazyColumn 判定 item 不可见 → dispose → thrashing；也避免不同宽度 item 共享错误宽度 → 回弹。
        var viewportWidthPx by remember {
            mutableStateOf(viewportWidthCache[document] ?: 0f)
        }
        Box(modifier = modifier.onSizeChanged { size ->
            val w = size.width.toFloat()
            if (w != viewportWidthPx && w > 0f) {
                if (!isStreaming) {
                    viewportWidthCache[document] = w
                }
                viewportWidthPx = w
            }
        }) {
            if (viewportWidthPx > 0f) {
                val textMeasurer = rememberTextMeasurer()
                val selectionController = if (enableSelection) {
                    val selectionScope = rememberCoroutineScope()
                    rememberMarkdownSelectionController(selectionScope, textMeasurer)
                } else {
                    null
                }
                selectionController?.bindClipboard(LocalClipboard.current)
                val popupToolbar = remember { PopupTextToolbar() }
                val navigationController = rememberMarkdownNavigationController(
                    renderMode = renderMode,
                    enableScroll = enableScroll,
                    scrollState = scrollState,
                    lazyListState = lazyListState,
                    onLinkClick = onLinkClick,
                )
                val layoutSource = remember(
                    engineHost,
                    internalRenderDocument,
                    facadeState,
                    renderMode,
                    viewportWidthPx,
                    blockSpacingPx,
                    density,
                    textMeasurer,
                    latexMeasurer,
                    diagramHostRegistry,
                ) {
                    // 两种模式统一走 layoutSession（按块惰性排版 + 全局共享块缓存）。
                    // StaticColumn 曾用 engineHost.layout 整篇急切排版并绕过
                    // sharedMarkdownBlockLayoutCache，导致每条聊天消息滚出再滚回时
                    // 全量重测文本；统一 session 后，块布局结果跨 item 重建复用。
                    engineHost.layoutSession(
                        renderDocument = internalRenderDocument,
                        facadeState = facadeState,
                        viewportWidth = viewportWidthPx,
                        blockSpacing = blockSpacingPx,
                        onLinkClick = navigationController.linkClickDelegate,
                        onFootnoteClick = navigationController.onFootnoteClick,
                        density = density,
                        textMeasurer = textMeasurer,
                        latexMeasurer = latexMeasurer,
                        diagramHostRegistry = diagramHostRegistry,
                    )
                }
                navigationController.footnoteDefinitionItemIndexes =
                    layoutSource.metadata.footnoteDefinitionItemIndexes
                if (selectionController != null) {
                    LaunchedEffect(internalRenderDocument) {
                        selectionController.updateDocument(internalRenderDocument.blocks)
                    }
                }
                Box(
                    modifier = if (selectionController != null) {
                        Modifier
                            .onGloballyPositioned { selectionController.registry.setRoot(it) }
                            .markdownSelectionGestures(selectionController)
                    } else {
                        Modifier
                    },
                ) {
                    CompositionLocalProvider(
                        LocalMarkdownSelectionController provides selectionController,
                        LocalTextToolbar provides popupToolbar,
                    ) {
                        ProvideRendererContext(
                            document = renderDocument,
                            onLinkClick = onLinkClick,
                            onFootnoteClick = navigationController.onFootnoteClick,
                            onFootnoteBackClick = navigationController.onFootnoteBackClick,
                            footnoteNavigationState = navigationController.footnoteNavigationState,
                            imageContent = imageContent,
                            config = config,
                            codeTheme = codeTheme,
                            isStreaming = isStreaming,
                            directiveRegistry = directiveRegistry,
                            diagramHostRegistry = diagramHostRegistry,
                        ) {
                            engineHost.composePainter.Paint(
                                document = layoutSource,
                                environment = ComposeRenderEnvironment(
                                    modifier = Modifier.fillMaxWidth(),
                                    renderMode = renderMode,
                                    enableScroll = enableScroll,
                                    scrollState = scrollState,
                                    lazyListState = lazyListState,
                                    header = header,
                                    footer = footer,
                                ),
                            )
                        }
                        if (selectionController != null) {
                            SelectionHandlesHost(selectionController)
                            SelectionToolbarHost(selectionController)
                            PopupTextToolbarHost(popupToolbar, selectionController, selectionMenuActions)
                        }
                    }
                }
            }
        }
    }
}
