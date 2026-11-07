package xyz.mederi.tools.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import xyz.mederi.api.exception.MederiStateException
import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.store.InMemorySettingsStore
import xyz.mederi.store.SettingsStore
import java.io.IOException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxManagerTest {
    private fun request(id: String, execution: String = "main") = ExecutionApprovalRequest(
        id, "conversation", execution, "bash", command = "printf test", cwd = "/project", access = "network",
    )

    @Test
    fun parallelRequestsWrongOwnerAndDuplicateResolutionAreIsolated() = runBlocking {
        val manager = SandboxManager()
        val first = async(start = CoroutineStart.UNDISPATCHED) { manager.requestApproval("project", request("a")) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { manager.requestApproval("project", request("b", "child")) } }
        assertEquals(listOf("a", "b"), manager.pendingApprovals("conversation").map { it.id })
        assertFailsWith<MederiStateException> { manager.resolve("wrong-conversation", "a", true) }
        assertEquals(2, manager.pendingApprovals("conversation").size)
        manager.resolve("conversation", "a", true)
        assertEquals(0L, first.await())
        assertEquals(listOf("b"), manager.pendingApprovals("conversation").map { it.id })
        assertFailsWith<MederiStateException> { manager.resolve("conversation", "a", true) }
        manager.resolve("conversation", "b", false)
        assertTrue(second.await().exceptionOrNull() is MederiStateException)
        assertTrue(manager.pendingApprovals("conversation").isEmpty())
    }

    @Test
    fun duplicateRequestIdDoesNotReplaceOriginalWaiter() = runBlocking {
        val manager = SandboxManager()
        val first = async(start = CoroutineStart.UNDISPATCHED) { manager.requestApproval("project", request("same")) }
        val duplicate = async(start = CoroutineStart.UNDISPATCHED) { runCatching { manager.requestApproval("project", request("same", "other")) } }
        assertTrue(duplicate.await().isFailure)
        assertEquals("main", manager.pendingApprovals("conversation").single().executionSessionId)
        manager.resolve("conversation", "same", true)
        assertEquals(0L, first.await())
        assertTrue(manager.pendingApprovals("conversation").isEmpty())
    }

    @Test
    fun settingsChangeRevokesAllOldRequestsInsteadOfApprovingThem() = runBlocking {
        val bus = MutableSharedFlow<MederiEvent>(extraBufferCapacity = 32)
        val events = mutableListOf<MederiEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { bus.collect { events.add(it) } }
        val manager = SandboxManager(eventBus = bus)
        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { manager.requestApproval("project", request("a")) } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { manager.requestApproval("project", request("b", "child")) } }
        manager.updateSettings("project", SandboxSettings(ExecutionPermissionMode.FULL_ACCESS))
        assertTrue(first.await().isFailure)
        assertTrue(second.await().isFailure)
        assertTrue(manager.pendingApprovals("conversation").isEmpty())
        assertFailsWith<MederiStateException> { manager.resolve("conversation", "a", true) }
        withTimeout(3000) { while (events.count { it.type == EventType.EXECUTION_APPROVAL_RESOLVED } < 2) yield() }
        assertEquals(listOf("SETTINGS_CHANGED", "SETTINGS_CHANGED"), events.filter {
            it.type == EventType.EXECUTION_APPROVAL_RESOLVED
        }.map { it.payload["status"] })
        collector.cancelAndJoin()
    }

    @Test
    fun cancellingWaiterOrOneExecutionNeverLeavesGhostOrCancelsSibling() = runBlocking {
        val manager = SandboxManager()
        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { manager.requestApproval("project", request("a")) } }
        val child = async(start = CoroutineStart.UNDISPATCHED) { manager.requestApproval("project", request("b", "child")) }
        manager.cancelExecution("main")
        assertTrue(first.await().isFailure)
        assertEquals(listOf("b"), manager.pendingApprovals("conversation").map { it.id })
        child.cancelAndJoin()
        assertTrue(manager.pendingApprovals("conversation").isEmpty())
        assertFailsWith<MederiStateException> { manager.resolve("conversation", "b", true) }
        Unit
    }

    @Test
    fun backpressuredNotificationsCannotBlockCancelResolveOrClose() = runBlocking {
        val bus = MutableSharedFlow<MederiEvent>()
        val collectorEntered = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.collect { collectorEntered.complete(Unit); releaseCollector.await() }
        }
        bus.emit(MederiEvent(EventType.STATUS, "conversation", timestamp = "test"))
        collectorEntered.await()
        val manager = SandboxManager(eventBus = bus)
        try {
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { manager.requestApproval("project", request("a")) }
            assertEquals(1, manager.pendingApprovals("conversation").size)
            waiter.cancel()
            withTimeout(3000) { waiter.join() }
            assertTrue(manager.pendingApprovals("conversation").isEmpty())
            // 订阅者仍不消费，下一次批准及宿主关闭也必须收敛。
            val next = async(start = CoroutineStart.UNDISPATCHED) { manager.requestApproval("project", request("b")) }
            withTimeout(3000) { manager.resolve("conversation", "b", true) }
            assertEquals(0L, withTimeout(3000) { next.await() })
            withTimeout(3000) { manager.close() }
            assertFailsWith<MederiStateException> { manager.requestApproval("project", request("closed")) }
            assertTrue(manager.pendingApprovals("conversation").isEmpty())
        } finally {
            withContext(NonCancellable) {
                manager.close()
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun failedPersistenceCannotEnableFullAccessOrRevokePendingRequest() = runBlocking {
        val memory = InMemorySettingsStore()
        val store = object : SettingsStore by memory {
            override suspend fun set(key: String, value: String) { throw IOException("disk full") }
        }
        val manager = SandboxManager(store)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { manager.requestApproval("project", request("a")) }
        assertFailsWith<IOException> { manager.updateSettings("project", SandboxSettings(ExecutionPermissionMode.FULL_ACCESS)) }
        assertEquals(ExecutionPermissionMode.NEEDS_APPROVAL, manager.getSettings("project").mode)
        assertEquals(0L, manager.generation("project"))
        assertEquals(1, manager.pendingApprovals("conversation").size)
        manager.resolve("conversation", "a", true)
        assertEquals(0L, waiter.await())
        assertEquals(null, memory.get(SandboxManager.settingsKey("project")))
    }

    @Test
    fun cancellationDuringPersistenceCompletesOneCoherentSettingsCommit() = runBlocking {
        val memory = InMemorySettingsStore()
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val store = object : SettingsStore by memory {
            override suspend fun set(key: String, value: String) {
                writeEntered.complete(Unit)
                releaseWrite.await()
                memory.set(key, value)
            }
        }
        val manager = SandboxManager(store)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { manager.requestApproval("project", request("a")) } }
        val update = launch { manager.updateSettings("project", SandboxSettings(ExecutionPermissionMode.FULL_ACCESS)) }
        writeEntered.await()
        update.cancel()
        releaseWrite.complete(Unit)
        withTimeout(3000) { update.join() }
        val persisted = Json.decodeFromString<SandboxSettings>(memory.get(SandboxManager.settingsKey("project"))!!)
        assertEquals(persisted, manager.getSettings("project"))
        assertEquals(ExecutionPermissionMode.FULL_ACCESS, persisted.mode)
        assertEquals(1L, manager.generation("project"))
        assertTrue(pending.await().isFailure)
        assertTrue(manager.pendingApprovals("conversation").isEmpty())
    }

    @Test
    fun settingsAreInstanceLocalButPersistAcrossManagerRecreation() = runBlocking {
        val store = InMemorySettingsStore()
        val first = SandboxManager(store)
        val independent = SandboxManager()
        first.updateSettings("project", SandboxSettings(ExecutionPermissionMode.FULL_ACCESS))
        assertEquals(ExecutionPermissionMode.FULL_ACCESS, SandboxManager(store).getSettings("project").mode)
        assertEquals(ExecutionPermissionMode.NEEDS_APPROVAL, independent.getSettings("project").mode)
        assertEquals(ExecutionPermissionMode.FULL_ACCESS, first.getSettings("project").mode)
        assertTrue(first.pendingApprovals("conversation").isEmpty())
    }

    @Test
    fun staleGenerationCannotRegisterAndFullAccessNeverRequestsApproval() = runBlocking {
        val manager = SandboxManager()
        manager.updateSettings("project", SandboxSettings(ExecutionPermissionMode.FULL_ACCESS))
        assertFailsWith<MederiStateException> { manager.requestApproval("project", request("stale"), 0L) }
        assertEquals(1L, manager.requestApproval("project", request("full"), 1L))
        assertFalse(manager.pendingApprovals("conversation").isNotEmpty())
    }

    @Test
    fun approvedButNotYetResumedWaiterCannotUseAnOlderSettingsGeneration() = runBlocking {
        val manager = SandboxManager()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { manager.requestApproval("project", request("a")) }
        }
        manager.resolve("conversation", "a", true)
        // 同协程立即改设置；已唤醒的 waiter 尚未获得调度，旧批准也必须失效。
        manager.updateSettings("project", SandboxSettings())
        assertTrue(waiter.await().exceptionOrNull() is MederiStateException)
        assertTrue(manager.pendingApprovals("conversation").isEmpty())
    }

    @Test
    fun malformedStoredConfigurationFailsClosedWithoutRegisteringApproval() = runBlocking {
        val store = InMemorySettingsStore()
        store.set(SandboxManager.settingsKey("project"), "{broken")
        val manager = SandboxManager(store)
        assertFailsWith<MederiValidationException> { manager.getSettings("project") }
        assertFailsWith<MederiValidationException> { manager.requestApproval("project", request("a")) }
        assertTrue(manager.pendingApprovals("conversation").isEmpty())
        assertEquals("{broken", store.get(SandboxManager.settingsKey("project")))
    }

    @Test
    fun separateMederiInstancesDoNotShareSettingsOrApprovalTables() = runBlocking {
        // 无 configDir，只使用注入的内存 store，不接触用户数据库。
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val scratch = File(repository, "temp/sandbox-manager-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(scratch.toPath(), "case-").toFile().canonicalFile
        val first = xyz.mederi.Mederi.create { settingsStore = InMemorySettingsStore() }
        val second = xyz.mederi.Mederi.create { settingsStore = InMemorySettingsStore() }
        try {
            val firstProject = first.projects.create(xyz.mederi.api.CreateProjectRequest("first", File(root, "first").apply { mkdirs() }.path))
            val secondProject = second.projects.create(xyz.mederi.api.CreateProjectRequest("second", File(root, "second").apply { mkdirs() }.path))
            first.sandboxManager.updateSettings(firstProject.id, SandboxSettings(ExecutionPermissionMode.FULL_ACCESS))
            assertEquals(ExecutionPermissionMode.NEEDS_APPROVAL, second.sandboxManager.getSettings(secondProject.id).mode)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                second.sandboxManager.requestApproval(secondProject.id, request("a"))
            }
            assertTrue(first.sandboxManager.pendingApprovals("conversation").isEmpty())
            assertEquals(1, second.sandboxManager.pendingApprovals("conversation").size)
            assertFailsWith<MederiStateException> { first.sandboxManager.resolve("conversation", "a", true) }
            second.sandboxManager.resolve("conversation", "a", true)
            assertEquals(0L, waiter.await())
            assertTrue(second.sandboxManager.pendingApprovals("conversation").isEmpty())
        } finally {
            first.sandboxManager.close()
            second.sandboxManager.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun notificationsKeepRequestedBeforeResolvedDespiteSubscriberBackpressure() = runBlocking {
        val bus = MutableSharedFlow<MederiEvent>()
        val events = mutableListOf<MederiEvent>()
        val collectorEntered = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.collect { event ->
                if (event.type == EventType.STATUS) {
                    collectorEntered.complete(Unit)
                    releaseCollector.await()
                } else events.add(event)
            }
        }
        bus.emit(MederiEvent(EventType.STATUS, "conversation", timestamp = "test"))
        collectorEntered.await()
        val manager = SandboxManager(eventBus = bus)
        try {
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { manager.requestApproval("project", request("a")) }
            withTimeout(3000) { manager.resolve("conversation", "a", true) }
            assertEquals(0L, withTimeout(3000) { waiter.await() })
            assertTrue(events.isEmpty())
            releaseCollector.complete(Unit)
            withTimeout(3000) { while (events.size < 2) yield() }
            assertEquals(listOf(EventType.EXECUTION_APPROVAL_REQUESTED, EventType.EXECUTION_APPROVAL_RESOLVED), events.map { it.type })
        } finally {
            withContext(NonCancellable) {
                manager.close()
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancellingConversationRemovesAllItsExecutorsButLeavesOtherConversation() = runBlocking {
        val manager = SandboxManager()
        val main = async(start = CoroutineStart.UNDISPATCHED) { runCatching { manager.requestApproval("project", request("main")) } }
        val child = async(start = CoroutineStart.UNDISPATCHED) { runCatching { manager.requestApproval("project", request("child", "child")) } }
        val other = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval("project", request("other", "other-executor").copy(conversationId = "other-conversation"))
        }
        manager.cancelConversation("conversation")
        assertTrue(main.await().isFailure)
        assertTrue(child.await().isFailure)
        assertTrue(manager.pendingApprovals("conversation").isEmpty())
        assertEquals(listOf("other"), manager.pendingApprovals("other-conversation").map { it.id })
        manager.resolve("other-conversation", "other", true)
        assertEquals(0L, other.await())
        manager.close()
    }

    @Test
    fun projectSettingsChangesOnlyRevokeThatProjectsRequestsAndVersions() = runBlocking {
        val manager = SandboxManager()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { manager.requestApproval("first", request("first").copy(conversationId = "conversation-a")) }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestApproval("second", request("second").copy(conversationId = "conversation-b"))
        }
        val saved = manager.updateSettings("first", SandboxSettings(ExecutionPermissionMode.FULL_ACCESS))
        assertTrue(first.await().isFailure)
        assertTrue(manager.pendingApprovals("conversation-a").isEmpty())
        assertEquals(listOf("second"), manager.pendingApprovals("conversation-b").map { it.id })
        assertEquals(1L, saved.version)
        assertEquals(0L, manager.getSettings("second").version)
        assertEquals(0L, manager.generation("second"))
        assertEquals(ExecutionPermissionMode.NEEDS_APPROVAL, manager.getSettings("second").mode)
        manager.resolve("conversation-b", "second", true)
        assertEquals(0L, second.await())
        manager.close()
    }

    @Test
    fun legacyGlobalFullAccessAndPathsNeverBecomeProjectDefaults() = runBlocking {
        val store = InMemorySettingsStore()
        val legacy = Json.encodeToString(SandboxSettings.serializer(),
            SandboxSettings(ExecutionPermissionMode.FULL_ACCESS, listOf("/tmp/legacy-extra"), version = 12))
        store.set("sandbox.settings", legacy)
        val manager = SandboxManager(store)
        assertEquals(SandboxSettings(), manager.getSettings("first"))
        assertEquals(SandboxSettings(), manager.getSettings("second"))
        assertEquals(legacy, store.get("sandbox.settings"))
        assertEquals(null, store.get(SandboxManager.settingsKey("first")))
        assertEquals(null, store.get(SandboxManager.settingsKey("second")))
        manager.close()
    }
}
