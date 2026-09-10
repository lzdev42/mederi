package xyz.emuci.markdown.renderer.internal.layout.engine

import xyz.emuci.markdown.renderer.internal.core.model.InternalRenderDocumentModel
import xyz.emuci.markdown.renderer.internal.core.model.InternalRenderBlockModel
import xyz.emuci.markdown.renderer.internal.layout.model.InternalLayoutBlockModel
import xyz.emuci.markdown.renderer.internal.layout.model.InternalLayoutDocumentModel
import xyz.emuci.markdown.renderer.internal.layout.model.InternalLayoutDocumentMetadata

internal interface MarkdownLayoutEngine {
    fun layout(
        document: InternalRenderDocumentModel,
        environment: LayoutEnvironment,
    ): InternalLayoutDocumentModel

    fun layoutBlock(
        block: InternalRenderBlockModel,
        environment: LayoutEnvironment,
    ): InternalLayoutBlockModel

    fun metadata(document: InternalRenderDocumentModel): InternalLayoutDocumentMetadata
}
