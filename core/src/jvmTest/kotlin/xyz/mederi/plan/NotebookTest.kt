package xyz.mederi.plan

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotebookTest {

    private val tmpDir = File.createTempFile("mederi-notebook-test", "").apply {
        delete(); mkdirs()
        File(this, ".mederi").mkdirs()
    }

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `append returns true and persists entry`() {
        val notebook = Notebook(listOf(tmpDir.absolutePath))

        val result = notebook.append("## test entry")

        assertTrue(result)
        val file = File(tmpDir, ".mederi/notebook.md")
        val content = file.readText()
        assertTrue(content.contains("## test entry"), "notebook.md should contain the appended entry")
    }

    @Test
    fun `append returns false when no writable directory`() {
        val notebook = Notebook(emptyList())

        val result = notebook.append("## should not log")

        assertFalse(result)
    }
}
