package xyz.emuci.markdown.renderer.block

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import xyz.emuci.diagram.DiagramBlockView
import xyz.emuci.diagram.MermaidSourceDetector
import xyz.emuci.diagram.DiagramCodeFallback
import xyz.emuci.markdown.renderer.DiagramHostRoute
import xyz.emuci.markdown.renderer.LocalDiagramHostRegistry
import xyz.emuci.markdown.renderer.LocalIsStreaming
import xyz.emuci.markdown.parser.ast.DiagramBlock
import xyz.emuci.markdown.renderer.LocalMarkdownTheme
import xyz.emuci.markdown.renderer.internal.core.identity.renderIdentityFromText
import xyz.emuci.markdown.renderer.internal.core.identity.renderIdentityFromValues
import xyz.emuci.markdown.renderer.internal.core.model.DiagramBlockWidgetModel

/**
 * 图表块渲染器。
 *
 * 路由由 [MermaidSourceDetector] 三态检测驱动：Diagram → [DiagramBlockView]
 * （xyz.emuci.diagram 域的 expect/actual，全平台 mermaid Web 渲染）；
 * Pending / Fallback → [DiagramCodeFallback] 源码展示，避免丢失原始内容。
 */
@Composable
internal fun DiagramBlockRenderer(
    node: DiagramBlock,
    modifier: Modifier = Modifier,
) {
    RenderDiagramBlockWidgetModel(
        model = DiagramBlockWidgetModel(
            identity = xyz.emuci.markdown.renderer.internal.core.identity.RenderIdentity(
                stableId = node.stableKey.toLong(),
                contentRevision = node.contentHash,
                layoutRevision = node.contentHash,
                paintRevision = 0L,
            ),
            hostKey = renderIdentityFromValues(
                renderIdentityFromText("DiagramHost"),
                renderIdentityFromText(node.diagramType.lowercase()),
                node.lineRange.startLine.toLong(),
            ),
            diagramType = node.diagramType,
            code = node.literal,
        ),
        modifier = modifier,
    )
}

@Composable
internal fun RenderDiagramBlockWidgetModel(
    model: DiagramBlockWidgetModel,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMarkdownTheme.current
    val isStreaming = LocalIsStreaming.current
    val hostRegistry = LocalDiagramHostRegistry.current
    val diagramBackground = theme.diagramTheme.colors.canvas
    val code = model.code.trimEnd('\n')
    val diagramType = model.diagramType.lowercase()
    val detection = remember(code, diagramType) {
        MermaidSourceDetector.detect(
            source = code,
            hint = diagramType,
        )
    }
    val typeName = remember(model.diagramType) {
        model.diagramType.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
    }
    val hostKey = model.hostKey
    val route = hostRegistry.route(
        hostKey = hostKey,
        detectedDiagram = detection.shouldRouteToDiagram,
        hasCode = code.isNotBlank(),
        isStreaming = isStreaming,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.codeBlockCornerRadius))
            .background(diagramBackground)
            .padding(theme.codeBlockPadding),
    ) {
        when (route) {
            DiagramHostRoute.Diagram -> {
                DiagramBlockView(
                    source = code,
                    theme = theme.diagramTheme,
                    languageHint = diagramType,
                    sessionKey = hostKey,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { size ->
                            if (size.height > 0) {
                                hostRegistry.recordHeightPx(hostKey, size.height.toFloat())
                            }
                        },
                )
            }

            DiagramHostRoute.Pending -> {
                if (code.isBlank()) {
                    Spacer(modifier = Modifier.fillMaxWidth())
                } else {
                    DiagramCodeFallback(
                        code = code,
                        typeName = typeName,
                        modifier = Modifier.fillMaxWidth(),
                        decorate = false,
                    )
                }
            }

            DiagramHostRoute.Fallback -> {
                DiagramCodeFallback(
                    code = code,
                    typeName = typeName,
                    modifier = Modifier.fillMaxWidth(),
                    decorate = false,
                )
            }
        }
    }
}
