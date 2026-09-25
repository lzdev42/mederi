package xyz.emuci.markdown.renderer.internal.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import xyz.emuci.markdown.renderer.LocalMarkdownTheme
import xyz.emuci.markdown.renderer.MarkdownRenderMode
import xyz.emuci.markdown.renderer.block.FencedCodeBlockRenderer
import xyz.emuci.markdown.renderer.block.MathBlockRenderer
import xyz.emuci.markdown.renderer.block.PageBreakRenderer
import xyz.emuci.markdown.renderer.block.RenderAdmonitionBlockModel
import xyz.emuci.markdown.renderer.block.RenderBibliographyBlockModel
import xyz.emuci.markdown.renderer.block.RenderBibliographyLayoutBlockModel
import xyz.emuci.markdown.renderer.block.RenderBlockQuoteBlockModel
import xyz.emuci.markdown.renderer.block.RenderColumnsLayoutBlockModel
import xyz.emuci.markdown.renderer.block.RenderColumnsLayoutGroupModel
import xyz.emuci.markdown.renderer.block.RenderCustomContainerBlockModel
import xyz.emuci.markdown.renderer.block.RenderDefinitionListBlockModel
import xyz.emuci.markdown.renderer.block.RenderDefinitionListLayoutBlockModel
import xyz.emuci.markdown.renderer.block.RenderDiagramBlockWidgetModel
import xyz.emuci.markdown.renderer.block.RenderVerticalTextBlockWidgetModel
import xyz.emuci.markdown.renderer.block.RenderDirectiveFallbackBlockModel
import xyz.emuci.markdown.renderer.block.RenderFigureBlockModel
import xyz.emuci.markdown.renderer.block.RenderFigureLayoutBlockModel
import xyz.emuci.markdown.renderer.block.RenderFootnoteDefinitionBlockModel
import xyz.emuci.markdown.renderer.block.RenderFootnoteLayoutBlockModel
import xyz.emuci.markdown.renderer.block.RenderHtmlBlockModel
import xyz.emuci.markdown.renderer.block.RenderListBlockModel
import xyz.emuci.markdown.renderer.block.RenderListLayoutBlockModel
import xyz.emuci.markdown.renderer.block.RenderTabBlockModel
import xyz.emuci.markdown.renderer.block.RenderTabLayoutBlockModel
import xyz.emuci.markdown.renderer.block.RenderTableBlockModel
import xyz.emuci.markdown.renderer.block.RenderTableLayoutBlockModel
import xyz.emuci.markdown.renderer.block.RenderTocLayoutBlockModel
import xyz.emuci.markdown.renderer.block.ThematicBreakRenderer
import xyz.emuci.markdown.renderer.internal.adapter.createDirectiveBlockRenderScope
import xyz.emuci.markdown.renderer.internal.core.model.AdmonitionBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.BibliographyDefinitionBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.BlockQuoteBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.CodeBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.CodeBlockWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.ColumnsLayoutBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.CustomContainerBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.DefinitionListBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.DiagramBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.DiagramBlockWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.DirectiveBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.FallbackContainerBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.FallbackLeafBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.FigureBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.FootnoteDefinitionBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.HeadingBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.HtmlBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.InternalRenderBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.ListBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.MathBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.MathBlockWidgetModel
import xyz.emuci.markdown.renderer.internal.core.model.PageBreakBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.ParagraphBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.TabBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.TableBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.ThematicBreakBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.TocBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.VerticalTextBlockModel
import xyz.emuci.markdown.renderer.internal.core.model.VerticalTextBlockWidgetModel
import xyz.emuci.markdown.renderer.internal.layout.engine.MarkdownLayoutSource
import xyz.emuci.markdown.renderer.internal.layout.inline.LayoutInlineRunPlacement
import xyz.emuci.markdown.renderer.internal.layout.model.LayoutBibliographyBlockModel
import xyz.emuci.markdown.renderer.internal.layout.model.LayoutColumnsBlockModel
import xyz.emuci.markdown.renderer.internal.layout.model.LayoutDefinitionListBlockModel
import xyz.emuci.markdown.renderer.internal.layout.model.LayoutFigureBlockModel
import xyz.emuci.markdown.renderer.internal.layout.model.LayoutFootnoteBlockModel
import xyz.emuci.markdown.renderer.internal.layout.model.LayoutInlineBlockModel
import xyz.emuci.markdown.renderer.internal.layout.model.LayoutListBlockModel
import xyz.emuci.markdown.renderer.internal.layout.model.LayoutRect
import xyz.emuci.markdown.renderer.internal.layout.model.LayoutRenderBlockModel
import xyz.emuci.markdown.renderer.internal.layout.model.LayoutTabBlockModel
import xyz.emuci.markdown.renderer.internal.layout.model.LayoutTableBlockModel
import xyz.emuci.markdown.renderer.internal.layout.model.LayoutTocBlockModel
import xyz.emuci.markdown.renderer.internal.layout.model.LayoutWidgetBlockModel
import xyz.emuci.markdown.renderer.internal.selection.LocalMarkdownSelectionController
import xyz.emuci.markdown.renderer.internal.selection.selectableBlock
import kotlinx.coroutines.flow.distinctUntilChanged

