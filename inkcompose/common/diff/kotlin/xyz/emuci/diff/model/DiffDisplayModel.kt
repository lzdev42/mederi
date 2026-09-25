package xyz.emuci.diff.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * UI 渲染所需的行项模型。
 */
sealed class DiffDisplayItem {
    abstract val key: String

    data class CodeLine(
        val line: DiffLine,
        override val key: String,
    ) : DiffDisplayItem()

    class Collapsed(
        val id: String,
        val oldStart: Int,
        val newStart: Int,
        val totalCount: Int,
        val hiddenLines: List<DiffLine.Unchanged> = emptyList(),
        initialExpandedTop: Int = 0,
        initialExpandedBottom: Int = 0,
    ) : DiffDisplayItem() {
        override val key: String = id

        var expandedTop by mutableStateOf(initialExpandedTop)
        var expandedBottom by mutableStateOf(initialExpandedBottom)

        val remainingCount: Int
            get() = maxOf(0, totalCount - expandedTop - expandedBottom)

        fun expandTop(amount: Int = 10) {
            val maxCanExpand = totalCount - expandedTop - expandedBottom
            val toAdd = minOf(amount, maxCanExpand)
            expandedTop += toAdd
        }

        fun expandBottom(amount: Int = 10) {
            val maxCanExpand = totalCount - expandedTop - expandedBottom
            val toAdd = minOf(amount, maxCanExpand)
            expandedBottom += toAdd
        }

        fun expandAll() {
            expandedTop = totalCount - expandedBottom
        }

        fun getExpandedTopLines(): List<DiffLine.Unchanged> {
            if (hiddenLines.isEmpty() || expandedTop <= 0) return emptyList()
            return hiddenLines.take(expandedTop)
        }

        fun getExpandedBottomLines(): List<DiffLine.Unchanged> {
            if (hiddenLines.isEmpty() || expandedBottom <= 0) return emptyList()
            val available = hiddenLines.drop(expandedTop)
            return available.takeLast(expandedBottom)
        }
    }
}
