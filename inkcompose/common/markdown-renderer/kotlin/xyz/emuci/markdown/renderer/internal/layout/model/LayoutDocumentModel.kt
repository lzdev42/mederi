package xyz.emuci.markdown.renderer.internal.layout.model

import xyz.emuci.markdown.renderer.internal.core.identity.RenderIdentity

data class InternalLayoutDocumentModel(
    val identity: RenderIdentity,
    val blocks: List<InternalLayoutBlockModel>,
    val totalSize: LayoutSize,
    val metadata: InternalLayoutDocumentMetadata = InternalLayoutDocumentMetadata(),
)

data class InternalLayoutDocumentMetadata(
    val footnoteDefinitionItemIndexes: Map<String, Int> = emptyMap(),
)
