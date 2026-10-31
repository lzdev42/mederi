package xyz.mederi.tools

import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.mederi.tools.patch.PatchApplier
import xyz.mederi.tools.patch.PatchApplyException
import xyz.mederi.tools.patch.PatchHunk
import xyz.mederi.tools.patch.PatchParseException
import xyz.mederi.tools.patch.PatchParser
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Byte-for-byte scenarios from openai/codex 23934d31; see resources/apply-patch-upstream. */
class PatchCompatibilityTest {
    private val resourceRoot: File
        get() = File(requireNotNull(javaClass.getResource("/apply-patch-upstream/scenario-index.txt")).toURI()).parentFile

    private fun scenario(name: String) = runBlocking {
        val fixture = File(resourceRoot, "scenarios/$name")
        val repository = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp").apply { mkdirs() }
        val project = Files.createTempDirectory(scratch.toPath(), "patch-compat-").toFile()
        try {
            val input = File(fixture, "input")
            if (input.exists()) input.copyRecursively(project, overwrite = true)
            val result = FileSystemTools(listOf(project.absolutePath)).ApplyPatchTool()
                .execute(FileSystemTools.ApplyPatchArgs(File(fixture, "patch.txt").readText()))
            // Mederi preflight is intentionally stronger than the upstream CLI's partial-write behavior.
            val expected = if (name == "015_failure_after_partial_success_leaves_changes") input
                else File(fixture, "expected")
            assertSnapshot(expected, project, "$name: $result")
            if (name == "015_failure_after_partial_success_leaves_changes") {
                assertFalse(File(project, "created.txt").exists(), result)
            }
        } finally {
            project.deleteRecursively()
        }
    }

    private fun assertSnapshot(expected: File, actual: File, message: String) {
        fun entries(root: File): Map<String, File> = if (!root.exists()) emptyMap() else root.walkTopDown()
            .filter { it != root }.associateBy { it.relativeTo(root).invariantSeparatorsPath }
        val expectedEntries = entries(expected)
        val actualEntries = entries(actual)
        assertEquals(expectedEntries.keys, actualEntries.keys, message)
        expectedEntries.forEach { (path, file) ->
            val actualFile = actualEntries.getValue(path)
            assertEquals(file.isDirectory, actualFile.isDirectory, "$message ($path)")
            if (file.isFile) assertContentEquals(file.readBytes(), actualFile.readBytes(), "$message ($path)")
        }
    }

    @Test fun `001 add file`() = scenario("001_add_file")
    @Test fun `002 multiple operations`() = scenario("002_multiple_operations")
    @Test fun `003 multiple chunks`() = scenario("003_multiple_chunks")
    @Test fun `004 move to new directory`() = scenario("004_move_to_new_directory")
    @Test fun `005 empty patch leaves files unchanged`() = scenario("005_rejects_empty_patch")
    @Test fun `006 missing context`() = scenario("006_rejects_missing_context")
    @Test fun `007 missing delete source`() = scenario("007_rejects_missing_file_delete")
    @Test fun `008 empty update hunk`() = scenario("008_rejects_empty_update_hunk")
    @Test fun `009 missing update source`() = scenario("009_requires_existing_file_for_update")
    @Test fun `010 move overwrites destination`() = scenario("010_move_overwrites_existing_destination")
    @Test fun `011 add overwrites existing file`() = scenario("011_add_overwrites_existing_file")
    @Test fun `012 delete directory rejected`() = scenario("012_delete_directory_fails")
    @Test fun `013 invalid hunk header`() = scenario("013_rejects_invalid_hunk_header")
    @Test fun `014 update appends newline`() = scenario("014_update_file_appends_trailing_newline")
    @Test fun `015 Mederi preflight prevents partial changes`() = scenario("015_failure_after_partial_success_leaves_changes")
    @Test fun `016 pure addition update chunk`() = scenario("016_pure_addition_update_chunk")
    @Test fun `017 padded hunk header`() = scenario("017_whitespace_padded_hunk_header")
    @Test fun `018 padded patch markers`() = scenario("018_whitespace_padded_patch_markers")
    @Test fun `019 unicode`() = scenario("019_unicode_simple")
    @Test fun `020 delete file`() = scenario("020_delete_file_success")
    @Test fun `020 padded patch marker lines`() = scenario("020_whitespace_padded_patch_marker_lines")
    @Test fun `021 deletion-only update`() = scenario("021_update_file_deletion_only")
    @Test fun `022 end of file`() = scenario("022_update_file_end_of_file_marker")
    @Test fun `023 CRLF preserved`() = scenario("023_preserves_crlf_line_endings")
    @Test fun `024 mixed endings preserved`() = scenario("024_preserves_mixed_line_endings")

    private fun update(original: String, body: String): String {
        val hunk = PatchParser.parse("*** Begin Patch\n*** Update File: file\n$body\n*** End Patch")
            .single() as PatchHunk.UpdateFile
        return PatchApplier.deriveNewContents(original, "file", hunk.chunks)
    }

