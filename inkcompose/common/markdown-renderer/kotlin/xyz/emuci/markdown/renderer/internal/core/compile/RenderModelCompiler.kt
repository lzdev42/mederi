package xyz.emuci.markdown.renderer.internal.core.compile

import xyz.emuci.markdown.parser.ast.Document
import xyz.emuci.markdown.renderer.internal.core.model.InternalRenderDocumentModel

interface RenderModelCompiler {
    fun compile(
        document: Document,
        environment: RenderCompileEnvironment,
    ): InternalRenderDocumentModel
}
