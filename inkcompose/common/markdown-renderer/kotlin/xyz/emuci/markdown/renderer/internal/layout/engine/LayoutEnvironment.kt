package xyz.emuci.markdown.renderer.internal.layout.engine

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import xyz.emuci.syntax.theme.CodeTheme
import xyz.emuci.latex.renderer.measure.LatexMeasurerState
import xyz.emuci.markdown.renderer.DiagramHostRegistry
import xyz.emuci.markdown.renderer.MarkdownTheme
import xyz.emuci.markdown.renderer.internal.core.compile.RenderCompileEnvironment
import xyz.emuci.markdown.renderer.internal.layout.inline.InlineLayoutEpoch
import xyz.emuci.markdown.renderer.internal.layout.inline.InlineLayoutRuntime

internal data class LayoutEnvironment(
    val viewportWidth: Float,
    val blockSpacing: Float = 0f,
    val markdownTheme: MarkdownTheme,
    val codeTheme: CodeTheme? = null,
    val onLinkClick: ((String) -> Unit)? = null,
    val onFootnoteClick: ((String) -> Unit)? = null,
    val density: Density,
    val textMeasurer: TextMeasurer,
    val latexMeasurer: LatexMeasurerState,
    val compileEnvironment: RenderCompileEnvironment,
    val diagramHostRegistry: DiagramHostRegistry,
    val inlineLayoutRuntime: InlineLayoutRuntime,
    val inlineLayoutEpoch: InlineLayoutEpoch,
)
