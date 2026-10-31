package xyz.mederi.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.Assume.assumeFalse
import xyz.mederi.tools.diff.FileChangeStatus
import xyz.mederi.tools.diff.PatchChange
import xyz.mederi.tools.diff.TurnDiffTracker
import xyz.mederi.tools.patch.PatchParser
import xyz.mederi.tools.sandbox.SandboxConfig
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FileSystemToolsApplyPatchTest {
    private val projects = mutableListOf<File>()
    private fun project(): File {
        val workspace = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile }
        val scratch = File(workspace, "temp").apply { mkdirs() }
        return Files.createTempDirectory(scratch.toPath(), "apply-patch-test-").toFile().canonicalFile.also(projects::add)
    }
    @After fun cleanup() { projects.forEach { it.deleteRecursively() } }
    private fun patch(body: String) = "*** Begin Patch\n$body\n*** End Patch"
    private fun apply(project: File, body: String, touched: ((String) -> Unit)? = null, tracker: TurnDiffTracker? = null): String = runBlocking {
        FileSystemTools(listOf(project.path), tracker, onFileTouched = touched).ApplyPatchTool()
            .execute(FileSystemTools.ApplyPatchArgs(patch(body)))
    }

    @Test fun `mixed operations create update delete and move with correct diffs and callbacks`() {
        val root = project()
        File(root, "edit.txt").writeText("old\n")
        File(root, "delete.txt").writeText("gone\n")
        File(root, "source.txt").writeText("source\n")
        File(root, "dest.txt").writeText("overwritten\n")
        val touched = mutableListOf<String>()
        val tracker = TurnDiffTracker("test", listOf(root.path))
        val result = apply(root, "*** Add File: nested/new.txt\n+new\n*** Update File: edit.txt\n@@\n-old\n+updated\n*** Delete File: delete.txt\n*** Update File: source.txt\n*** Move to: dest.txt\n@@\n-source\n+moved", touched::add, tracker)
        assertTrue(result.startsWith("Success."), result)
        assertEquals("new\n", File(root, "nested/new.txt").readText())
        assertEquals("updated\n", File(root, "edit.txt").readText())
        assertEquals("moved\n", File(root, "dest.txt").readText())
        assertFalse(File(root, "source.txt").exists())
        assertFalse(File(root, "delete.txt").exists())
        assertEquals(setOf("nested/new.txt", "edit.txt", "delete.txt", "source.txt", "dest.txt"), touched.map { File(it).relativeTo(root).path }.toSet())
        val changes = tracker.buildDiff(null, "now").changes.associateBy { it.path }
        assertEquals(FileChangeStatus.DELETED, changes.getValue("source.txt").status)
        assertEquals("overwritten\n", changes.getValue("dest.txt").before)
        assertEquals(FileChangeStatus.MODIFIED, changes.getValue("dest.txt").status)
    }

    @Test fun `Add over existing file tracks original content`() {
        val root = project()
        File(root, "file.txt").writeText("original\n")
        val tracker = TurnDiffTracker("test", listOf(root.path))
        assertTrue(apply(root, "*** Add File: file.txt\n+replacement", tracker = tracker).startsWith("Success."))
        val change = tracker.buildDiff(null, "now").changes.single()
        assertEquals(FileChangeStatus.MODIFIED, change.status)
        assertEquals("original\n", change.before)
    }

    @Test fun `preflight failure leaves every file and directory unchanged`() {
        val root = project()
        File(root, "existing.txt").writeText("actual\n")
        val result = apply(root, "*** Add File: new/nested.txt\n+new\n*** Update File: existing.txt\n@@\n-missing\n+updated")
        assertTrue(result.startsWith("Error: apply_patch verification failed:"), result)
        assertFalse(File(root, "new").exists())
        assertEquals("actual\n", File(root, "existing.txt").readText())
    }

    @Test fun `all source and move destination locks are acquired before validation`() {
        val root = project()
        val source = File(root, "source.txt").apply { writeText("source\n") }
        val dest = File(root, "dest.txt")
        assertEquals(null, FileWriteRegistry.tryAcquire(listOf(dest)))
        try {
            val result = apply(root, "*** Update File: source.txt\n*** Move to: dest.txt\n@@\n-source\n+moved")
            assertTrue(result.contains("being modified by another tool"), result)
            assertEquals("source\n", source.readText())
            assertFalse(dest.exists())
            // A failed multi-lock must not reserve the otherwise available source.
            assertEquals(null, FileWriteRegistry.tryAcquire(listOf(source)))
            FileWriteRegistry.release(listOf(source))
        } finally { FileWriteRegistry.release(listOf(dest)) }
        assertTrue(apply(root, "*** Update File: source.txt\n*** Move to: dest.txt\n@@\n-source\n+moved").startsWith("Success."))
    }

    @Test fun `invalid patches release all locks`() {
        val root = project()
        val file = File(root, "file.txt").apply { writeText("actual\n") }
        assertTrue(apply(root, "*** Update File: file.txt\n@@\n-missing\n+new").startsWith("Error:"))
        assertEquals(null, FileWriteRegistry.tryAcquire(listOf(file)))
        FileWriteRegistry.release(listOf(file))
    }

    @Test fun `duplicate canonical targets and move collisions are rejected`() {
        for (body in listOf(
            "*** Add File: file.txt\n+a\n*** Add File: ./file.txt\n+b",
            "*** Update File: file.txt\n*** Move to: moved.txt\n@@\n-old\n+new\n*** Add File: moved.txt\n+collision",
            "*** Update File: file.txt\n*** Move to: ./file.txt\n@@\n-old\n+new",
            "*** Add File: parent\n+a\n*** Add File: parent/child\n+b",
        )) {
            val root = project()
            File(root, "file.txt").writeText("old\n")
            assertTrue(apply(root, body).startsWith("Error:"), body)
            assertEquals("old\n", File(root, "file.txt").readText())
            assertFalse(File(root, "moved.txt").exists())
            assertFalse(File(root, "parent").exists())
        }
    }

    @Test fun `path traversal directories and non UTF8 files fail without writes`() {
        val root = project()
        val outside = File(root.parentFile, root.name + "-outside.txt")
        try {
            assertTrue(apply(root, "*** Add File: ../${outside.name}\n+escape").startsWith("Error:"))
            assertFalse(outside.exists())
            File(root, "dir").mkdir()
            assertTrue(apply(root, "*** Add File: dir\n+replace directory").startsWith("Error:"))
            for (bytes in listOf(byteArrayOf(0, 1), byteArrayOf(0xc3.toByte(), 0x28))) {
                File(root, "binary").writeBytes(bytes)
                val result = apply(root, "*** Add File: binary\n+text")
                assertTrue(result.startsWith("Error:"), result)
                assertTrue(result.contains("binary"), result)
                assertTrue(bytes.contentEquals(File(root, "binary").readBytes()))
            }
        } finally { outside.delete() }
    }

    @Test fun `symlinks at leaf and ancestors are rejected for every operation`() {
        assumeFalse(System.getProperty("os.name").contains("Windows", ignoreCase = true))
        val root = project()
        val outside = project()
        File(outside, "victim.txt").writeText("original\n")
        File(root, "source.txt").writeText("original\n")
        Files.createSymbolicLink(File(root, "link.txt").toPath(), File(outside, "victim.txt").toPath())
        Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
        for (path in listOf("link.txt", "linked/victim.txt", "linked/new/nested.txt")) {
            for (body in listOf(
                "*** Add File: $path\n+changed",
                "*** Update File: $path\n@@\n-original\n+changed",
                "*** Delete File: $path",
                "*** Update File: source.txt\n*** Move to: $path\n@@\n-original\n+changed",
            )) assertTrue(apply(root, body).startsWith("Error:"), body)
        }
        assertEquals("original\n", File(outside, "victim.txt").readText())
        assertEquals("original\n", File(root, "source.txt").readText())
        assertFalse(File(outside, "new").exists())
        assertTrue(Files.isSymbolicLink(File(root, "link.txt").toPath()))
    }

    @Test fun `dangling links and root targets are rejected`() {
        assumeFalse(System.getProperty("os.name").contains("Windows", ignoreCase = true))
        val root = project()
        Files.createSymbolicLink(File(root, "dangling").toPath(), File(root, "missing").toPath())
        assertTrue(apply(root, "*** Add File: dangling\n+new").startsWith("Error:"))
        assertTrue(apply(root, "*** Add File: .\n+new").startsWith("Error:"))
        assertTrue(Files.isSymbolicLink(File(root, "dangling").toPath()))
    }

    @Test fun `I O failure restores bytes removes created directories and releases locks`() {
        val root = project()
        val old = File(root, "old.txt").apply { writeText("original\n") }
        var writes = 0
        val reports = mutableListOf<PatchChange>()
        val transaction = PatchFileTransaction(listOf(root.path), reports::addAll) { _, rollback ->
            if (!rollback && ++writes == 2) throw IOException("injected failure")
        }
        val result = transaction.apply(PatchParser.parse(patch("*** Add File: new/deep.txt\n+new\n*** Update File: old.txt\n@@\n-original\n+changed")))
        assertTrue(result.contains("All file contents restored"), result)
        assertFalse(File(root, "new").exists())
        assertEquals("original\n", old.readText())
        assertTrue(reports.isEmpty())
        assertEquals(null, FileWriteRegistry.tryAcquire(listOf(old, File(root, "new/deep.txt"))))
        FileWriteRegistry.release(listOf(old, File(root, "new/deep.txt")))
    }

    @Test fun `rollback failure reports and tracks remaining changes truthfully`() {
        val root = project()
        File(root, "old.txt").writeText("original\n")
        var writes = 0
        val reports = mutableListOf<PatchChange>()
        val transaction = PatchFileTransaction(listOf(root.path), reports::addAll) { _, rollback ->
            if (rollback || ++writes == 2) throw IOException("injected failure")
        }
        val result = transaction.apply(PatchParser.parse(patch("*** Add File: new.txt\n+new\n*** Update File: old.txt\n@@\n-original\n+changed")))
        assertTrue(result.contains("Rollback incomplete"), result)
        assertTrue(result.contains("A new.txt"), result)
        assertEquals("new\n", File(root, "new.txt").readText())
        assertEquals(FileChangeStatus.ADDED, reports.single().status)
    }

    @Test fun `cancellation rolls back and propagates`() {
        val root = project()
        var writes = 0
        val transaction = PatchFileTransaction(listOf(root.path), {}) { _, rollback ->
            if (!rollback && ++writes == 2) throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> {
            transaction.apply(PatchParser.parse(patch("*** Add File: first.txt\n+first\n*** Add File: second.txt\n+second")))
        }
        assertFalse(File(root, "first.txt").exists())
        assertEquals(null, FileWriteRegistry.tryAcquire(listOf(File(root, "first.txt"))))
        FileWriteRegistry.release(listOf(File(root, "first.txt")))
    }

    @Test fun `existing executable permissions survive update and move`() {
        val root = project()
        if (!Files.getFileStore(root.toPath()).supportsFileAttributeView("posix")) return
        val script = File(root, "script.sh").apply { writeText("old\n") }
        val permissions = PosixFilePermissions.fromString("rwxr-x---")
        Files.setPosixFilePermissions(script.toPath(), permissions)
        assertTrue(apply(root, "*** Update File: script.sh\n@@\n-old\n+updated").startsWith("Success."))
        assertEquals(permissions, Files.getPosixFilePermissions(script.toPath()))
        assertTrue(apply(root, "*** Update File: script.sh\n*** Move to: moved.sh\n@@\n-updated\n+moved").startsWith("Success."))
        assertEquals(permissions, Files.getPosixFilePermissions(File(root, "moved.sh").toPath()))
    }

    @Test fun `multiple roots retain exact absolute touched paths including deleted source`() {
        val first = project()
        val second = project()
        File(first, "same.txt").writeText("first\n")
        val source = File(second, "source.txt").apply { writeText("second\n") }
        val destination = File(second, "same.txt")
        val touched = mutableListOf<String>()
        val result = runBlocking {
            FileSystemTools(listOf(first.path, second.path), onFileTouched = touched::add).ApplyPatchTool().execute(
                FileSystemTools.ApplyPatchArgs(patch("*** Update File: ${source.path}\n*** Move to: ${destination.path}\n@@\n-second\n+moved")))
        }
        assertTrue(result.startsWith("Success."), result)
        assertEquals(setOf(source.path, destination.path), touched.toSet())
        assertEquals("first\n", File(first, "same.txt").readText())
        assertEquals("moved\n", destination.readText())
    }

    @Test fun `absolute whitelist is honored without affecting relative lookup`() {
        val root = project()
        val whitelist = project()
        val forbidden = project()
        File(whitelist, "existing.txt").writeText("whitelisted\n")
        val previous = SandboxConfig.extraWritablePaths
        try {
            SandboxConfig.extraWritablePaths = listOf(whitelist.path)
            assertTrue(apply(root, "*** Add File: ${File(whitelist, "new.txt").path}\n+allowed").startsWith("Success."))
            assertEquals("allowed\n", File(whitelist, "new.txt").readText())
            assertTrue(apply(root, "*** Add File: ${File(forbidden, "new.txt").path}\n+denied").startsWith("Error:"))
            assertFalse(File(forbidden, "new.txt").exists())
            assertTrue(apply(root, "*** Add File: existing.txt\n+project").startsWith("Success."))
            assertEquals("project\n", File(root, "existing.txt").readText())
            assertEquals("whitelisted\n", File(whitelist, "existing.txt").readText())
        } finally { SandboxConfig.extraWritablePaths = previous }
    }

    @Test fun `generated binary content is rejected during preflight`() {
        val root = project()
        val result = apply(root, "*** Add File: new.txt\n+bad\u0000content")
        assertTrue(result.startsWith("Error:"), result)
        assertFalse(File(root, "new.txt").exists())
    }

    @Test fun `blank and relative whitelist entries return explicit errors without widening scope`() {
        val root = project()
        val previous = SandboxConfig.extraWritablePaths
        try {
            for (entry in listOf("", "   ", "relative-directory")) {
                SandboxConfig.extraWritablePaths = listOf(entry)
                val result = apply(root, "*** Add File: new.txt\n+new")
                assertTrue(result.startsWith("Error:"), result)
                assertTrue(result.contains("Invalid extra writable path"), result)
                assertFalse(File(root, "new.txt").exists())
            }
        } finally { SandboxConfig.extraWritablePaths = previous }
    }

    @Test fun `same content Add and Update preserve file identity modification time and callbacks`() {
        val root = project()
        val file = File(root, "same.txt").apply { writeText("same\n") }
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_000_000_000_000))
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        val touched = mutableListOf<String>()
        for (body in listOf("*** Add File: same.txt\n+same", "*** Update File: same.txt\n@@\n-same\n+same")) {
            val result = apply(root, body, touched = touched::add)
            assertEquals("No files were modified.", result)
            val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            assertEquals(before.lastModifiedTime(), after.lastModifiedTime())
            assertEquals(before.fileKey(), after.fileKey())
            assertEquals("same\n", file.readText())
            assertTrue(touched.isEmpty())
        }
    }

    @Test fun `stable lock lease releases original keys after ancestor symlink swap and closes once`() {
        assumeFalse(System.getProperty("os.name").contains("Windows", ignoreCase = true))
        val root = project()
        val outside = project()
        val directory = File(root, "directory").apply { mkdir() }
        val target = File(directory, "file.txt").apply { writeText("original\n") }
        File(outside, "file.txt").writeText("outside\n")
        val parked = File(root, "parked")
        val acquisition = FileWriteRegistry.tryAcquireLease(listOf(target))
        assertTrue(acquisition is FileWriteRegistry.Acquisition.Acquired)
        val lease = acquisition.lease
        try {
            Files.move(directory.toPath(), parked.toPath())
            Files.createSymbolicLink(directory.toPath(), outside.toPath())
            lease.close()
            Files.delete(directory.toPath())
            Files.move(parked.toPath(), directory.toPath())
            assertEquals(null, FileWriteRegistry.tryAcquire(listOf(target)))
            // A second close must not unlock the subsequent caller's reservation.
            lease.close()
            assertTrue(FileWriteRegistry.tryAcquireLease(listOf(target)) is FileWriteRegistry.Acquisition.Conflict)
        } finally {
            lease.close()
            if (Files.isSymbolicLink(directory.toPath())) Files.delete(directory.toPath())
            if (parked.exists()) Files.move(parked.toPath(), directory.toPath())
            FileWriteRegistry.release(listOf(target))
        }
    }

    @Test fun `move overwrite retains existing destination permissions`() {
        val root = project()
        if (!Files.getFileStore(root.toPath()).supportsFileAttributeView("posix")) return
        val source = File(root, "source.sh").apply { writeText("source\n") }
        val destination = File(root, "destination.sh").apply { writeText("destination\n") }
        Files.setPosixFilePermissions(source.toPath(), PosixFilePermissions.fromString("rwxr-x---"))
        val destinationPermissions = PosixFilePermissions.fromString("rw-------")
        Files.setPosixFilePermissions(destination.toPath(), destinationPermissions)
        val result = apply(root, "*** Update File: source.sh\n*** Move to: destination.sh\n@@\n-source\n+moved")
        assertTrue(result.startsWith("Success."), result)
        assertEquals(destinationPermissions, Files.getPosixFilePermissions(destination.toPath()))
        assertEquals("moved\n", destination.readText())
        assertFalse(source.exists())
    }

    @Test fun `tracking failure does not misreport successful disk changes as failed`() {
        val root = project()
        val result = apply(root, "*** Add File: new.txt\n+new", touched = { throw IllegalStateException("callback failed") })
        assertTrue(result.startsWith("Success."), result)
        assertTrue(result.contains("Warning:"), result)
        assertEquals("new\n", File(root, "new.txt").readText())
    }
}