internal object DefaultMarkdownComposePainter : MarkdownComposePainter {
    @Composable
    override fun Paint(
        document: MarkdownLayoutSource,
        environment: ComposeRenderEnvironment,
    ) {
        when (environment.renderMode) {
            MarkdownRenderMode.LazyColumn -> {
                val lazyListState = environment.lazyListState ?: rememberLazyListState()
                val documentStartIndex = if (environment.header == null) 0 else 1
                PrefetchNextMarkdownBlock(
                    document = document,
                    state = lazyListState,
                    documentStartIndex = documentStartIndex,
                )
                LazyColumn(
                    state = lazyListState,
                    modifier = environment.modifier.graphicsLayer { },
                    verticalArrangement = Arrangement.spacedBy(LocalMarkdownTheme.current.blockSpacing),
                ) {
                    environment.header?.let { header ->
                        item(key = "markdown_header") { header() }
                    }
                    items(
                        count = document.blockCount,
                        key = document::stableIdAt,
                    ) { index ->
                        PaintBlock(document.blockAt(index))
                    }
                    environment.footer?.let { footer ->
                        item(key = "markdown_footer") { footer() }
                    }
                }
            }

            MarkdownRenderMode.StaticColumn -> {
                val body: @Composable () -> Unit = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(LocalMarkdownTheme.current.blockSpacing),
                    ) {
                        for (index in 0 until document.blockCount) {
                            key(document.stableIdAt(index)) {
                                PaintBlock(document.blockAt(index))
                            }
                        }
                    }
                }
                Column(
                    modifier = environment.modifier
                        .then(
                            if (environment.enableScroll && environment.scrollState != null) {
                                Modifier.verticalScroll(environment.scrollState)
                            } else {
                                Modifier
                            }
                        )
                        .graphicsLayer { },
                    verticalArrangement = Arrangement.spacedBy(LocalMarkdownTheme.current.blockSpacing),
                ) {
                    environment.header?.invoke()
                    body()
                    environment.footer?.invoke()
                }
            }
        }
    }
}

@Composable
private fun PrefetchNextMarkdownBlock(
    document: MarkdownLayoutSource,
    state: LazyListState,
    documentStartIndex: Int,
) {
    LaunchedEffect(document, state, documentStartIndex) {
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
                ?.minus(documentStartIndex)
        }
            .distinctUntilChanged()
            .collect { lastVisibleDocumentIndex ->
                if (lastVisibleDocumentIndex != null) {
                    document.prefetch(lastVisibleDocumentIndex + 1)
                }
            }
    }
}

