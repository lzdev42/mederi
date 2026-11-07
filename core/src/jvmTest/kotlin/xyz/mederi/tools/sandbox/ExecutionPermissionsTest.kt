package xyz.mederi.tools.sandbox

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import xyz.mederi.api.exception.MederiStateException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionPermissionsTest {
    private suspend fun fixture(block: suspend (File) -> Unit) {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/execution-permissions-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test
    fun workspaceIsAutomaticButOutsideApprovalIsExactAndSingleUse() = runBlocking {
        fixture { root ->
            val project = File(root, "project").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            // 测试仓库可能恰好位于系统 /tmp；保护 fixture 外部子树，确保该目标始终需要批准。
            val manager = SandboxManager(protectedDirectories = listOf(outside.path))
            val permissions = ExecutionPermissions(manager, listOf(project.path), "conversation", "child", projectId = "project")
            val inside = File(project, "inside.txt").toPath()
            val localGrant = permissions.authorizeWrite("write_file", listOf(inside))
            assertEquals(listOf(inside), localGrant.paths)
            assertTrue(manager.pendingApprovals("conversation").isEmpty())

            val target = File(outside, "one.txt").toPath()
            val other = File(outside, "two.txt").toPath()
            val first = async(start = CoroutineStart.UNDISPATCHED) { permissions.authorizeWrite("write_file", listOf(target)) }
            val request = manager.pendingApprovals("conversation").single()
            assertEquals("child", request.executionSessionId)
            assertEquals(listOf(target.toString()), request.paths)
            assertFalse(Files.exists(target))
            manager.resolve("conversation", request.id, true)
            val grant = first.await()
            assertEquals(listOf(target), grant.paths)
            assertTrue(manager.getSettings("project").extraWritablePaths.isEmpty())

            val again = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { permissions.authorizeWrite("write_file", listOf(target)) }
            }
            val second = manager.pendingApprovals("conversation").single()
            assertTrue(second.id != request.id)
            manager.resolve("conversation", second.id, false)
            assertTrue(again.await().isFailure)

            val sibling = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { permissions.authorizeWrite("write_file", listOf(other)) }
            }
            val siblingRequest = manager.pendingApprovals("conversation").single()
            assertEquals(listOf(other.toString()), siblingRequest.paths)
            manager.resolve("conversation", siblingRequest.id, false)
            assertTrue(sibling.await().isFailure)
            assertTrue(manager.pendingApprovals("conversation").isEmpty())
        }
    }

    @Test
    fun networkAndUnsandboxedRequestsCarryExactCommandAndDoNotPersistPermission() = runBlocking {
        fixture { root ->
            val manager = SandboxManager()
            val permissions = ExecutionPermissions(manager, listOf(root.path), "conversation", projectId = "project")
            permissions.authorizeCommand("printf safe", root.path, "default")
            assertTrue(manager.pendingApprovals("conversation").isEmpty())
            for ((mode, access) in listOf("network" to "network", "off" to "unsandboxed")) {
                val command = "printf '$mode command'"
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    permissions.authorizeCommand(command, root.path, mode, tool = "verify_subtask")
                }
                val request = manager.pendingApprovals("conversation").single()
                assertEquals(command, request.command)
                assertEquals(root.path, request.cwd)
                assertEquals(access, request.access)
                assertEquals("verify_subtask", request.toolName)
                manager.resolve("conversation", request.id, true)
                permissions.checkGrant(pending.await())
                assertEquals(SandboxSettings(), manager.getSettings("project"))
            }
            assertFailsWith<IllegalArgumentException> { permissions.authorizeCommand("true", root.path, "invalid") }
            assertTrue(manager.pendingApprovals("conversation").isEmpty())
        }
    }

    @Test
    fun fullAccessNeverAsksForOutsideWritesOrCommandEscalation() = runBlocking {
        fixture { root ->
            val project = File(root, "project").apply { mkdirs() }
            val manager = SandboxManager(protectedDirectories = listOf(File(root, "outside").path))
            manager.updateSettings("project", SandboxSettings(ExecutionPermissionMode.FULL_ACCESS))
            val permissions = ExecutionPermissions(manager, listOf(project.path), "conversation", projectId = "project")
            val target = File(root, "outside/file.txt").toPath()
            assertEquals(listOf(target), permissions.authorizeWrite("write_file", listOf(target)).paths)
            for (mode in listOf("default", "network", "off")) {
                assertEquals(ExecutionPermissionMode.FULL_ACCESS,
                    permissions.authorizeCommand("printf test", project.path, mode).settings.mode)
            }
            assertTrue(manager.pendingApprovals("conversation").isEmpty())
        }
    }

    @Test
    fun settingsChangeInvalidatesPreviouslyAcceptedGrants() = runBlocking {
        fixture { root ->
            val manager = SandboxManager()
            val permissions = ExecutionPermissions(manager, listOf(root.path), "conversation", projectId = "project")
            val write = permissions.authorizeWrite("write_file", listOf(File(root, "file.txt").toPath()))
            val command = permissions.authorizeCommand("printf test", root.path, "default")
            manager.updateSettings("project", SandboxSettings(ExecutionPermissionMode.FULL_ACCESS))
            assertFailsWith<MederiStateException> { permissions.checkGrant(write) }
            assertFailsWith<MederiStateException> { permissions.checkGrant(command) }
            assertTrue(manager.pendingApprovals("conversation").isEmpty())
        }
    }

    @Test
    fun parentChangedToSymlinkAfterAuthorizationIsRejected() = runBlocking {
        fixture { root ->
            val project = File(root, "project").apply { mkdirs() }
            val folder = File(project, "folder").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val permissions = ExecutionPermissions(SandboxManager(), listOf(project.path), "conversation", projectId = "project")
            val target = File(folder, "file.txt").toPath()
            val grant = permissions.authorizeWrite("write_file", listOf(target))
            assertTrue(folder.delete())
            Files.createSymbolicLink(folder.toPath(), outside.toPath())
            assertFailsWith<IllegalArgumentException> { permissions.checkGrant(grant) }
            assertFalse(File(outside, "file.txt").exists())
        }
    }

    @Test
    fun changingAnotherProjectCannotInvalidateThisProjectsGrantOrPendingApproval() = runBlocking {
        fixture { root ->
            val first = File(root, "first").apply { mkdirs() }
            val second = File(root, "second").apply { mkdirs() }
            val manager = SandboxManager()
            val firstPermissions = ExecutionPermissions(manager, listOf(first.path), "conversation-a", projectId = "first")
            val secondPermissions = ExecutionPermissions(manager, listOf(second.path), "conversation-b", projectId = "second")
            val firstGrant = firstPermissions.authorizeWrite("write_file", listOf(File(first, "a.txt").toPath()))
            val secondGrant = secondPermissions.authorizeWrite("write_file", listOf(File(second, "b.txt").toPath()))
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                secondPermissions.authorizeCommand("printf second", second.path, "network")
            }
            val secondRequest = manager.pendingApprovals("conversation-b").single()
            manager.updateSettings("first", SandboxSettings(ExecutionPermissionMode.FULL_ACCESS))
            assertFailsWith<MederiStateException> { firstPermissions.checkGrant(firstGrant) }
            secondPermissions.checkGrant(secondGrant)
            assertEquals(listOf(secondRequest.id), manager.pendingApprovals("conversation-b").map { it.id })
            manager.resolve("conversation-b", secondRequest.id, true)
            secondPermissions.checkGrant(pending.await())
            assertEquals(SandboxSettings(), manager.getSettings("second"))
            manager.close()
        }
    }

    @Test
    fun projectBlacklistCannotBeBypassedByFullAccessOrCommandAllowlist() = runBlocking {
        fixture { root ->
            val privateDirectory = File(root, "private").apply { mkdirs() }
            val target = File(privateDirectory, "secret.txt").apply { writeText("secret") }.toPath()
            val manager = SandboxManager()
            manager.updateSettings("project", SandboxSettings(ExecutionPermissionMode.FULL_ACCESS,
                deniedPaths = listOf(privateDirectory.path), allowedCommands = listOf("git"), deniedCommands = listOf("git push")))
            val permissions = ExecutionPermissions(manager, listOf(root.path), "conversation", projectId = "project")
            assertFailsWith<MederiStateException> { permissions.checkRead(target) }
            assertFailsWith<MederiStateException> { permissions.authorizeWrite("write_file", listOf(target)) }
            assertFailsWith<MederiStateException> { permissions.authorizeCommand("git push origin main", root.path, "off") }
            assertTrue(manager.pendingApprovals("conversation").isEmpty())
            assertEquals("secret", target.toFile().readText())
            manager.close()
        }
    }
}
