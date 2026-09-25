package xyz.emuci.markdown.renderer.internal.layout.inline

import androidx.compose.ui.text.TextStyle
import xyz.emuci.markdown.renderer.inline.InlineRenderResult
import xyz.emuci.markdown.renderer.internal.util.LruCache

internal class InlineRenderResultCache(
    private val maxEntries: Int = DefaultMaxEntries,
) {
    private val entries = LruCache<InlineRenderResultCacheKey, InlineRenderResult>(maxEntries)

    fun getOrPut(
        epoch: InlineLayoutEpoch,
        stableId: Long,
        contentRevision: Long,
        style: TextStyle,
        compute: () -> InlineRenderResult,
    ): InlineRenderResult {
        val key = InlineRenderResultCacheKey(
            epoch = epoch,
            stableId = stableId,
            contentRevision = contentRevision,
            styleHash = style.hashCode(),
        )
        return entries.getOrPut(key, compute)
    }

    fun clear() {
        entries.clear()
    }

    private data class InlineRenderResultCacheKey(
        val epoch: InlineLayoutEpoch,
        val stableId: Long,
        val contentRevision: Long,
        val styleHash: Int,
    )

    private companion object {
        const val DefaultMaxEntries = 2048
    }
}
