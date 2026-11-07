package xyz.mederi.ui.settings

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.mock.MockAiCore
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class AgentSettingsViewModelTest {
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5000) { while (!predicate()) delay(10) }
    private val model = ModelOption("model-new", "New", "provider", true, reasoningLevels = listOf("LOW", "HIGH"))
    private val item = SubagentConfigItem("RESEARCHER", "Researcher", "", "old", "Old", "HIGH", "key-1", false)

    @Test
    fun fullRoleRequestsRetainOtherFieldsReadBackAndResetAllInheritance() = runBlocking {
        val backend = MockAiCore()
        var snapshot = item
        val calls = CopyOnWriteArrayList<Pair<String, UpdateSubagentConfigInput>>()
        var reads = 0
        val api = object : AiCore by backend {
            override suspend fun listSubagentConfigs(): Result<List<SubagentConfigItem>> { reads++; return Result.success(listOf(snapshot)) }
            override suspend fun updateSubagentConfig(role: String, input: UpdateSubagentConfigInput): Result<Unit> {
                calls += role to input
                snapshot = snapshot.copy(modelId = input.modelId, reasoningLevel = input.reasoningLevel,
                    apiKeyId = input.apiKeyId, isInheriting = input == UpdateSubagentConfigInput())
                return Result.success(Unit)
            }
        }
        val store = ViewModelStore(); val vm = AgentSettingsViewModel(api); store.put("vm", vm)
        try {
            until { vm.uiState.rolesEnabled }
            vm.selectModel(item.role, model)
            until { calls.size == 1 && vm.uiState.rolesEnabled }
            assertEquals(UpdateSubagentConfigInput(model.id, "HIGH", "key-1"), calls[0].second)
            vm.selectKey(item.role, API_KEY_ROTATION_ID)
            until { calls.size == 2 && vm.uiState.rolesEnabled }
            assertEquals(UpdateSubagentConfigInput(model.id, "HIGH", API_KEY_ROTATION_ID), calls[1].second)
            vm.selectReasoning(item.role, null)
            until { calls.size == 3 && vm.uiState.rolesEnabled }
            assertEquals(UpdateSubagentConfigInput(model.id, null, API_KEY_ROTATION_ID), calls[2].second)
            vm.selectKey(item.role, null)
            until { calls.size == 4 && vm.uiState.rolesEnabled }
            assertEquals(UpdateSubagentConfigInput(model.id), calls[3].second)
            vm.selectModel(item.role, model.copy(id = "no-thinking", supportsThinking = false))
            until { calls.size == 5 && vm.uiState.rolesEnabled }
            assertNull(calls[4].second.reasoningLevel)
            vm.selectModel(item.role, null)
            until { calls.size == 6 && vm.uiState.rolesEnabled }
            assertEquals(item.role, calls[5].first)
            assertEquals(UpdateSubagentConfigInput(), calls[5].second)
            assertTrue(vm.uiState.configs.single().isInheriting)
            assertEquals(7, reads, "Every successful write must re-read Core")
        } finally { store.clear() }
    }

    @Test
    fun loadingAndFailureNeverCreateDefaultsAndRetrySectionsIndependently() = runBlocking {
        val configs = Channel<Result<List<SubagentConfigItem>>>(Channel.UNLIMITED)
        val global = Channel<Result<SubagentGlobalSettings>>(Channel.UNLIMITED)
        var configReads = 0; var globalReads = 0
        val api = object : AiCore by MockAiCore() {
            override suspend fun listSubagentConfigs(): Result<List<SubagentConfigItem>> { configReads++; return configs.receive() }
            override suspend fun getSubagentGlobalSettings(): Result<SubagentGlobalSettings> { globalReads++; return global.receive() }
        }
        val store = ViewModelStore(); val vm = AgentSettingsViewModel(api); store.put("vm", vm)
        try {
            assertNull(vm.uiState.global); assertFalse(vm.uiState.rolesEnabled)
            vm.reloadConfigs(); vm.reloadGlobal()
            until { configReads == 1 && globalReads == 1 }
            configs.send(Result.failure(IllegalStateException("roles-load")))
            global.send(Result.failure(IllegalStateException("global-load")))
            until { !vm.uiState.loadingConfigs && !vm.uiState.loadingGlobal }
            assertNull(vm.uiState.global); assertFalse(vm.uiState.configsReady)
            assertEquals(listOf("roles-load"), vm.uiState.configsError?.args)
            vm.reloadGlobal(); global.send(Result.success(SubagentGlobalSettings(7)))
            until { vm.uiState.concurrencyEnabled }
            assertEquals(7, vm.uiState.global?.maxConcurrentAgents)
            assertNotNull(vm.uiState.configsError); assertEquals(1, configReads)
            vm.reloadConfigs(); configs.send(Result.success(emptyList()))
            until { vm.uiState.rolesEnabled }
            assertTrue(vm.uiState.configs.isEmpty()); assertNull(vm.uiState.configsError)
        } finally { store.clear(); configs.close(); global.close() }
    }

    @Test
    fun pendingWritesRejectDuplicatesFailuresKeepSnapshotAndReloadFailureBlocksOverwrite() = runBlocking {
        val writes = Channel<Result<Unit>>(Channel.UNLIMITED)
        val globalWrites = Channel<Result<Unit>>(Channel.UNLIMITED)
        val roleCalls = CopyOnWriteArrayList<UpdateSubagentConfigInput>()
        val limits = CopyOnWriteArrayList<Int>()
        var failRefresh = false
        val api = object : AiCore by MockAiCore() {
            override suspend fun listSubagentConfigs(): Result<List<SubagentConfigItem>> =
                if (failRefresh) Result.failure(IllegalStateException("read-back")) else Result.success(listOf(item))
            override suspend fun getSubagentGlobalSettings() = Result.success(SubagentGlobalSettings(4))
            override suspend fun updateSubagentConfig(role: String, input: UpdateSubagentConfigInput): Result<Unit> {
                roleCalls += input; return writes.receive()
            }
            override suspend fun updateSubagentGlobalSettings(input: UpdateSubagentGlobalSettingsInput): Result<Unit> {
                limits += input.maxConcurrentAgents; return globalWrites.receive()
            }
        }
        val store = ViewModelStore(); val vm = AgentSettingsViewModel(api); store.put("vm", vm)
        try {
            until { vm.uiState.rolesEnabled && vm.uiState.concurrencyEnabled }
            vm.selectKey(item.role, "exact-key"); vm.selectReasoning(item.role, "LOW")
            vm.setConcurrency(0); vm.setConcurrency(17); vm.setConcurrency(4); vm.setConcurrency(5); vm.setConcurrency(6)
            until { roleCalls.size == 1 && limits.size == 1 }
            assertEquals(listOf(5), limits.toList())
            writes.send(Result.failure(IllegalStateException("write-rejected")))
            globalWrites.send(Result.failure(IllegalStateException("global-rejected")))
            until { vm.uiState.rolesEnabled && vm.uiState.concurrencyEnabled }
            assertEquals(item, vm.uiState.configs.single()); assertEquals(4, vm.uiState.global?.maxConcurrentAgents)
            assertEquals(listOf("write-rejected"), vm.uiState.configsError?.args)
            assertEquals(listOf("global-rejected"), vm.uiState.globalError?.args)
            vm.selectKey(item.role, "second-key"); until { roleCalls.size == 2 }
            failRefresh = true; writes.send(Result.success(Unit))
            until { vm.uiState.savingRole == null }
            assertFalse(vm.uiState.rolesEnabled); assertEquals(item, vm.uiState.configs.single())
            assertEquals(listOf("read-back"), vm.uiState.configsError?.args)
            vm.selectKey(item.role, "must-not-write"); delay(30); assertEquals(2, roleCalls.size)
        } finally { store.clear(); writes.close(); globalWrites.close() }
    }
    @Test
    fun invalidReasoningIsClearedAndLargeConcurrencyUsesAuthoritativeReadback() = runBlocking {
        var roleSnapshot = item
        var limit = 20
        var failGlobalRead = false
        val limits = CopyOnWriteArrayList<Int>()
        val api = object : AiCore by MockAiCore() {
            override suspend fun listSubagentConfigs() = Result.success(listOf(roleSnapshot))
            override suspend fun getSubagentGlobalSettings(): Result<SubagentGlobalSettings> =
                if (failGlobalRead) Result.failure(IllegalStateException("global-read-back")) else Result.success(SubagentGlobalSettings(limit))
            override suspend fun updateSubagentConfig(role: String, input: UpdateSubagentConfigInput): Result<Unit> {
                assertNull(input.reasoningLevel)
                assertEquals("key-1", input.apiKeyId)
                roleSnapshot = item.copy(modelId = input.modelId, reasoningLevel = input.reasoningLevel)
                return Result.success(Unit)
            }
            override suspend fun updateSubagentGlobalSettings(input: UpdateSubagentGlobalSettingsInput): Result<Unit> {
                limits += input.maxConcurrentAgents
                limit = 18 // Fixture backend returns its actual value, not the submitted value.
                return Result.success(Unit)
            }
        }
        val store = ViewModelStore(); val vm = AgentSettingsViewModel(api); store.put("vm", vm)
        try {
            until { vm.uiState.rolesEnabled && vm.uiState.concurrencyEnabled }
            vm.selectModel(item.role, model.copy(reasoningLevels = listOf("LOW")))
            until { vm.uiState.configs.single().modelId == model.id && vm.uiState.rolesEnabled }
            vm.setConcurrency(19)
            until { vm.uiState.global?.maxConcurrentAgents == 18 && vm.uiState.concurrencyEnabled }
            assertEquals(listOf(19), limits.toList())
            failGlobalRead = true; vm.setConcurrency(17)
            until { vm.uiState.globalError != null && !vm.uiState.savingGlobal }
            assertNull(vm.uiState.global); assertFalse(vm.uiState.concurrencyEnabled)
            assertEquals(listOf("global-read-back"), vm.uiState.globalError?.args)
        } finally { store.clear() }
    }

    @Test
    fun sessionOverridesAndRestoresStayScopedWhileOtherSessionReadIsPending() = runBlocking {
        val delayed = Channel<Result<List<SubagentConfigItem>>>(Channel.UNLIMITED)
        val writes = CopyOnWriteArrayList<Triple<String, String, UpdateSubagentConfigInput?>>()
        val concurrencyWrites = CopyOnWriteArrayList<Pair<String, UpdateSubagentGlobalSettingsInput?>>()
        var snapshot = item.copy(usesDefault = true)
        var global = SubagentGlobalSettings(3, usesDefault = true)
        val api = object : AiCore by MockAiCore() {
            override suspend fun listSubagentConfigs(): Result<List<SubagentConfigItem>> = error("Must read session scope")
            override suspend fun updateSubagentConfig(role: String, input: UpdateSubagentConfigInput): Result<Unit> = error("Must write session scope")
            override suspend fun listSessionSubagentConfigs(conversationId: String): Result<List<SubagentConfigItem>> =
                if (conversationId == "slow") delayed.receive() else Result.success(listOf(snapshot))
            override suspend fun getSessionSubagentGlobalSettings(conversationId: String) = Result.success(global)
            override suspend fun updateSessionSubagentConfig(conversationId: String, role: String, input: UpdateSubagentConfigInput?): Result<Unit> {
                writes += Triple(conversationId, role, input)
                snapshot = if (input == null) item.copy(usesDefault = true) else item.copy(
                    modelId = input.modelId, reasoningLevel = input.reasoningLevel, apiKeyId = input.apiKeyId, usesDefault = false)
                return Result.success(Unit)
            }
            override suspend fun updateSessionSubagentGlobalSettings(conversationId: String, input: UpdateSubagentGlobalSettingsInput?): Result<Unit> {
                concurrencyWrites += conversationId to input
                global = SubagentGlobalSettings(input?.maxConcurrentAgents ?: 3, usesDefault = input == null)
                return Result.success(Unit)
            }
        }
        val store = ViewModelStore()
        val slow = AgentSettingsViewModel(api, "slow").also { store.put("slow", it) }
        val current = AgentSettingsViewModel(api, "current").also { store.put("current", it) }
        try {
            until { current.uiState.rolesEnabled && current.uiState.concurrencyEnabled }
            current.selectKey(item.role, "session-key")
            until { writes.size == 1 && current.uiState.rolesEnabled }
            assertEquals(Triple("current", item.role, UpdateSubagentConfigInput("old", "HIGH", "session-key")), writes.single())
            assertFalse(current.uiState.configs.single().usesDefault)
            current.restoreRoleDefault(item.role)
            until { writes.size == 2 && current.uiState.rolesEnabled }
            assertEquals(Triple("current", item.role, null), writes.last())
            assertTrue(current.uiState.configs.single().usesDefault)
            current.setConcurrency(5)
            until { concurrencyWrites.size == 1 && current.uiState.concurrencyEnabled }
            assertEquals(5, current.uiState.global?.maxConcurrentAgents)
            current.restoreConcurrencyDefault()
            until { concurrencyWrites.size == 2 && current.uiState.concurrencyEnabled }
            assertEquals("current" to null, concurrencyWrites.last())
            assertTrue(current.uiState.global?.usesDefault == true)
            delayed.send(Result.success(listOf(item.copy(modelId = "slow-model"))))
            until { slow.uiState.rolesEnabled }
            assertEquals("slow-model", slow.uiState.configs.single().modelId)
            assertEquals("old", current.uiState.configs.single().modelId)
        } finally { store.clear(); delayed.close() }
    }

}
