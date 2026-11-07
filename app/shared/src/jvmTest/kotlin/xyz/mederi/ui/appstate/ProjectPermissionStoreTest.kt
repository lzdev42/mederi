package xyz.mederi.ui.appstate

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.SandboxStatusInfo
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.settings.SandboxSettingsViewModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ProjectPermissionStoreTest {
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5000) { while (!predicate()) delay(10) }
    private val status = SandboxStatusInfo("fixture", true, "sh", "fixture")

    @Test
    fun sharedProjectProjectionPreservesRulesAndDoesNotOptimisticallyGrantAccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var stored = SandboxSettings(extraWritablePaths = listOf("/extra"), version = 4,
            deniedPaths = listOf("/private"), allowedCommands = listOf("git status"), deniedCommands = listOf("git push"))
        val calls = CopyOnWriteArrayList<Pair<String, SandboxSettings>>()
        val result = CompletableDeferred<Result<SandboxSettings>>()
        val reads = AtomicInteger()
        val api = object : AiCore by MockAiCore() {
            override fun events() = emptyFlow<CoreEvent>()
            override suspend fun getSandboxSettings(projectId: String): Result<SandboxSettings> { reads.incrementAndGet(); return Result.success(stored) }
            override suspend fun getSandboxStatus(projectId: String) = Result.success(status)
            override suspend fun updateSandboxSettings(projectId: String, settings: SandboxSettings): Result<SandboxSettings> {
                calls += projectId to settings
                return result.await()
            }
        }
        val store = ProjectPermissionStore(api, scope)
        val composer = store.state("a"); val settingsPage = store.state("a")
        try {
            store.ensureLoaded("a"); store.ensureLoaded("a")
            until { composer.value.canEdit }
            assertEquals(1, reads.get())
            store.selectMode("a", ExecutionPermissionMode.FULL_ACCESS)
            store.selectMode("a", ExecutionPermissionMode.FULL_ACCESS)
            until { calls.size == 1 }
            assertTrue(settingsPage.value.saving)
            assertEquals(ExecutionPermissionMode.NEEDS_APPROVAL, composer.value.settings?.mode)
            val proposed = calls.single().second
            assertEquals(stored.copy(mode = ExecutionPermissionMode.FULL_ACCESS), proposed)
            result.complete(Result.failure(IllegalStateException("write-denied")))
            until { !composer.value.saving }
            assertEquals(stored, settingsPage.value.settings)
            assertEquals(listOf("write-denied"), composer.value.error?.args)
        } finally { result.cancel(); scope.cancel() }
    }

    @Test
    fun failedReloadCannotUseOldSnapshotToGrantFullAccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var reads = 0; var writes = 0
        val api = object : AiCore by MockAiCore() {
            override fun events() = emptyFlow<CoreEvent>()
            override suspend fun getSandboxSettings(projectId: String): Result<SandboxSettings> =
                if (++reads == 2) Result.failure(IllegalStateException("read-failed")) else Result.success(SandboxSettings())
            override suspend fun getSandboxStatus(projectId: String) = Result.success(status)
            override suspend fun updateSandboxSettings(projectId: String, settings: SandboxSettings): Result<SandboxSettings> { writes++; return Result.success(settings) }
        }
        val store = ProjectPermissionStore(api, scope); val state = store.state("a")
        try {
            store.ensureLoaded("a"); until { state.value.canEdit }
            store.reload("a"); until { !state.value.loading }
            assertFalse(state.value.canEdit)
            store.selectMode("a", ExecutionPermissionMode.FULL_ACCESS)
            delay(30); assertEquals(0, writes)
            store.reload("a"); until { state.value.canEdit }
        } finally { scope.cancel() }
    }

    @Test
    fun projectEventsRefreshOnlyTheirOwnEntry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 8)
        val reads = CopyOnWriteArrayList<String>()
        var settingA = SandboxSettings()
        val api = object : AiCore by MockAiCore() {
            override fun events() = events
            override suspend fun getSandboxSettings(projectId: String): Result<SandboxSettings> {
                reads += projectId
                return Result.success(if (projectId == "a") settingA else SandboxSettings(extraWritablePaths = listOf("/b")))
            }
            override suspend fun getSandboxStatus(projectId: String) = Result.success(status)
        }
        val store = ProjectPermissionStore(api, scope); val a = store.state("a"); val b = store.state("b")
        try {
            store.ensureLoaded("a"); store.ensureLoaded("b")
            until { a.value.canEdit && b.value.canEdit && events.subscriptionCount.value > 0 }
            settingA = SandboxSettings(ExecutionPermissionMode.FULL_ACCESS)
            events.emit(CoreEvent(CoreEventType.SANDBOX_SETTINGS_CHANGED, "", payload = mapOf("projectId" to "a")))
            until { a.value.settings?.mode == ExecutionPermissionMode.FULL_ACCESS && a.value.canEdit }
            assertEquals(1, reads.count { it == "b" })
            assertEquals(ExecutionPermissionMode.NEEDS_APPROVAL, b.value.settings?.mode)
            assertEquals(listOf("/b"), b.value.settings?.extraWritablePaths)
        } finally { scope.cancel() }
    }

    @Test
    fun switchingSettingsTargetDoesNotRedirectAnInFlightProjectWrite() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val result = CompletableDeferred<Result<SandboxSettings>>()
        val writes = CopyOnWriteArrayList<String>()
        val api = object : AiCore by MockAiCore() {
            override fun events() = emptyFlow<CoreEvent>()
            override suspend fun getSandboxSettings(projectId: String) = Result.success(SandboxSettings(extraWritablePaths = listOf("/$projectId")))
            override suspend fun getSandboxStatus(projectId: String) = Result.success(status)
            override suspend fun updateSandboxSettings(projectId: String, settings: SandboxSettings): Result<SandboxSettings> { writes += projectId; return result.await() }
        }
        val store = ProjectPermissionStore(api, scope); val vm = SandboxSettingsViewModel(store)
        val lifecycle = ViewModelStore(); lifecycle.put("vm", vm)
        try {
            vm.bindProject("a"); until { vm.uiState.canEdit }
            vm.selectMode(ExecutionPermissionMode.FULL_ACCESS); until { writes.size == 1 }
            vm.bindProject("b"); until { vm.uiState.projectId == "b" && vm.uiState.canEdit }
            result.complete(Result.failure(IllegalStateException("a-write-failed")))
            until { store.state("a").value.error != null }
            assertEquals(listOf("a"), writes.toList())
            assertNull(vm.uiState.error)
            assertEquals(listOf("/b"), vm.uiState.settings?.extraWritablePaths)
        } finally { result.cancel(); lifecycle.clear(); scope.cancel() }
    }

    @Test
    fun cancelledOlderReadCannotOverwriteNewerProjectSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = CompletableDeferred<Unit>(); val reads = AtomicInteger()
        val api = object : AiCore by MockAiCore() {
            override fun events() = emptyFlow<CoreEvent>()
            override suspend fun getSandboxSettings(projectId: String): Result<SandboxSettings> =
                if (reads.incrementAndGet() == 1) withContext(NonCancellable) { first.await(); Result.success(SandboxSettings()) }
                else Result.success(SandboxSettings(ExecutionPermissionMode.FULL_ACCESS))
            override suspend fun getSandboxStatus(projectId: String) = Result.success(status)
        }
        val store = ProjectPermissionStore(api, scope); val state = store.state("a")
        try {
            store.reload("a"); until { reads.get() == 1 }
            store.reload("a"); until { state.value.canEdit }
            first.complete(Unit); delay(30)
            assertEquals(ExecutionPermissionMode.FULL_ACCESS, state.value.settings?.mode)
        } finally { first.complete(Unit); scope.cancel() }
    }
}
