package xyz.emuci.markdown.renderer.internal.layout.inline

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import xyz.emuci.syntax.theme.CodeTheme
import xyz.emuci.latex.renderer.measure.LatexMeasurerState
import xyz.emuci.markdown.renderer.MarkdownConfig
import xyz.emuci.markdown.renderer.MarkdownTheme
import xyz.emuci.markdown.renderer.inline.InlineRenderResult
import xyz.emuci.markdown.renderer.inline.buildInlineRenderResultFromModel
import xyz.emuci.markdown.renderer.internal.core.identity.RenderIdentity
import xyz.emuci.markdown.renderer.internal.core.model.InlineModel
import xyz.emuci.markdown.runtime.MarkdownDirectiveRegistry
import kotlin.math.ceil

internal class InlineLayoutRuntime {
    private val renderResultCache = InlineRenderResultCache()
    private val flowLayoutCache = InlineFlowLayoutCache()
    private val metrics = InlineLayoutMetrics()

    fun metricsSnapshot(): InlineLayoutMetricsSnapshot = metrics.snapshot()

    fun resetMetrics() = metrics.reset()

    fun renderResult(
        model: InlineModel,
        style: TextStyle,
        epoch: InlineLayoutEpoch,
        theme: MarkdownTheme,
        directiveRegistry: MarkdownDirectiveRegistry,
        onLinkClick: ((String) -> Unit)?,
        onFootnoteClick: ((String) -> Unit)?,
        latexMeasurer: LatexMeasurerState,
        density: Density,
        textMeasurer: TextMeasurer,
        codeTheme: CodeTheme?,
    ): InlineRenderResult {
        metrics.renderResultRequests++
        return renderResultCache.getOrPut(
            epoch = epoch,
            stableId = model.identity.stableId,
            contentRevision = model.identity.contentRevision,
            style = style,
        ) {
            metrics.renderResultComputations++
            val result = buildInlineRenderResultFromModel(
                model = model,
                theme = theme,
                hostTextStyle = style,
                directiveRegistry = directiveRegistry,
                onLinkClick = onLinkClick,
                onFootnoteClick = onFootnoteClick,
                latexMeasurer = latexMeasurer,
                density = density,
                textMeasurer = textMeasurer,
                codeTheme = codeTheme,
            )
            metrics.inlineMathBuildRequests += result.inlineMathBuildRequests
            result
        }
    }

    fun flowLayout(
        identity: RenderIdentity,
        inlineResult: InlineRenderResult,
        style: TextStyle,
        epoch: InlineLayoutEpoch,
        density: Density,
        textMeasurer: TextMeasurer,
        widthPx: Float,
        maxLines: Int,
    ): InlineFlowLayout {
        metrics.flowLayoutRequests++
        return flowLayoutCache.getOrPut(
            epoch = epoch,
            layoutRevision = identity.layoutRevision,
            widthPx = widthPx,
            maxLines = maxLines,
            style = style,
            density = density,
            textMeasurer = textMeasurer,
        ) {
            metrics.flowLayoutComputations++
            computeInlineFlowLayout(
                input = inlineResult.flowInput,
                style = style,
                density = density,
                textMeasurer = textMeasurer,
                maxWidthPx = widthPx,
                maxLines = maxLines,
            )
        }
    }

    fun intrinsicHeightPx(
        identity: RenderIdentity,
        inlineResult: InlineRenderResult,
        style: TextStyle,
        epoch: InlineLayoutEpoch,
        density: Density,
        textMeasurer: TextMeasurer,
        maxLines: Int,
        widthPx: Int,
    ): Int {
        val targetWidth = if (widthPx == Constraints.Infinity || widthPx <= 0) {
            computeMaxIntrinsicWidthPx(
                input = inlineResult.flowInput,
                style = style,
                textMeasurer = textMeasurer,
            ).coerceAtLeast(1)
        } else {
            widthPx
        }
        return ceil(
            flowLayout(
                identity = identity,
                inlineResult = inlineResult,
                style = style,
                epoch = epoch,
                density = density,
                textMeasurer = textMeasurer,
                widthPx = targetWidth.toFloat(),
                maxLines = maxLines,
            ).heightPx
        ).toInt()
    }
}

internal fun inlineLayoutEpoch(
    theme: MarkdownTheme,
    codeTheme: CodeTheme?,
    directiveRegistry: MarkdownDirectiveRegistry,
    config: MarkdownConfig?,
    density: Density,
): InlineLayoutEpoch = InlineLayoutEpoch(
    themeHash = theme.hashCode(),
    codeThemeHash = codeTheme?.hashCode() ?: 0,
    directivePluginsHash = directiveRegistry.directivePlugins.hashCode(),
    configHash = config.hashCode(),
    densityBits = density.density.toBits(),
    fontScaleBits = density.fontScale.toBits(),
)
