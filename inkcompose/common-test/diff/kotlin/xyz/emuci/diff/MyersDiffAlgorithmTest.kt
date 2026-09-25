package xyz.emuci.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.emuci.diff.algorithm.MyersDiffAlgorithm
import xyz.emuci.diff.model.DiffLine

class MyersDiffAlgorithmTest {

    @Test
    fun should_compute_simple_diff_correctly() {
        val oldText = """
            line 1
            line 2
            line 3
        """.trimIndent()

        val newText = """
            line 1
            line 2 modified
            line 3
            line 4
        """.trimIndent()

        val diff = MyersDiffAlgorithm.computeDiff(oldText, newText, contextRadius = 1, filePath = "test.kt")
        assertEquals("test.kt", diff.fileName)
        assertEquals("kotlin", diff.language)
        assertEquals(1, diff.hunks.size)

        val hunk = diff.hunks[0]
        val lines = hunk.lines

        // Should have line 1 (unchanged), line 2 (deleted), line 2 modified (added), line 3 (unchanged), line 4 (added)
        assertTrue(lines.any { it is DiffLine.Deleted && it.content == "line 2" })
        assertTrue(lines.any { it is DiffLine.Added && it.content == "line 2 modified" })
        assertTrue(lines.any { it is DiffLine.Added && it.content == "line 4" })
        assertEquals(2, diff.stats.additions)
        assertEquals(1, diff.stats.deletions)
    }

    @Test
    fun should_split_into_multiple_hunks_when_distance_exceeds_context() {
        val oldLines = (1..30).map { "line $it" }
        val newLines = oldLines.toMutableList().apply {
            this[2] = "line 3 modified"   // index 2 (line 3)
            this[25] = "line 26 modified" // index 25 (line 26)
        }

        val diff = MyersDiffAlgorithm.computeDiff(
            oldText = oldLines.joinToString("\n"),
            newText = newLines.joinToString("\n"),
            contextRadius = 2,
            filePath = "large.kt"
        )

        // Distance between line 3 and line 26 is 23 lines > 2 * 2 context radius, so should be 2 separate hunks!
        assertEquals(2, diff.hunks.size)
        assertEquals(2, diff.stats.additions)
        assertEquals(2, diff.stats.deletions)
    }
}
