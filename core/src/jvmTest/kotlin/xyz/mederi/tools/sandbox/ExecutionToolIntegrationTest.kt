package xyz.mederi.tools.sandbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mederi.office.OfficeTools
import xyz.mederi.tools.FileSystemTools
import xyz.mederi.tools.ShellTools
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionToolIntegrationTest {
    private fun fixture(block: suspend CoroutineScope.(File) -> Unit) = runBlocking {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/execution-permissions-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }

    private suspend fun waiting(manager: SandboxManager): ExecutionApprovalRequest = withTimeout(3000) {
        while (manager.pendingApprovals("conversation").isEmpty()) delay(1)
        manager.pendingApprovals("conversation").single()
    }

    @Test fun outsideWriteWaitsBeforeAnyMutationAndApprovalIsOneShot() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val outside = File(root, "outside/new/file.txt")
        val manager = SandboxManager(protectedDirectories = listOf(outside.parentFile.path))
        val tools = FileSystemTools(listOf(project.path), executionPermissions = ExecutionPermissions(manager, listOf(project.path), "conversation", projectId = "project"))
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            tools.WriteFileTool().execute(FileSystemTools.WriteFileArgs(outside.path, "first"))
        }
        val request = waiting(manager)
        assertEquals(listOf(outside.path), request.paths)
        assertFalse(outside.parentFile.exists())
        manager.resolve("conversation", request.id, true)
        assertTrue(first.await().startsWith("Written"))
        assertEquals("first", outside.readText())
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { tools.WriteFileTool().execute(FileSystemTools.WriteFileArgs(outside.path, "second")) }
        }
        val again = waiting(manager)
        assertTrue(request.id != again.id)
        manager.resolve("conversation", again.id, false)
        assertTrue(second.await().isFailure)
        assertEquals("first", outside.readText())
    }

    @Test fun projectWritesNeedNoApprovalAndProtectedGitStillDoes() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val git = File(project, ".git").apply { mkdirs() }
        val manager = SandboxManager()
        val tools = FileSystemTools(listOf(project.path), executionPermissions = ExecutionPermissions(manager, listOf(project.path), "conversation", projectId = "project"))
        tools.WriteFileTool().execute(FileSystemTools.WriteFileArgs("src/new.txt", "source"))
        assertEquals("source", File(project, "src/new.txt").readText())
        assertTrue(manager.pendingApprovals("conversation").isEmpty())
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { tools.WriteFileTool().execute(FileSystemTools.WriteFileArgs(File(git, "config").path, "metadata")) }
        }
        manager.resolve("conversation", waiting(manager).id, false)
        assertTrue(operation.await().isFailure)
        assertFalse(File(git, "config").exists())
    }

    @Test fun patchAuthorizationCoversAllTargetsBeforeWritingAnyOfThem() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val outside = File(root, "outside/new.txt")
        val manager = SandboxManager(protectedDirectories = listOf(outside.parentFile.path))
        val tools = FileSystemTools(listOf(project.path), executionPermissions = ExecutionPermissions(manager, listOf(project.path), "conversation", projectId = "project"))
        val patch = "*** Begin Patch\n*** Add File: inside.txt\n+inside\n*** Add File: ${outside.path}\n+outside\n*** End Patch\n"
        val operation = async(start = CoroutineStart.UNDISPATCHED) { tools.ApplyPatchTool().execute(FileSystemTools.ApplyPatchArgs(patch)) }
        val request = waiting(manager)
        assertEquals(setOf(File(project, "inside.txt").path, outside.path), request.paths.toSet())
        assertFalse(File(project, "inside.txt").exists())
        assertFalse(outside.parentFile.exists())
        manager.resolve("conversation", request.id, true)
        assertFalse(operation.await().startsWith("Error"))
        assertEquals("inside\n", File(project, "inside.txt").readText())
        assertEquals("outside\n", outside.readText())
    }

    @Test fun officeRejectsOutsideWriteBeforeCreatingParents() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val outside = File(root, "office/document.docx")
        val manager = SandboxManager(protectedDirectories = listOf(outside.parentFile.path))
        val tools = OfficeTools(listOf(project.path), ExecutionPermissions(manager, listOf(project.path), "conversation", projectId = "project"))
        val operation = async(start = CoroutineStart.UNDISPATCHED) { tools.OfficeWriteTool().execute(OfficeTools.OfficeWriteArgs(outside.path, "# title")) }
        val request = waiting(manager)
        assertFalse(outside.parentFile.exists())
        manager.resolve("conversation", request.id, false)
        assertTrue(operation.await().startsWith("Error"))
        assertFalse(outside.parentFile.exists())
    }

    @Test fun patchCannotRetargetApprovalWhenRelativeLookupChangesWhileWaiting() = fixture { root ->
        val first = File(root, "first").apply { mkdirs() }
        val second = File(root, "second").apply { mkdirs() }
        val firstTarget = File(first, ".git/config").apply { parentFile.mkdirs(); writeText("original\n") }
        val secondTarget = File(second, ".git/config").apply { parentFile.mkdirs(); writeText("original\n") }
        val manager = SandboxManager()
        val directories = listOf(first.path, second.path)
        val tools = FileSystemTools(directories, executionPermissions = ExecutionPermissions(manager, directories, "conversation", projectId = "project"))
        val patch = "*** Begin Patch\n*** Update File: .git/config\n@@\n-original\n+changed\n*** End Patch\n"
        val operation = async(start = CoroutineStart.UNDISPATCHED) { tools.ApplyPatchTool().execute(FileSystemTools.ApplyPatchArgs(patch)) }
        val request = waiting(manager)
        assertEquals(listOf(firstTarget.path), request.paths)
        assertTrue(firstTarget.delete())
        manager.resolve("conversation", request.id, true)
        assertTrue(operation.await().startsWith("Error"))
        assertEquals("original\n", secondTarget.readText())
    }

    @Test fun fullAccessWritesOutsideWithoutApproval() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val outside = File(root, "outside.txt")
        val manager = SandboxManager()
        manager.updateSettings("project", SandboxSettings(ExecutionPermissionMode.FULL_ACCESS))
        val tools = FileSystemTools(listOf(project.path), executionPermissions = ExecutionPermissions(manager, listOf(project.path), "conversation", projectId = "project"))
        tools.WriteFileTool().execute(FileSystemTools.WriteFileArgs(outside.path, "full"))
        assertEquals("full", outside.readText())
        assertTrue(manager.pendingApprovals("conversation").isEmpty())
    }

    @Test fun commandEscalationWaitsAndRejectingItStartsNoProcess() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val target = File(root, "not-created.txt")
        val manager = SandboxManager()
        val shell = ShellTools(listOf(project.path), executionPermissions = ExecutionPermissions(manager, listOf(project.path), "conversation", projectId = "project"))
        val command = "printf unsafe > '${target.path.replace("'", "'\\''")}'"
        val operation = async(start = CoroutineStart.UNDISPATCHED) { shell.runCommand(command, sandboxMode = "off") }
        val request = waiting(manager)
        assertEquals(command, request.command)
        assertEquals("unsandboxed", request.access)
        assertFalse(target.exists())
        manager.resolve("conversation", request.id, false)
        assertTrue(operation.await().exitCode != 0)
        assertFalse(target.exists())
    }

    @Test fun canonicalSymlinkEscapeRequiresApproval() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val outside = File(root, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(project, "link").toPath(), outside.toPath())
        val manager = SandboxManager(protectedDirectories = listOf(outside.path))
        val tools = FileSystemTools(listOf(project.path), executionPermissions = ExecutionPermissions(manager, listOf(project.path), "conversation", projectId = "project"))
        val operation = async(start = CoroutineStart.UNDISPATCHED) { runCatching { tools.WriteFileTool().execute(FileSystemTools.WriteFileArgs("link/file", "no")) } }
        val request = waiting(manager)
        assertEquals(listOf(File(outside, "file").path), request.paths)
        manager.resolve("conversation", request.id, false)
        assertTrue(operation.await().isFailure)
        assertFalse(File(outside, "file").exists())
    }
}