@Composable
private fun PaintBlock(block: xyz.emuci.markdown.renderer.internal.layout.model.InternalLayoutBlockModel) {
    when (block) {
        is LayoutRenderBlockModel -> PaintRenderBlock(block)
        is LayoutListBlockModel -> RenderListLayoutBlockModel(
            model = block,
            modifier = Modifier.fillMaxWidth(),
            renderChildren = ::PaintLayoutBlockChildren,
        )

        is LayoutColumnsBlockModel -> RenderColumnsLayoutGroupModel(
            model = block,
            modifier = Modifier.fillMaxWidth(),
            renderChildren = ::PaintLayoutBlockColumn,
        )

        is LayoutTableBlockModel -> RenderTableLayoutBlockModel(
            model = block,
            modifier = Modifier
                .fillMaxWidth()
                .selectableBlock(block, LocalMarkdownSelectionController.current),
        )

        is LayoutDefinitionListBlockModel -> RenderDefinitionListLayoutBlockModel(
            model = block,
            modifier = Modifier.fillMaxWidth(),
            renderChildren = ::PaintLayoutBlockChildren,
        )

        is LayoutFigureBlockModel -> RenderFigureLayoutBlockModel(
            model = block,
            modifier = Modifier.fillMaxWidth(),
        )

        is LayoutTocBlockModel -> RenderTocLayoutBlockModel(
            model = block,
            modifier = Modifier.fillMaxWidth(),
        )

        is LayoutBibliographyBlockModel -> RenderBibliographyLayoutBlockModel(
            model = block,
            modifier = Modifier.fillMaxWidth(),
        )

        is LayoutTabBlockModel -> RenderTabLayoutBlockModel(
            model = block,
            modifier = Modifier.fillMaxWidth(),
            renderChildren = ::PaintLayoutBlockColumn,
        )

        is LayoutFootnoteBlockModel -> RenderFootnoteLayoutBlockModel(
            model = block,
            modifier = Modifier.fillMaxWidth(),
            renderLeadChild = { child ->
                if (child != null) {
                    Box {
                        PaintBlock(child)
                    }
                }
            },
            renderTrailingChildren = { children ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(LocalMarkdownTheme.current.blockSpacing),
                ) {
                    PaintLayoutBlockChildren(children)
                }
            },
        )

        is LayoutWidgetBlockModel -> PaintWidgetBlock(
            block,
            modifier = Modifier
                .fillMaxWidth()
                .selectableBlock(block, LocalMarkdownSelectionController.current),
        )

        is LayoutInlineBlockModel -> PaintInlineBlock(block)
    }
}