    @Test fun `context indices distinguish unchanged lines from equal replacement text`() {
        val hunk = PatchParser.parse("*** Begin Patch\n*** Update File: f\n@@\n context\n-same\n+same\n tail\n*** End Patch")
            .single() as PatchHunk.UpdateFile
        assertEquals(listOf(0 to 0, 2 to 2), hunk.chunks.single().contextLineIndices)
        assertEquals("context\r\nsame\r\ntail\r", PatchApplier.deriveNewContents("context\r\nsame\ntail\r", "f", hunk.chunks))
    }

    @Test fun `fuzzy context retains exact whitespace punctuation and line endings`() {
        assertEquals("  curly ‘quote’  \r\nnew\r\ntrailer\n",
            update("  curly ‘quote’  \r\nold\ntrailer\n", "@@\n curly 'quote'\n-old\n+new\n trailer"))
    }

    @Test fun `standalone CR source lines preserved`() {
        assertEquals("first\rnew\rlast\r", update("first\rold\rlast\r", "@@\n first\n-old\n+new\n last"))
    }

    @Test fun `append does not consume actual final blank line`() {
        assertEquals("old\n\nnew\n", update("old\n\n", "@@\n+new"))
    }

    @Test fun `all lines removed yields empty file`() {
        assertEquals("", update("old\n", "@@\n-old"))
    }

    @Test fun `empty file insertion and unterminated append`() {
        assertEquals("new\n", update("", "@@\n+new"))
        assertEquals("old\nnew\n", update("old", "@@\n+new"))
    }

    @Test fun `EOF enforces end and never moves behind cursor`() {
        assertEquals(null, PatchApplier.seekSequence(listOf("a", "b"), listOf("a"), 0, true))
        assertEquals(null, PatchApplier.seekSequence(listOf("a", "b"), listOf("b"), 2, true))
        assertFailsWith<PatchApplyException> {
            update("a\nb\n", "@@ b\n-a\n+new\n*** End of File")
        }
    }

    @Test fun `lookup selects exact occurrence before tolerant occurrence`() {
        assertEquals(1, PatchApplier.seekSequence(listOf("  x", "x"), listOf("x"), 0, false))
        assertEquals(0, PatchApplier.seekSequence(listOf("  x"), listOf("x"), 0, false))
        assertEquals(null, PatchApplier.seekSequence(listOf("x"), listOf("x", "y"), 0, false))
        assertEquals(null, PatchApplier.seekSequence(listOf("x"), listOf("x"), -1, false))
    }

    @Test fun `multiple chunks and function jump apply in order`() {
        assertEquals("def a\nnew-a\ndef b\nnew-b\n", update("def a\nold-a\ndef b\nold-b\n",
            "@@ def a\n-old-a\n+new-a\n@@ def b\n-old-b\n+new-b"))
    }

    @Test fun `EOF newline sentinel is tolerated`() {
        assertEquals("new\n", update("old", "@@\n-old\n+new\n \n*** End of File"))
    }

    @Test fun `heredoc wrapper and CRLF patches parsed`() {
        val text = "*** Begin Patch\r\n*** Add File: f\r\n+hello\r\n*** End Patch"
        assertEquals(PatchHunk.AddFile("f", "hello\n"), PatchParser.parse("<<'EOF'\n$text\nEOF\n").single())
    }

    @Test fun `malformed input has actionable line number`() {
        val exception = assertFailsWith<PatchParseException.InvalidHunk> {
            PatchParser.parse("*** Begin Patch\n*** Update File: f\n@@\n@@\n-old\n+new\n*** End Patch")
        }
        assertEquals(4, exception.lineNumber)
        assertTrue(exception.message.orEmpty().contains("Unexpected line"))
    }

    @Test fun `missing boundaries and empty update rejected`() {
        assertFailsWith<PatchParseException> { PatchParser.parse("+raw") }
        assertFailsWith<PatchParseException> { PatchParser.parse("*** Begin Patch\n*** Update File: f\n*** End Patch") }
        assertFailsWith<PatchParseException> { PatchParser.parse("*** Begin Patch\n*** Add File: f\n+x") }
    }

    @Test fun `unsupported remote routing preamble rejected`() {
        assertFailsWith<PatchParseException> {
            PatchParser.parse("*** Begin Patch\n*** Environment ID: remote\n*** Add File: f\n+x\n*** End Patch")
        }
    }

    @Test fun `non BMP characters and standalone CR in patch content remain data`() {
        assertEquals(PatchHunk.AddFile("emoji-😀.txt", "before\rafter 😀\n"),
            PatchParser.parse("*** Begin Patch\n*** Add File: emoji-😀.txt\n+before\rafter 😀\n*** End Patch").single())
        assertEquals("🧑‍💻 start\r\nnew 😀\r\n", update("🧑‍💻 start\r\nold 😀\r\n",
            "@@\n 🧑‍💻 start\n-old 😀\n+new 😀"))
    }

    @Test fun `fixture list stays complete`() {
        assertEquals(25, File(resourceRoot, "scenario-index.txt").readLines().size)
    }
}
