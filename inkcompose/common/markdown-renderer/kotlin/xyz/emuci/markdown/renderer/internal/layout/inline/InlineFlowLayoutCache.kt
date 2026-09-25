package xyz.emuci.markdown.renderer.internal.layout.inline

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import xyz.emuci.markdown.renderer.internal.util.LruCache

internal class InlineFlowLayoutCache(
    private val maxEntries: Int = DefaultMaxEntries,
) {
    private val entries = LruCache<InlineFlowLayoutCacheKey, InlineFlowLayout>(maxEntries)

    fun getOrPut(
        epoch: InlineLayoutEpoch,
        layoutRevision: Long,
        widthPx: Float,
        maxLines: Int,
        style: TextStyle,
        density: Density,
        compute: () -> InlineFlowLayout,
    ): InlineFlowLayout {
        val key = InlineFlowLayoutCacheKey(
            epoch = epoch,
            layoutRevision = layoutRevision,
            widthBits = widthPx.toBits(),
            maxLines = maxLines,
            styleHash = style.hashCode(),
            densityBits = density.density.toBits(),
            fontScaleBits = density.fontScale.toBits(),
        )
        return entries.getOrPut(key, compute)
    }

    fun clear() {
        entries.clear()
    }

    private data class InlineFlowLayoutCacheKey(
        val epoch: InlineLayoutEpoch,
        val layoutRevision: Long,
        val widthBits: Int,
        val maxLines: Int,
        val styleHash: Int,
        val densityBits: Int,
        val fontScaleBits: Int,
    )

    private companion object {
        const val DefaultMaxEntries = 2048
    }
}
