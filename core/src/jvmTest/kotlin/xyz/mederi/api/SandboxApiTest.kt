package xyz.mederi.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import xyz.mederi.api.exception.MederiInternalException
import xyz.mederi.api.exception.MederiNotFoundException
import xyz.mederi.api.exception.MederiStateException
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.api.impl.SandboxApiImpl
import xyz.mederi.store.InMemorySettingsStore
import xyz.mederi.store.SettingsStore
import xyz.mederi.tools.sandbox.ExecutionPermissionMode
import xyz.mederi.tools.sandbox.SandboxManager
import xyz.mederi.tools.sandbox.SandboxSettings
import java.io.IOException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SandboxApiTest {
    private suspend fun fixture(block: suspend (xyz.mederi.Mederi, String, String) -> Unit) {
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/sandbox-api-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        val mederi = xyz.mederi.Mederi.create { settingsStore = InMemorySettingsStore() }
        try {
            val first = mederi.projects.create(CreateProjectRequest("first", File(root, "first").apply { mkdirs() }.path))
            val second = mederi.projects.create(CreateProjectRequest("second", File(root, "second").apply { mkdirs() }.path))
            block(mederi, first.id, second.id)
        } finally {
            mederi.sandboxManager.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun readAndWriteStorageFailuresBecomeMederiExceptions() = runBlocking {
        val readError = IOException("unreadable store")
        val unreadable = object : SettingsStore by InMemorySettingsStore() {
            override suspend fun get(key: String): String? = throw readError
        }
        val readFailure = assertFailsWith<MederiInternalException> {
            SandboxApiImpl(SandboxManager(unreadable)).getSettings("project")
        }
        assertSame(readError, readFailure.cause)

        val writeError = IOException("disk full")
        val unwritable = object : SettingsStore by InMemorySettingsStore() {
            override suspend fun set(key: String, value: String) { throw writeError }
        }
        val manager = SandboxManager(unwritable)
        val writeFailure = assertFailsWith<MederiInternalException> {
            SandboxApiImpl(manager).updateSettings("project", SandboxSettings(ExecutionPermissionMode.FULL_ACCESS))
        }
        // 协程堆栈恢复可能复制 IOException；领域API必须保留类型及诊断，不要求引用同一实例。
        assertTrue(writeFailure.cause is IOException)
        assertEquals(writeError.message, writeFailure.cause?.message)
        assertEquals(ExecutionPermissionMode.NEEDS_APPROVAL, manager.getSettings("project").mode)
    }

    @Test
    fun validationAndExpiredApprovalKeepTheirDomainExceptionTypes() = runBlocking {
        val api = SandboxApiImpl(SandboxManager())
        assertFailsWith<MederiValidationException> { api.updateSettings("project", SandboxSettings(extraWritablePaths = listOf("relative"))) }
        assertFailsWith<MederiStateException> { api.resolveExecutionApproval("conversation", "expired", true) }
        assertTrue(api.pendingApprovals("conversation").isEmpty())
    }

    @Test
    fun cancellationPassesThroughWithoutBeingReportedAsInternalFailure() = runBlocking {
        val cancelled = CancellationException("operation cancelled")
        val store = object : SettingsStore by InMemorySettingsStore() {
            override suspend fun get(key: String): String? = throw cancelled
        }
        val actual = assertFailsWith<CancellationException> { SandboxApiImpl(SandboxManager(store)).getSettings("project") }
        assertSame(cancelled, actual)
    }

    @Test
    fun mederiApiAndRuntimeUseTheSameManager() = runBlocking {
        fixture { mederi, first, second ->
            val full = SandboxSettings(ExecutionPermissionMode.FULL_ACCESS)
            val saved = mederi.sandbox.updateSettings(first, full)
            assertEquals(saved, mederi.sandboxManager.getSettings(first))
            val restricted = mederi.sandboxManager.updateSettings(first, saved.copy(mode = ExecutionPermissionMode.NEEDS_APPROVAL))
            assertEquals(restricted, mederi.sandbox.getSettings(first))
            assertEquals(SandboxSettings(), mederi.sandbox.getSettings(second))
        }
    }

    @Test
    fun staleClientCannotRestoreFullAccessWhileEditingWritablePaths() = runBlocking {
        fixture { mederi, first, second ->
            val api = mederi.sandbox
            val oldFull = api.updateSettings(first, api.getSettings(first).copy(mode = ExecutionPermissionMode.FULL_ACCESS))
            val restricted = api.updateSettings(first, oldFull.copy(mode = ExecutionPermissionMode.NEEDS_APPROVAL))
            assertFailsWith<MederiStateException> { api.updateSettings(first, oldFull.copy(allowedCommands = listOf("git status"))) }
            assertEquals(restricted, api.getSettings(first))
            assertEquals(0L, api.getSettings(second).version)
        }
    }

    @Test
    fun unknownProjectCannotReadWriteOrProbePermissionConfiguration() = runBlocking {
        fixture { mederi, _, _ ->
            assertFailsWith<MederiNotFoundException> { mederi.sandbox.getSettings("unknown") }
            assertFailsWith<MederiNotFoundException> { mederi.sandbox.updateSettings("unknown", SandboxSettings()) }
            assertFailsWith<MederiNotFoundException> { mederi.sandbox.getStatus("unknown") }
            assertEquals(null, mederi.settingsStore.get(SandboxManager.settingsKey("unknown")))
        }
    }

    @Test
    fun projectRulesRoundTripWithoutLeakingToAnotherProject() = runBlocking {
        fixture { mederi, first, second ->
            val rules = SandboxSettings(ExecutionPermissionMode.FULL_ACCESS,
                deniedPaths = listOf(File("/tmp/project-private").canonicalPath),
                allowedCommands = listOf("git status"), deniedCommands = listOf("git push"))
            val saved = mederi.sandbox.updateSettings(first, rules)
            assertEquals(rules.copy(version = 1), saved)
            assertEquals(saved, mederi.sandbox.getSettings(first))
            assertEquals(SandboxSettings(), mederi.sandbox.getSettings(second))
        }
    }

    @Test
    fun concurrentOldFormsCannotBothCommitOrChangeAnotherProjectVersion() = runBlocking {
        fixture { mederi, first, second ->
            val initial = mederi.sandbox.getSettings(first)
            val updates = listOf("git status", "git diff").map { prefix ->
                async(Dispatchers.Default) { runCatching {
                    mederi.sandbox.updateSettings(first, initial.copy(allowedCommands = listOf(prefix)))
                } }
            }.map { it.await() }
            assertEquals(1, updates.count { it.isSuccess })
            assertEquals(1, updates.count { it.exceptionOrNull() is MederiStateException })
            assertEquals(1L, mederi.sandbox.getSettings(first).version)
            assertEquals(SandboxSettings(), mederi.sandbox.getSettings(second))
        }
    }
}
