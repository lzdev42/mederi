package xyz.emuci.markdown.renderer.internal.compose

import androidx.compose.runtime.Composable
import xyz.emuci.markdown.renderer.internal.layout.engine.MarkdownLayoutSource

internal interface MarkdownComposePainter {
    @Composable
    fun Paint(
        document: MarkdownLayoutSource,
        environment: ComposeRenderEnvironment,
    )
}
