package xyz.emuci.markdown.renderer.internal

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.luminance
import xyz.emuci.markdown.parser.ast.Document
import xyz.emuci.markdown.renderer.DiagramHostRegistry
import xyz.emuci.latex.renderer.measure.LatexMeasurerState
import xyz.emuci.markdown.renderer.MarkdownTheme
import xyz.emuci.markdown.renderer.internal.compose.DefaultMarkdownComposePainter
import xyz.emuci.markdown.renderer.internal.compose.MarkdownComposePainter
import xyz.emuci.markdown.renderer.internal.core.compile.DefaultRenderModelCompiler
import xyz.emuci.markdown.renderer.internal.core.compile.RenderCompileEnvironment
import xyz.emuci.markdown.renderer.internal.core.compile.RenderConfigSnapshot
import xyz.emuci.markdown.renderer.internal.core.compile.RenderThemeSnapshot
import xyz.emuci.markdown.renderer.internal.core.model.InternalRenderDocumentModel
import xyz.emuci.markdown.renderer.internal.layout.engine.DefaultMarkdownLayoutEngine
import xyz.emuci.markdown.renderer.internal.layout.engine.LayoutEnvironment
import xyz.emuci.markdown.renderer.internal.layout.engine.MarkdownLayoutEngine
import xyz.emuci.markdown.renderer.internal.layout.engine.MarkdownLayoutSession
import xyz.emuci.markdown.renderer.internal.layout.engine.MarkdownBlockLayoutCache
import xyz.emuci.markdown.renderer.internal.layout.inline.InlineLayoutRuntime
import xyz.emuci.markdown.renderer.internal.layout.inline.InlineLayoutMetricsSnapshot
import xyz.emuci.markdown.renderer.internal.layout.inline.inlineLayoutEpoch
import xyz.emuci.markdown.renderer.internal.layout.model.InternalLayoutDocumentModel

/**
 * 全局共享的块级布局缓存（LRU，线程主线程访问）。
 *
 * 各 item 的 [MarkdownEngineHost] 共享同一实例，使 LazyColumn 回收重建后
 * 滚回来的 item 直接复用上次的块布局（几何 + 文本测量结果），
 * 不再每个 item 首帧全量重新测量文本 → 消除连续滚动时 20~30 FPS 的主因。
 *
 * key 由块 identity（同 Document 稳定）+ viewportWidth + 稳定 epoch 构成，
 * epoch 已排除 per-item 的测量器/回调实例（见 [InlineLayoutEpoch]）。
 * 块模型为纯数据（几何 + AnnotatedString），跨回收复用安全。
 */
internal val sharedMarkdownBlockLayoutCache = MarkdownBlockLayoutCache()

internal class MarkdownEngineHost(
    private val compiler: DefaultRenderModelCompiler = DefaultRenderModelCompiler,
    private val layoutEngine: MarkdownLayoutEngine = DefaultMarkdownLayoutEngine,
    val composePainter: MarkdownComposePainter = DefaultMarkdownComposePainter,
) {
    private val inlineLayoutRuntime = InlineLayoutRuntime()
    private val blockLayoutCache = sharedMarkdownBlockLayoutCache

    internal fun inlineLayoutMetrics(): InlineLayoutMetricsSnapshot =
        inlineLayoutRuntime.metricsSnapshot()

    internal fun resetInlineLayoutMetrics() {
        inlineLayoutRuntime.resetMetrics()
    }

    fun compile(
        document: Document,
        facadeState: RendererFacadeState,
    ): InternalRenderDocumentModel {
        return compiler.compile(
            document = document,
            environment = facadeState.toCompileEnvironment(),
        )
    }

    fun layout(
        renderDocument: InternalRenderDocumentModel,
        facadeState: RendererFacadeState,
        viewportWidth: Float,
        blockSpacing: Float = 0f,
        onLinkClick: ((String) -> Unit)? = null,
        onFootnoteClick: ((String) -> Unit)? = null,
        density: Density,
        textMeasurer: TextMeasurer,
        latexMeasurer: LatexMeasurerState,
        diagramHostRegistry: DiagramHostRegistry,
    ): InternalLayoutDocumentModel {
        return layoutEngine.layout(
            document = renderDocument,
            environment = createLayoutEnvironment(
                facadeState = facadeState,
                viewportWidth = viewportWidth,
                blockSpacing = blockSpacing,
                onLinkClick = onLinkClick,
                onFootnoteClick = onFootnoteClick,
                density = density,
                textMeasurer = textMeasurer,
                latexMeasurer = latexMeasurer,
                diagramHostRegistry = diagramHostRegistry,
            ),
        )
    }

    fun layoutSession(
        renderDocument: InternalRenderDocumentModel,
        facadeState: RendererFacadeState,
        viewportWidth: Float,
        blockSpacing: Float = 0f,
        onLinkClick: ((String) -> Unit)? = null,
        onFootnoteClick: ((String) -> Unit)? = null,
        density: Density,
        textMeasurer: TextMeasurer,
        latexMeasurer: LatexMeasurerState,
        diagramHostRegistry: DiagramHostRegistry,
    ): MarkdownLayoutSession = MarkdownLayoutSession(
        document = renderDocument,
        environment = createLayoutEnvironment(
            facadeState = facadeState,
            viewportWidth = viewportWidth,
            blockSpacing = blockSpacing,
            onLinkClick = onLinkClick,
            onFootnoteClick = onFootnoteClick,
            density = density,
            textMeasurer = textMeasurer,
            latexMeasurer = latexMeasurer,
            diagramHostRegistry = diagramHostRegistry,
        ),
        engine = layoutEngine,
        sharedBlockCache = blockLayoutCache,
    )

    private fun createLayoutEnvironment(
        facadeState: RendererFacadeState,
        viewportWidth: Float,
        blockSpacing: Float,
        onLinkClick: ((String) -> Unit)?,
        onFootnoteClick: ((String) -> Unit)?,
        density: Density,
        textMeasurer: TextMeasurer,
        latexMeasurer: LatexMeasurerState,
        diagramHostRegistry: DiagramHostRegistry,
    ): LayoutEnvironment = LayoutEnvironment(
        viewportWidth = viewportWidth,
        blockSpacing = blockSpacing,
        markdownTheme = facadeState.theme,
        codeTheme = facadeState.codeTheme,
        onLinkClick = onLinkClick,
        onFootnoteClick = onFootnoteClick,
        density = density,
        textMeasurer = textMeasurer,
        latexMeasurer = latexMeasurer,
        compileEnvironment = facadeState.toCompileEnvironment(),
        diagramHostRegistry = diagramHostRegistry,
        inlineLayoutRuntime = inlineLayoutRuntime,
        inlineLayoutEpoch = inlineLayoutEpoch(
            theme = facadeState.theme,
            codeTheme = facadeState.codeTheme,
            directiveRegistry = facadeState.directiveRegistry,
            config = facadeState.config,
            density = density,
        ),
    )
}

internal fun RendererFacadeState.toCompileEnvironment(): RenderCompileEnvironment {
    return RenderCompileEnvironment(
        theme = RenderThemeSnapshot(
            darkMode = theme.isDarkLike(),
        ),
        config = RenderConfigSnapshot(
            enableHeadingNumbering = config.enableHeadingNumbering,
            streaming = isStreaming,
        ),
        directiveRegistry = directiveRegistry,
    )
}

private fun MarkdownTheme.isDarkLike(): Boolean {
    return bodyStyle.color.luminance() < 0.5f
}
