package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.appstate.LocalAppState
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.SandboxHooks
import xyz.mederi.core.contract.SandboxStatusInfo
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.UiMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SandboxSettingsDeliveryTest {
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5000) { while (!predicate()) delay(10) }
    private val local = SandboxSettingsUiState(true, status = SandboxStatusInfo("fixture", true, "sh", "status fixture"))

    @Test
    fun addTrimsRejectsBlankAndDuplicateAndDeleteTargetsTheExactPath() = runComposeUiTest {
        val paths = mutableStateOf(listOf("/shared", "/shared-more"))
        val writes = mutableListOf<List<String>>()
        var add = ""; var remove = ""; var field = ""
        setContent { AppTheme {
            add = stringResource(Res.string.settings_add)
            remove = stringResource(Res.string.settings_remove)
            field = stringResource(Res.string.settings_sandbox_whitelist_placeholder)
            SandboxSettingsContent(local, paths.value, {}, { writes += it; paths.value = it },
                Modifier.width(460.dp).height(800.dp))
        } }
        onNodeWithText(add).performScrollTo().assertIsNotEnabled()
        onNodeWithContentDescription(field).performTextInput("   ")
        onNodeWithText(add).assertIsNotEnabled()
        onNodeWithContentDescription(field).performTextReplacement("  /shared  ")
        onNodeWithText(add).assertIsNotEnabled()
        onNodeWithContentDescription(field).performTextReplacement("  /new path  ")
        onNodeWithText(add).performClick()
        runOnIdle { assertEquals(listOf("/shared", "/shared-more", "/new path"), writes.single()) }
        onAllNodesWithContentDescription(remove)[0].performScrollTo().performClick()
        runOnIdle { assertEquals(listOf("/shared-more", "/new path"), writes.last()) }
    }

    @Test
    fun unsupportedHostDoesNotPresentDevicePathsAsRemoteConfiguration() = runComposeUiTest {
        var writes = 0
        var add = ""; var remove = ""; var field = ""; var remote = ""
        setContent { AppTheme {
            add = stringResource(Res.string.settings_add)
            remove = stringResource(Res.string.settings_remove)
            field = stringResource(Res.string.settings_sandbox_whitelist_placeholder)
            remote = stringResource(Res.string.settings_sandbox_remote_hint)
            SandboxSettingsContent(SandboxSettingsUiState(false), listOf("/server-only"), {}, { writes++ },
                Modifier.width(460.dp).height(800.dp))
        } }
        onNodeWithText(remote).performScrollTo().assertIsDisplayed()
        onNodeWithText("/server-only").assertDoesNotExist()
        onNodeWithContentDescription(remove).assertDoesNotExist()
        onNodeWithContentDescription(field).assertDoesNotExist()
        onNodeWithText(add).assertDoesNotExist()
        runOnIdle { assertEquals(0, writes) }
    }

    @Test
    fun queryFailureHasAnActualRetryActionAndLoadingIsVisible() = runComposeUiTest {
        val state = mutableStateOf(SandboxSettingsUiState(true, loading = true))
        var reloads = 0; var retry = ""; var loading = ""
        setContent { AppTheme {
            retry = stringResource(Res.string.settings_sandbox_retry)
            loading = stringResource(Res.string.settings_sandbox_querying)
            SandboxSettingsContent(state.value, emptyList(), { reloads++ }, {}, Modifier.width(460.dp).height(800.dp))
        } }
        onNodeWithText(loading).assertIsDisplayed()
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
        runOnIdle { state.value = SandboxSettingsUiState(true,
            error = UiMessage(Res.string.settings_sandbox_load_failed, listOf("query fixture"))) }
        onNodeWithText("query fixture", substring = true).assertIsDisplayed()
        onNodeWithText(retry).performClick()
        runOnIdle { assertEquals(1, reloads) }
    }

    @Test
    fun longPathsAndLargeFontsKeepPathAndActionsReachable() = runComposeUiTest {
        val path = "/shared/" + "very-long-directory/".repeat(6)
        var removed: List<String>? = null
        var remove = ""; var add = ""
        setContent { AppTheme {
            remove = stringResource(Res.string.settings_remove)
            add = stringResource(Res.string.settings_add)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                SandboxSettingsContent(local, listOf(path), {}, { removed = it }, Modifier.width(280.dp).height(800.dp))
            }
        } }
        onNodeWithText(path).performScrollTo().assertExists()
        onNodeWithContentDescription(remove).performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertEquals(emptyList(), removed) }
        onNodeWithText(add).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun viewModelDoesNotInventStatusAndRetriesAfterARealHookFailure() = runBlocking {
        val calls = AtomicInteger()
        val gate = CountDownLatch(1)
        val hooks = object : SandboxHooks {
            override fun setSandboxExtraPaths(paths: List<String>) = error("Query VM must never write whitelist")
            override fun sandboxStatus(): SandboxStatusInfo? {
                val call = calls.incrementAndGet()
                if (call == 1) {
                    check(gate.await(5, TimeUnit.SECONDS))
                    error("query fixture")
                }
                return if (call == 2) null else SandboxStatusInfo("none", false, "sh", "unavailable fixture")
            }
        }
        val store = ViewModelStore()
        val vm = SandboxSettingsViewModel(hooks)
        store.put("sandbox", vm)
        try {
            vm.reload()
            assertTrue(vm.uiState.loading)
            assertNull(vm.uiState.status)
            vm.reload()
            until { calls.get() == 1 }
            gate.countDown()
            until { !vm.uiState.loading }
            assertNotNull(vm.uiState.error)
            assertNull(vm.uiState.status)
            vm.reload()
            until { !vm.uiState.loading }
            assertEquals(2, calls.get())
            assertNull(vm.uiState.status)
            assertNull(vm.uiState.error)
            vm.reload()
            until { !vm.uiState.loading }
            assertEquals(false, vm.uiState.status?.available)
            assertEquals("unavailable fixture", vm.uiState.status?.detail)
        } finally { gate.countDown(); store.clear() }
    }

    @Test
    fun routeWritesExactPathsThroughTheExistingAppStateAndPersistsThem() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preferences = InMemoryPreferencesStore()
        val writes = CopyOnWriteArrayList<List<String>>()
        val backend = object : AiCore by MockAiCore(), SandboxHooks {
            override fun sandboxStatus() = SandboxStatusInfo("fixture", true, "sh", "Fixture sandbox")
            override fun setSandboxExtraPaths(paths: List<String>) { writes += paths }
        }
        val app = AppState(backend, preferences, scope)
        var field = ""; var add = ""; var remove = ""
        try {
            setContent { AppTheme {
                field = stringResource(Res.string.settings_sandbox_whitelist_placeholder)
                add = stringResource(Res.string.settings_add); remove = stringResource(Res.string.settings_remove)
                CompositionLocalProvider(LocalAppState provides app) {
                    androidx.compose.foundation.layout.Box(Modifier.width(600.dp).height(800.dp)) { SandboxSettingsPanel() }
                }
            } }
            onNodeWithContentDescription(field).performScrollTo().performTextInput("  /fixture/with spaces  ")
            onNodeWithText(add).performScrollTo().performClick()
            runOnIdle { assertEquals(listOf("/fixture/with spaces"), app.sandboxExtraPaths.value) }
            runBlocking { app.flushPreferences() }
            runBlocking { assertEquals(listOf("/fixture/with spaces"), Json.decodeFromString<List<String>>(preferences.getString("sandbox.extraPaths")!!)) }
            onNodeWithContentDescription(remove).performScrollTo().performClick()
            runOnIdle { assertEquals(emptyList(), app.sandboxExtraPaths.value) }
            assertEquals(listOf(listOf("/fixture/with spaces"), emptyList()), writes.toList())
        } finally { scope.cancel() }
    }

    @Test
    fun noHooksRemainsUnsupportedAndDoesNotPretendToLoad() {
        val store = ViewModelStore()
        val vm = SandboxSettingsViewModel(null)
        store.put("sandbox", vm)
        try {
            vm.reload()
            assertFalse(vm.uiState.supported)
            assertFalse(vm.uiState.loading)
            assertNull(vm.uiState.status)
            assertNull(vm.uiState.error)
        } finally { store.clear() }
    }
}
