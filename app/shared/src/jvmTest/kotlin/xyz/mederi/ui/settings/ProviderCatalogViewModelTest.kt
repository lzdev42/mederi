package xyz.mederi.ui.settings

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.appstate.AppState
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ProviderCatalogViewModelTest {
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5000) {
        while (!predicate()) delay(10)
    }
    private fun config(id: String, name: String, builtin: Boolean = true) = ProviderConfig(id, name,
        if (builtin) ProviderType.Builtin else ProviderType.Custom, "http://127.0.0.1/$id", false,
        emptyList(), supportsApiKey = true, supportsBaseUrl = !builtin)

    @Test
    fun queriesAndPresetIdentityUseIndependentRealSources() {
        val builtin = ProviderItemUiState("b", "Alpha", true, false, baseUrl = "https://alpha.example")
        val custom = ProviderItemUiState("c", "Alpha", false, false, baseUrl = "http://localhost:8080")
        val state = ProviderSettingsUiState(listOf(builtin, custom), "b", providerQuery = " LOCALHOST ", presetQuery = "beta",
            searchQuery = "model-query")
        assertEquals(listOf("c"), state.filteredProviders.map { it.id })
        val rows = providerPresetItems(listOf("beta", "Alpha", "Alpha"), listOf(builtin, custom), " ALP ")
        assertEquals(listOf("Alpha"), rows.map { it.name })
        assertEquals(listOf("b"), rows.single().configuredIds)
        assertEquals(listOf("Alpha", "beta"), providerPresetItems(listOf("beta", "Alpha"), listOf(custom), "").map { it.name })
        assertTrue(providerPresetItems(listOf("Alpha"), listOf(custom), "").single().configuredIds.isEmpty())
        assertEquals("model-query", state.searchQuery)
    }

    @Test
    fun navigationPreservesQueriesAndSelectsExactConfiguredIdsWithoutGuessing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = ViewModelStore()
        try {
            val backend = MockAiCore().also { it.initialize() }
            val configs = MutableStateFlow(listOf(config("one", "Alpha"), config("custom", "Alpha", false)))
            val api = object : AiCore by backend {
                override val providers = configs
                override val builtinPresets = listOf("Alpha", "Beta")
            }
            val vm = ProviderSettingsViewModel(AppState(api, InMemoryPreferencesStore(), scope))
            store.put("fixture", vm)
            until { vm.uiState.providers.size == 2 }
            vm.updateSearchQuery("keep-model-query")
            vm.updateProviderQuery("127.0.0.1")
            vm.openProviderCatalog(); vm.updatePresetQuery("bet")
            vm.selectProviderPreset("Beta")
            assertEquals(ProviderPage.Preset("Beta"), vm.uiState.page)
            assertEquals("keep-model-query", vm.uiState.searchQuery)
            vm.openProviderCatalog()
            assertEquals("bet", vm.uiState.presetQuery)
            vm.startCreateCustomProvider(); assertTrue(vm.uiState.isCreatingCustom)
            vm.returnToProviderManagement()
            assertEquals("127.0.0.1", vm.uiState.providerQuery)
            vm.openProviderCatalog(); vm.selectProviderPreset("Alpha")
            assertEquals("one", vm.uiState.selectedProviderId)
            assertEquals(ProviderPage.Detail, vm.uiState.page)
            configs.value = configs.value + config("two", "Alpha")
            until { vm.uiState.providers.size == 3 }
            vm.openProviderCatalog(); vm.selectProviderPreset("Alpha")
            assertEquals(ProviderPage.Manage, vm.uiState.page)
            assertNull(vm.uiState.selectedProviderId)
            assertEquals(setOf("one", "two"), vm.uiState.filteredProviders.map { it.id }.toSet())
            vm.updateProviderQuery("")
            assertEquals(3, vm.uiState.filteredProviders.size)
        } finally { store.clear(); scope.cancel() }
    }

    @Test
    fun addRejectsDuplicatesKeepsFailureAndOnlySuccessReturnsToSelectedDetail() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = ViewModelStore()
        val results = Channel<Result<ProviderConfig>>(Channel.UNLIMITED)
        try {
            val backend = MockAiCore().also { it.initialize() }
            val configs = MutableStateFlow<List<ProviderConfig>>(emptyList())
            val calls = CopyOnWriteArrayList<Pair<String, String>>()
            val api = object : AiCore by backend {
                override val providers = configs
                override val builtinPresets = listOf("Alpha")
                override suspend fun addBuiltinProvider(name: String, apiKey: String): Result<ProviderConfig> {
                    calls += name to apiKey
                    return results.receive()
                }
            }
            val vm = ProviderSettingsViewModel(AppState(api, InMemoryPreferencesStore(), scope))
            store.put("fixture", vm)
            vm.openProviderCatalog(); vm.selectProviderPreset("Alpha")
            vm.addBuiltinProvider("Alpha", " fixture-key ")
            vm.addBuiltinProvider("Alpha", "second-request")
            vm.openProviderCatalog(); vm.returnToProviderManagement(); vm.startCreateCustomProvider()
            assertEquals(ProviderPage.Preset("Alpha"), vm.uiState.page)
            until { calls.size == 1 }
            assertEquals(listOf("Alpha" to "fixture-key"), calls.toList())
            results.send(Result.failure(IllegalStateException("fixture-rejected")))
            until { !vm.uiState.isSavingCredentials }
            assertEquals(ProviderPage.Preset("Alpha"), vm.uiState.page)
            assertNotNull(vm.uiState.errorMessage)
            vm.clearSuccessMessage()
            assertNotNull(vm.uiState.errorMessage, "Success timeout cannot erase an unrelated error")
            vm.addBuiltinProvider("Alpha", " fixture-key ")
            until { calls.size == 2 }
            configs.value = listOf(config("added-id", "Alpha"))
            results.send(Result.success(configs.value.single()))
            until { !vm.uiState.isSavingCredentials && vm.uiState.page == ProviderPage.Detail }
            assertEquals("added-id", vm.uiState.selectedProviderId)
            assertNull(vm.uiState.errorMessage)
        } finally { store.clear(); scope.cancel(); results.close() }
    }
}
