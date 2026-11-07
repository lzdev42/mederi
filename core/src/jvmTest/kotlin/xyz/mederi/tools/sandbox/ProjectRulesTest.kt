package xyz.mederi.tools.sandbox

import kotlinx.coroutines.runBlocking
import xyz.mederi.api.exception.MederiStateException
import xyz.mederi.tools.FileSystemTools
import xyz.mederi.office.OfficeTools
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ProjectRulesTest {
    @Test fun staticPrefixesRespectArgumentsAndShellQuoting() {
        val settings = SandboxSettings(allowedCommands = listOf("git status", "printf 'a b'"))
        assertTrue(CommandRules.allowsEscalation("git status --short", settings))
        assertTrue(CommandRules.allowsEscalation("printf \"a b\"", settings))
        for (command in listOf("git statusx", "git status; rm x", "git status $(pwd)", "git status >x", "X=1 git status", "git status\nrm x", "git status *")) {
            assertFalse(CommandRules.allowsEscalation(command, settings), command)
        }
        assertEquals(listOf("printf", "\\q"), CommandRules.arguments("printf \"\\q\""))
        assertEquals(listOf("printf", "", "a b"), CommandRules.arguments("printf '' a\\ b"))
    }

    @Test fun denialsWinInFullAccessAndComplexShellFailsClosed() {
        val settings = SandboxSettings(mode = ExecutionPermissionMode.FULL_ACCESS,
            allowedCommands = listOf("git"), deniedCommands = listOf("git push"))
        assertFailsWith<MederiStateException> { CommandRules.allowsEscalation("git push origin", settings) }
        assertFailsWith<MederiStateException> { CommandRules.allowsEscalation("/usr/bin/git push origin", settings) }
        assertFailsWith<MederiStateException> { CommandRules.allowsEscalation("echo ok && git status", settings) }
        assertFailsWith<MederiStateException> { CommandRules.allowsEscalation("! git push", settings) }
        assertFailsWith<MederiStateException> { CommandRules.allowsEscalation("time git push", settings) }
        assertTrue(CommandRules.allowsEscalation("git status", settings))
        for (pattern in listOf("", "git *", "git status; echo ok", "env X=1 git $(pwd)")) {
            assertFailsWith<IllegalArgumentException> { CommandRules.validate(listOf(pattern)) }
        }
    }

    @Test fun fileAndOfficeBlacklistsCannotBeApprovedOrOverriddenByFullAccess() = runBlocking {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/project-rules-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        val manager = SandboxManager()
        try {
            val secret = File(root, "secret.txt").apply { writeText("confidential") }
            val office = File(root, "secret.docx").apply { writeText("confidential") }
            val visible = File(root, "visible.txt").apply { writeText("visible") }
            val permissions = ExecutionPermissions(manager, listOf(root.path), "conversation", projectId = "project")
            val files = FileSystemTools(listOf(root.path), executionPermissions = permissions,
                agentsDiscovery = xyz.mederi.tools.AgentsSubtreeDiscovery {
                    xyz.mederi.project.AgentsFileLoader.AgentsFile(secret.path, secret.name, secret.readText())
                })
            for (mode in ExecutionPermissionMode.entries) {
                manager.updateSettings("project", SandboxSettings(mode = mode,
                    extraWritablePaths = listOf(root.path), deniedPaths = listOf(secret.path, office.path)))
                assertFailsWith<MederiStateException> { files.ReadFileTool().execute(FileSystemTools.ReadFileArgs(secret.path)) }
                assertFailsWith<MederiStateException> { files.WriteFileTool().execute(FileSystemTools.WriteFileArgs(secret.path, "overwrite")) }
                val listing = files.ListDirectoryTool().execute(FileSystemTools.ListDirectoryArgs(root.path))
                assertFalse(listing.contains(secret.name)); assertFalse(listing.contains(office.name))
                assertFalse(listing.contains("confidential"), "Automatic instruction discovery must not expose a denied file")
                assertTrue(listing.contains(visible.name))
                val officeTools = OfficeTools(listOf(root.path), permissions)
                assertTrue(officeTools.OfficeReadTool().execute(OfficeTools.OfficeReadArgs(office.path)).startsWith("Error"))
                assertTrue(manager.pendingApprovals("conversation").isEmpty())
                assertEquals("confidential", secret.readText())
            }
        } finally { manager.close(); root.deleteRecursively() }
    }

    @Test fun allowedCommandEscalationSkipsPromptButRejectsDeniedCommands() = runBlocking {
        val manager = SandboxManager()
        try {
            manager.updateSettings("project", SandboxSettings(allowedCommands = listOf("git status"), deniedCommands = listOf("git push")))
            val permissions = ExecutionPermissions(manager, emptyList(), "conversation", projectId = "project")
            permissions.authorizeCommand("git status --short", "/", "off")
            assertFailsWith<MederiStateException> { permissions.authorizeCommand("git push", "/", "default") }
            assertTrue(manager.pendingApprovals("conversation").isEmpty())
        } finally { manager.close() }
    }
}
