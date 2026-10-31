package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult
import xyz.emuci.syntax.renderer.CodeTextSelectionObserver
import kotlin.math.abs

/** Actual painted text geometry, shared by code lines, table cells and inline code.
 * Logical offsets remain in the enclosing document block; coordinates are only kept while visible.
 */
internal class SelectionTextFragmentRegistry {
    private class Fragment {
        var coordinates: LayoutCoordinates? = null
        var layout: TextLayoutResult? = null
    }
    private val blocks = mutableMapOf<Long, MutableMap<Int, Fragment>>()
    private fun fragment(block: Long, offset: Int) = blocks.getOrPut(block) { mutableMapOf() }
        .getOrPut(offset) { Fragment() }

    fun observer(block: Long, baseOffset: Int = 0) = CodeTextSelectionObserver(
        onPositioned = { offset, coordinates -> fragment(block, baseOffset + offset).coordinates = coordinates },
        onTextLayout = { offset, layout -> fragment(block, baseOffset + offset).layout = layout },
        onDispose = { offset -> blocks[block]?.remove(baseOffset + offset) },
    )

    fun removeBlock(block: Long) { blocks.remove(block) }

    fun offsetAt(block: Long, windowPosition: Offset): Int? {
        var nearest: Int? = null
        var penalty = Float.MAX_VALUE
        for ((offset, fragment) in blocks[block].orEmpty()) {
            val coordinates = fragment.coordinates?.takeIf { it.isAttached } ?: continue
            val layout = fragment.layout ?: continue
            val local = coordinates.windowToLocal(windowPosition)
            val dx = when { local.x < 0 -> -local.x; local.x > coordinates.size.width -> local.x - coordinates.size.width; else -> 0f }
            val dy = when { local.y < 0 -> -local.y; local.y > coordinates.size.height -> local.y - coordinates.size.height; else -> 0f }
            val distance = dy * 1000f + dx
            if (distance < penalty) {
                penalty = distance
                nearest = offset + clampToCharBoundary(layout.layoutInput.text, layout.getOffsetForPosition(local))
            }
        }
        return nearest
    }

    fun highlightBoxes(
        block: Long,
        range: SelectionRange?,
        index: SelectionModelIndex,
        blockCoordinates: LayoutCoordinates?,
    ): List<Rect>? {
        val fragments = blocks[block]?.takeIf { it.isNotEmpty() } ?: return null
        val coordinates = blockCoordinates?.takeIf { it.isAttached } ?: return null
        range ?: return emptyList()
        val order = index.orderOf(block) ?: return emptyList()
        if (order < (index.orderOf(range.start.blockStableId) ?: return emptyList()) ||
            order > (index.orderOf(range.end.blockStableId) ?: return emptyList())) return emptyList()
        val start = if (range.start.blockStableId == block) range.start.charInBlock else 0
        val end = if (range.end.blockStableId == block) range.end.charInBlock else Int.MAX_VALUE
        return buildList {
            for ((offset, fragment) in fragments) {
                val textCoordinates = fragment.coordinates?.takeIf { it.isAttached } ?: continue
                val layout = fragment.layout ?: continue
                val from = (start - offset).coerceIn(0, layout.layoutInput.text.length)
                val to = (end - offset).coerceIn(0, layout.layoutInput.text.length)
                // Merge neighbouring glyphs on each painted line. Keep disjoint bidi runs
                // separate rather than filling the unselected gap between their visual positions.
                var merged: Rect? = null
                for (character in from until to) {
                    val box = layout.getBoundingBox(character)
                    val topLeft = coordinates.windowToLocal(textCoordinates.localToWindow(box.topLeft))
                    val bottomRight = coordinates.windowToLocal(textCoordinates.localToWindow(box.bottomRight))
                    val next = Rect(topLeft, bottomRight)
                    val previous = merged
                    merged = if (previous != null &&
                        abs(previous.top - next.top) < 0.5f && abs(previous.bottom - next.bottom) < 0.5f &&
                        next.left <= previous.right + 0.5f && next.right >= previous.left - 0.5f
                    ) {
                        Rect(minOf(previous.left, next.left), minOf(previous.top, next.top),
                            maxOf(previous.right, next.right), maxOf(previous.bottom, next.bottom))
                    } else {
                        previous?.let { add(it) }
                        next
                    }
                }
                merged?.let { add(it) }
            }
        }
    }

    fun positionFor(anchor: SelectionAnchor): Offset? {
        val fragments = blocks[anchor.blockStableId] ?: return null
        val entry = fragments.entries.filter { (offset, fragment) ->
            val length = fragment.layout?.layoutInput?.text?.length ?: return@filter false
            anchor.charInBlock in offset..(offset + length)
        }.lastOrNull() ?: return null
        val coordinates = entry.value.coordinates?.takeIf { it.isAttached } ?: return null
        val layout = entry.value.layout ?: return null
        val rect = layout.getCursorRect((anchor.charInBlock - entry.key).coerceIn(0, layout.layoutInput.text.length))
        return coordinates.localToWindow(rect.bottomLeft)
    }
}

internal data class SelectionTextScope(val blockStableId: Long, val offset: Int = 0)
internal val LocalSelectionTextScope = staticCompositionLocalOf<SelectionTextScope?> { null }