@Composable
private fun PaintRenderBlock(block: LayoutRenderBlockModel) {
    when (val renderBlock = block.block) {
        is CodeBlockModel -> FencedCodeBlockRenderer(
            text = renderBlock.code,
            language = renderBlock.language,
            title = renderBlock.title,
            showLineNumbers = renderBlock.showLineNumbers,
            startLine = renderBlock.startLine,
            highlightedLines = renderBlock.highlightedLines,
            modifier = Modifier.fillMaxWidth(),
        )

        is MathBlockModel -> MathBlockRenderer(
            latex = renderBlock.latex,
            modifier = Modifier.fillMaxWidth(),
        )

        is BlockQuoteBlockModel -> RenderBlockQuoteBlockModel(
            model = renderBlock,
            modifier = Modifier.fillMaxWidth(),
        ) {
            PaintLayoutBlockColumn(block.children)
        }

        is ListBlockModel -> RenderListBlockModel(
            model = renderBlock,
            modifier = Modifier.fillMaxWidth(),
            renderChildren = ::PaintRenderBlockChildren
        )

        is TableBlockModel -> RenderTableBlockModel(
            model = renderBlock,
            modifier = Modifier.fillMaxWidth(),
        )

        is AdmonitionBlockModel -> RenderAdmonitionBlockModel(
            model = renderBlock,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (renderBlock.children.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(LocalMarkdownTheme.current.blockSpacing),
                ) {
                    PaintLayoutBlockChildren(block.children)
                }
            }
        }

        is HtmlBlockModel -> RenderHtmlBlockModel(
            model = renderBlock,
            modifier = Modifier
                .fillMaxWidth()
                .selectableBlock(block, LocalMarkdownSelectionController.current),
        )

        is CustomContainerBlockModel -> RenderCustomContainerBlockModel(
            model = renderBlock,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (block.children.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(LocalMarkdownTheme.current.blockSpacing),
                ) {
                    PaintLayoutBlockChildren(block.children)
                }
            }
        }

        is ColumnsLayoutBlockModel -> RenderColumnsLayoutBlockModel(
            model = renderBlock,
            modifier = Modifier.fillMaxWidth(),
            renderChildren = ::PaintRenderBlockColumn
        )

        is DefinitionListBlockModel -> RenderDefinitionListBlockModel(
            model = renderBlock,
            modifier = Modifier.fillMaxWidth(),
            renderChildren = ::PaintRenderBlockChildren,
        )

        is FootnoteDefinitionBlockModel -> RenderFootnoteDefinitionBlockModel(
            model = renderBlock,
            modifier = Modifier.fillMaxWidth(),
            renderLeadContent = {},
            renderTrailingContent = {},
        )

        is TocBlockModel -> {
            if (renderBlock.entries.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(LocalMarkdownTheme.current.blockSpacing / 2),
                ) {
                    renderBlock.entries.forEach { entry ->
                        androidx.compose.material3.Text(
                            text = "${"  ".repeat((entry.level - 1).coerceAtLeast(0))}• ${entry.text}",
                            style = LocalMarkdownTheme.current.bodyStyle,
                            color = LocalMarkdownTheme.current.linkColor,
                        )
                    }
                }
            }
        }

        is PageBreakBlockModel -> PageBreakRenderer(
            modifier = Modifier.fillMaxWidth(),
        )

        is DirectiveBlockModel -> {
            val renderer = xyz.emuci.markdown.renderer.LocalMarkdownDirectiveRegistry.current
                .findBlockDirectiveRenderer(renderBlock.tagName)
            if (renderer != null) {
                renderer(
                    createDirectiveBlockRenderScope(
                        tagName = renderBlock.tagName,
                        args = renderBlock.args,
                        content = if (block.children.isNotEmpty()) {
                            { PaintLayoutBlockColumn(block.children) }
                        } else {
                            null
                        },
                    )
                )
            } else {
                RenderDirectiveFallbackBlockModel(
                    tagName = renderBlock.tagName,
                    args = renderBlock.args,
                    modifier = Modifier.fillMaxWidth(),
                    renderChildren = if (block.children.isNotEmpty()) {
                        { PaintLayoutBlockChildren(block.children) }
                    } else {
                        null
                    },
                )
            }
        }

        is TabBlockModel -> RenderTabBlockModel(
            model = renderBlock,
            modifier = Modifier.fillMaxWidth(),
            renderChildren = ::PaintRenderBlockColumn
        )

        is BibliographyDefinitionBlockModel -> RenderBibliographyBlockModel(
            model = renderBlock,
            modifier = Modifier.fillMaxWidth(),
        )

        is FigureBlockModel -> RenderFigureBlockModel(
            model = renderBlock,
            modifier = Modifier.fillMaxWidth(),
        )

        is DiagramBlockModel -> RenderDiagramBlockWidgetModel(
            model = renderBlock.widget as DiagramBlockWidgetModel,
            modifier = Modifier.fillMaxWidth(),
        )

        is VerticalTextBlockModel -> RenderVerticalTextBlockWidgetModel(
            model = renderBlock.widget as VerticalTextBlockWidgetModel,
            modifier = Modifier.fillMaxWidth(),
        )

        is ThematicBreakBlockModel -> ThematicBreakRenderer(
            modifier = Modifier.fillMaxWidth(),
        )

        is FallbackContainerBlockModel -> {
            if (block.children.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(LocalMarkdownTheme.current.blockSpacing),
                ) {
                    PaintLayoutBlockChildren(block.children)
                }
            }
        }

        is FallbackLeafBlockModel -> Unit
        is ParagraphBlockModel,
        is HeadingBlockModel -> Unit
    }
}

