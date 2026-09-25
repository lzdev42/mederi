package xyz.emuci.markdown.renderer.internal

import xyz.emuci.syntax.theme.CodeTheme
import xyz.emuci.markdown.renderer.MarkdownConfig
import xyz.emuci.markdown.renderer.MarkdownImageRenderer
import xyz.emuci.markdown.renderer.MarkdownTheme
import xyz.emuci.markdown.runtime.MarkdownDirectiveRegistry

data class RendererFacadeState(
    val theme: MarkdownTheme,
    val config: MarkdownConfig,
    val codeTheme: CodeTheme?,
    val imageRenderer: MarkdownImageRenderer?,
    val onLinkClick: ((String) -> Unit)?,
    val directiveRegistry: MarkdownDirectiveRegistry,
    val isStreaming: Boolean,
    val enableSelection: Boolean,
)
