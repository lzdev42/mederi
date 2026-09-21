package xyz.emuci.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.emuci.diff.model.DiffLine
import xyz.emuci.diff.parser.UnifiedDiffParser

class UnifiedDiffParserTest {

    @Test
    fun should_parse_standard_git_diff_patch() {
        val patch = """
            diff --git a/strings.xml b/strings.xml
            index 1234567..89abcdef 100644
            --- a/strings.xml
            +++ b/strings.xml
            @@ -1,3 +1,4 @@
             <resources>
            -    <string name="old">Old</string>
            +    <string name="new">New</string>
            +    <string name="new2">New 2</string>
             </resources>
        """.trimIndent()

        val files = UnifiedDiffParser.parse(patch)
        assertEquals(1, files.size)

        val file = files[0]
        assertEquals("strings.xml", file.fileName)
        assertEquals("xml", file.language)
        assertEquals(1, file.hunks.size)

        val hunk = file.hunks[0]
        assertEquals(1, hunk.oldStart)
        assertEquals(3, hunk.oldCount)
        assertEquals(1, hunk.newStart)
        assertEquals(4, hunk.newCount)

        assertEquals(5, hunk.lines.size)
        assertTrue(hunk.lines[0] is DiffLine.Unchanged)
        assertTrue(hunk.lines[1] is DiffLine.Deleted)
        assertTrue(hunk.lines[2] is DiffLine.Added)
        assertTrue(hunk.lines[3] is DiffLine.Added)
        assertTrue(hunk.lines[4] is DiffLine.Unchanged)

        assertEquals(2, file.stats.additions)
        assertEquals(1, file.stats.deletions)
    }

    @Test
    fun should_parse_multiple_files_in_patch() {
        val patch = """
            diff --git a/A.kt b/A.kt
            --- a/A.kt
            +++ b/A.kt
            @@ -10,2 +10,2 @@
            -val a = 1
            +val a = 2
             val b = 3
            diff --git a/B.kt b/B.kt
            --- a/B.kt
            +++ b/B.kt
            @@ -1,1 +1,2 @@
            +val new = true
             val old = false
        """.trimIndent()

        val files = UnifiedDiffParser.parse(patch)
        assertEquals(2, files.size)
        assertEquals("A.kt", files[0].fileName)
        assertEquals("kotlin", files[0].language)
        assertEquals("B.kt", files[1].fileName)
        assertEquals("kotlin", files[1].language)
    }
}
