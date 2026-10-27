package xyz.emuci.diff

import xyz.emuci.diff.algorithm.MyersDiffAlgorithm
import xyz.emuci.diff.model.DiffDisplayItem
import xyz.emuci.diff.model.DiffLine
import xyz.emuci.inkcompose.buildDisplayItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffDisplayItemsTest {

    @Test
    fun should_populateHiddenLines_when_sourceAvailable() {
        val oldText = (1..30).joinToString("\n") { "line $it" }
        val newText = (1..30).joinToString("\n") { if (it == 15) "line 15 CHANGED" else "line $it" }
        val diff = MyersDiffAlgorithm.computeDiff(oldText, newText, contextRadius = 3, filePath = "test.kt")
        // 源行已填充
        assertEquals(30, diff.oldSourceLines.size)
        val items = buildDisplayItems(diff)
        // 标准 patch 行为：只在 hunk 之间生成折叠，最后一个 hunk 之后无尾随折叠。
        // 30 行、第 15 行改动、contextRadius=3 → 1 个 hunk（12..18）→ 1 个 Collapsed（line 1..11 前间隙）
        val collapsed = items.filterIsInstance<DiffDisplayItem.Collapsed>()
        assertTrue(collapsed.isNotEmpty(), "expected at least 1 gap, got ${collapsed.size}")
        val first = collapsed.first()
        assertTrue(first.hiddenLines.isNotEmpty(), "hiddenLines must be populated when source available")
        // 前间隙 oldLineNo 从 1 开始
        assertEquals(1, first.hiddenLines.first().oldLineNo)
        // 展开验证
        first.expandTop(10)
        assertEquals(10, first.getExpandedTopLines().size)
        assertEquals(first.hiddenLines.size - 10, first.remainingCount)
        first.expandAll()
        assertEquals(0, first.remainingCount)
        assertEquals(first.hiddenLines.size, first.getExpandedTopLines().size)
    }

    @Test
    fun should_leaveHiddenLinesEmpty_when_noSource() {
        val diff = MyersDiffAlgorithm.computeDiff("a\nb\nc", "a\nB\nc", contextRadius = 0, filePath = null)
        // 手动构造无源行的 DiffFile 模拟 patch 路径
        val patchDiff = diff.copy(oldSourceLines = emptyList(), newSourceLines = emptyList())
        val items = buildDisplayItems(patchDiff)
        val collapsed = items.filterIsInstance<DiffDisplayItem.Collapsed>()
        if (collapsed.isNotEmpty()) {
            assertTrue(collapsed.all { it.hiddenLines.isEmpty() }, "patch path must have empty hiddenLines")
        }
    }
}