@Composable
private fun PaintInlineBlock(block: LayoutInlineBlockModel) {
    val selectionController = LocalMarkdownSelectionController.current
    // Keep the normal rendering path callback-free. Starting a selection recreates BasicText
    // once so its existing layout result can serve all subsequent highlights and handle drags.
    val onTextItemLayout = if (selectionController?.hasSelection != true) {
        null
    } else {
        remember(block.identity.stableId, selectionController) {
            { placements: List<LayoutInlineRunPlacement>, result: TextLayoutResult ->
                selectionController.registerTextLayout(block.identity.stableId, placements, result)
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableBlock(block, selectionController)
    ) {
        PaintInlineLayoutContent(
            block = block,
            modifier = Modifier.fillMaxWidth(),
            onTextItemLayout = onTextItemLayout,
        )
        if (block.showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 4.dp),
                thickness = LocalMarkdownTheme.current.dividerThickness,
                color = LocalMarkdownTheme.current.dividerColor,
            )
        }
    }
}

@Composable
private fun PaintRenderBlockColumn(
    blocks: List<InternalRenderBlockModel>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LocalMarkdownTheme.current.blockSpacing),
    ) {
        PaintRenderBlockChildren(blocks)
    }
}

@Composable
private fun PaintRenderBlockChildren(
    blocks: List<InternalRenderBlockModel>,
) {
    for (block in blocks) {
        key(block.identity.stableId) {
            PaintCompiledBlock(block)
        }
    }
}

@Composable
private fun PaintLayoutBlockColumn(
    blocks: List<xyz.emuci.markdown.renderer.internal.layout.model.InternalLayoutBlockModel>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LocalMarkdownTheme.current.blockSpacing),
    ) {
        PaintLayoutBlockChildren(blocks)
    }
}

@Composable
private fun PaintLayoutBlockChildren(
    blocks: List<xyz.emuci.markdown.renderer.internal.layout.model.InternalLayoutBlockModel>,
) {
    for (block in blocks) {
        key(block.identity.stableId) {
            PaintBlock(block)
        }
    }
}

@Composable
private fun PaintCompiledBlock(block: InternalRenderBlockModel) {
    PaintRenderBlock(
        LayoutRenderBlockModel(
            identity = block.identity,
            frame = xyz.emuci.markdown.renderer.internal.layout.model.LayoutRect(
                left = 0f,
                top = 0f,
                width = 0f,
                height = 0f,
            ),
            contentFrame = LayoutRect(
                left = 0f,
                top = 0f,
                width = 0f,
                height = 0f,
            ),
            block = block,
        )
    )
}

@Composable
private fun PaintWidgetBlock(
    block: LayoutWidgetBlockModel,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    when (val widget = block.widget) {
        is CodeBlockWidgetModel -> FencedCodeBlockRenderer(
            text = widget.code,
            language = widget.language,
            title = widget.title,
            showLineNumbers = (block.block as? CodeBlockModel)?.showLineNumbers ?: true,
            startLine = (block.block as? CodeBlockModel)?.startLine ?: 1,
            highlightedLines = (block.block as? CodeBlockModel)?.highlightedLines ?: emptySet(),
            modifier = modifier,
        )

        is MathBlockWidgetModel -> MathBlockRenderer(
            latex = widget.latex,
            modifier = modifier,
        )

        is DiagramBlockWidgetModel -> RenderDiagramBlockWidgetModel(
            model = widget,
            modifier = modifier,
        )

        is VerticalTextBlockWidgetModel -> RenderVerticalTextBlockWidgetModel(
            model = widget,
            modifier = modifier,
        )
    }
}
