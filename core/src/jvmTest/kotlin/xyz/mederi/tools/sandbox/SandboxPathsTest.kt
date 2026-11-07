package xyz.mederi.tools.sandbox

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SandboxPathsTest {
    private fun fixture(block: (File) -> Unit) {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/sandbox-path-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test
    fun sharedPolicyUsesPathContainmentAndCanonicalSymlinkTargets() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val outside = File(root, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(project, "link").toPath(), outside.toPath())
        val paths = SandboxPaths(listOf(project.path), emptyList(), emptyList())
        assertTrue(paths.allowsWrite(File(project, "new/file").toPath()))
        assertFalse(paths.allowsWrite(File(root, "project-sibling/file").toPath()))
        assertFalse(paths.allowsWrite(File(project, "link/file").toPath()))
        assertTrue(paths.allowsWrite(File(project, ".mederi/plans/p/plan.md").toPath()))
    }

    @Test
    fun protectionIsDiscoveredBeforeRootCoalescingIncludingGitPointerAndCommonDirectory() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val gitDir = File(root, "git/worktrees/one").apply { mkdirs() }
        File(gitDir, "commondir").writeText("../..")
        File(project, ".git").writeText("gitdir: ${gitDir.path}\n")
        listOf(".agents", ".codex", ".aws").forEach { File(project, it).mkdirs() }
        val paths = SandboxPaths(listOf(project.path), listOf(File(root, "git").path), listOf(root.path))
        assertEquals(listOf(root.toPath()), paths.writableRoots)
        for (name in listOf(".git", ".agents", ".codex", ".aws")) {
            assertFalse(paths.allowsWrite(File(project, "$name/config").toPath()), name)
        }
        assertFalse(paths.allowsWrite(File(gitDir, "index").toPath()))
        assertFalse(paths.allowsWrite(File(root, "git/config").toPath()))
        assertTrue(paths.allowsWrite(File(project, "source.kt").toPath()))
    }

    @Test
    fun explicitProtectedDirectoryCannotOverrideProtection() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val git = File(project, ".git").apply { mkdirs() }
        val paths = SandboxPaths(listOf(project.path), listOf(git.path), emptyList())
        assertFalse(paths.allowsWrite(File(git, "config").toPath()))
    }

    @Test
    fun runtimeConfigurationStaysProtectedWhenHomeIsWritableWithoutLockingProjectPlans() = fixture { root ->
        val home = File(root, "home").apply { mkdirs() }
        val config = File(home, ".mederi").apply { mkdirs() }
        val project = File(home, "projects/work").apply { mkdirs() }
        val paths = SandboxPaths(listOf(home.path, project.path), emptyList(), emptyList(), listOf(config.path))
        assertFalse(paths.allowsWrite(File(config, "config.db").toPath()))
        assertFalse(paths.allowsWrite(File(config, "preferences.json").toPath()))
        assertTrue(paths.allowsWrite(File(project, ".mederi/plans/p/plan.md").toPath()))
    }

    @Test
    fun explicitDenyBlocksReadAndWriteEvenInsideWritableRootsAndThroughSymlinks() = fixture { root ->
        val project = File(root, "project").apply { mkdirs() }
        val secret = File(project, "secret").apply { mkdirs() }
        Files.createSymbolicLink(File(project, "alias").toPath(), secret.toPath())
        val paths = SandboxPaths(listOf(project.path), listOf(secret.path), emptyList(),
            deniedPaths = listOf(secret.path, File(secret, "nested").path))
        assertEquals(listOf(secret.toPath()), paths.deniedCanonicalPaths)
        assertFalse(paths.allowsRead(secret.toPath()))
        assertFalse(paths.allowsWrite(File(secret, "new.txt").toPath()))
        assertFalse(paths.allowsRead(File(project, "alias/credentials").toPath()))
        assertTrue(paths.allowsRead(File(project, "secret-sibling").toPath()))
        assertTrue(paths.allowsWrite(File(project, "public.txt").toPath()))
    }

    @Test
    fun deniedPathsPreserveMissingTargetsAndRejectRelativeConfigurationWithoutCreatingFiles() = fixture { root ->
        val missing = File(root, "not-created/secrets")
        val paths = SandboxPaths(emptyList(), emptyList(), emptyList(), deniedPaths = listOf(missing.path))
        assertFalse(paths.allowsRead(File(missing, "future.txt").toPath()))
        assertFalse(missing.exists())
        assertFailsWith<IllegalArgumentException> {
            SandboxPaths(emptyList(), emptyList(), emptyList(), deniedPaths = listOf("relative"))
        }
        val denyAll = SandboxPaths(emptyList(), emptyList(), emptyList(), deniedPaths = listOf(root.toPath().root.toString()))
        assertFalse(denyAll.allowsRead(root.toPath()))
    }

    @Test
    fun fileWhitelistDoesNotGrantSiblingOrChildAccessAndValidationDoesNotCreateDirectories() = fixture { root ->
        val file = File(root, "single").apply { writeText("keep") }
        val paths = SandboxPaths(emptyList(), listOf(file.path), emptyList())
        assertTrue(paths.allowsWrite(file.toPath()))
        assertFalse(paths.allowsWrite(File(root, "sibling").toPath()))
        assertFalse(paths.allowsWrite(File(file, "child").toPath()))
        val missing = File(root, "not-created")
        SandboxPaths(listOf(missing.path), emptyList(), emptyList())
        assertFalse(missing.exists())
        for (invalid in listOf("", "relative", "/", "/proc", "/dev/null", "/sys")) {
            assertFailsWith<IllegalArgumentException>(invalid) { SandboxPaths(emptyList(), listOf(invalid), emptyList()) }
        }
    }
}
