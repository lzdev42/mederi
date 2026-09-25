package xyz.emuci.markdown.renderer.internal.core.compile

import xyz.emuci.markdown.renderer.internal.core.model.InlineModel
import xyz.emuci.markdown.renderer.internal.util.LruCache

/**
 * 编译阶段的增量缓存（每个 [MarkdownEngineHost] 一份，跨流式 tick 复用）。
 *
 * LLM 流式输出每到达一个新 chunk 都会产生新的 [Document]，渲染层随之整篇重新
 * compile。但流式解析会**复用**未变化的稳定前缀节点（引用与 contentHash 均保持），
 * 由此推导出的 inlineRevision/stableId 内容寻址稳定 —— 未变化块的 InlineModel
 * 可以从这里直接取回，跳过逐原子、逐节点的重建与身份哈希。
 *
 * 命中正确性：缓存键 = (inlineRevision, stableId)。inlineRevision 混合了
 * 子节点的 stableId 与 contentHash，是内容的去重指纹；stableId 来自 sourceRange，
 * 保证即使两段文本内容相同但位置不同也不会串用 identity。
 */
class RenderCompileCache(
    private val maxEntries: Int = 4096,
) {
    private val inlineModels = LruCache<InlineModelCacheKey, InlineModel>(maxEntries)

    fun inlineModel(revision: Long, stableId: Long): InlineModel? =
        inlineModels.get(InlineModelCacheKey(revision, stableId))

    fun putInlineModel(revision: Long, stableId: Long, model: InlineModel) {
        inlineModels.put(InlineModelCacheKey(revision, stableId), model)
    }

    private data class InlineModelCacheKey(
        val revision: Long,
        val stableId: Long,
    )
}