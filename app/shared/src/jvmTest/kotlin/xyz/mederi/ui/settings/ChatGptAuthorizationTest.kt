package xyz.mederi.ui.settings

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.mock.MockAiCore
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatGptAuthorizationTest {
    @Test
    fun pendingLoginTransitionsToConnectedAndReadsActualUsage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var reads = 0
            var usageReads = 0
            val api = object : AiCore by MockAiCore() {
                override suspend fun startChatGptLogin(providerId: String?) = Result.success(ChatGptAuthState("oauth-1", "PENDING", "https://auth.openai.com/authorize"))
                override suspend fun getChatGptAuthState(providerId: String) = Result.success(
                    if (reads++ == 0) ChatGptAuthState(providerId, "PENDING", "https://auth.openai.com/authorize")
                    else ChatGptAuthState(providerId, "CONNECTED", email = "a@example.test"))
                override suspend fun readChatGptUsage(providerId: String): Result<ChatGptUsage> {
                    usageReads++
                    return Result.success(ChatGptUsage(listOf(ChatGptUsageWindow(300, 24.0, 1800000000))))
                }
            }
            val vm = ChatGptAuthorizationViewModel(api).also { store.put("auth", it) }
            var selected: String? = null
            vm.start(null, { selected = it }, { true })
            runCurrent()
            assertEquals("oauth-1", selected)
            assertEquals("PENDING", vm.auth?.status)
            advanceTimeBy(1000); runCurrent()
            assertEquals("CONNECTED", vm.auth?.status)
            assertNull(vm.auth?.authUrl)
            assertEquals(76.0, vm.usage?.windows?.single()?.remainingPercent)
            assertEquals(1, usageReads)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun cancellingPendingLoginClearsUrlAndDoesNotRequestUsage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var cancelled = false
            val api = object : AiCore by MockAiCore() {
                override suspend fun getChatGptAuthState(providerId: String) = Result.success(
                    if (cancelled) ChatGptAuthState(providerId, "SIGNED_OUT")
                    else ChatGptAuthState(providerId, "PENDING", "https://auth.openai.com/authorize"))
                override suspend fun cancelChatGptLogin(providerId: String): Result<Unit> { cancelled = true; return Result.success(Unit) }
                override suspend fun readChatGptUsage(providerId: String): Result<ChatGptUsage> = error("cancelled login must not read usage")
            }
            val vm = ChatGptAuthorizationViewModel(api).also { store.put("auth", it) }
            vm.inspect("oauth"); runCurrent()
            assertEquals("PENDING", vm.auth?.status)
            vm.cancel(); runCurrent()
            assertTrue(cancelled)
            assertEquals("SIGNED_OUT", vm.auth?.status)
            assertNull(vm.auth?.authUrl)
            assertNull(vm.usage)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun switchingProviderDiscardsLateUsageAndUnprovidedWindowsStayUnknown() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = object : AiCore by MockAiCore() {
                override suspend fun getChatGptAuthState(providerId: String) = Result.success(ChatGptAuthState(providerId, "CONNECTED"))
                override suspend fun readChatGptUsage(providerId: String) = Result.success(ChatGptUsage(unavailableReason = CHATGPT_USAGE_API_UNAVAILABLE))
            }
            val vm = ChatGptAuthorizationViewModel(api).also { store.put("auth", it) }
            vm.inspect("oauth"); runCurrent()
            assertTrue(vm.usage!!.windows.isEmpty())
            assertEquals(CHATGPT_USAGE_API_UNAVAILABLE, vm.usage?.unavailableReason)
            vm.inspect(null); runCurrent()
            assertNull(vm.auth)
            assertNull(vm.usage)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun leavingSettingsStopsPollingWithoutCancellingHostAuthorization() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var reads = 0
            val api = object : AiCore by MockAiCore() {
                override suspend fun getChatGptAuthState(providerId: String): Result<ChatGptAuthState> {
                    reads++
                    return Result.success(ChatGptAuthState(providerId, "PENDING", "https://auth.openai.com/authorize"))
                }
                override suspend fun cancelChatGptLogin(providerId: String): Result<Unit> = error("Leaving settings must not cancel host authorization")
            }
            val vm = ChatGptAuthorizationViewModel(api).also { store.put("auth", it) }
            vm.inspect("oauth"); runCurrent()
            assertEquals(1, reads)
            vm.stopObserving()
            advanceTimeBy(5000); runCurrent()
            assertEquals(1, reads)
            assertNull(vm.auth)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun browserDispatchPrecedesNavigationAndSurvivesSelectionReset() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val url = "https://auth.openai.com/api/accounts/authorize?state=fixture"
            val events = mutableListOf<String>()
            val api = object : AiCore by MockAiCore() {
                override suspend fun startChatGptLogin(providerId: String?) = Result.success(ChatGptAuthState("new", "PENDING", url))
                override suspend fun getChatGptAuthState(providerId: String) = Result.success(ChatGptAuthState(providerId, "PENDING", url))
            }
            val vm = ChatGptAuthorizationViewModel(api).also { store.put("auth", it) }
            vm.start(null, { events += "select:$it"; vm.inspect(null) }, { events += "open:$it"; true })
            runCurrent()
            assertEquals(listOf("open:$url", "select:new"), events)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun failedBrowserLaunchKeepsPendingAddressForRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val url = "https://auth.openai.com/authorize"
            val api = object : AiCore by MockAiCore() {
                override suspend fun startChatGptLogin(providerId: String?) = Result.success(ChatGptAuthState("new", "PENDING", url))
                override suspend fun getChatGptAuthState(providerId: String) = Result.success(ChatGptAuthState(providerId, "PENDING", url))
            }
            val vm = ChatGptAuthorizationViewModel(api).also { store.put("auth", it) }
            vm.start(null, {}, { false }); runCurrent()
            assertTrue(vm.browserOpenFailed)
            assertEquals(url, vm.auth?.authUrl)
            vm.inspect("new"); runCurrent()
            assertEquals(url, vm.auth?.authUrl)
            assertTrue(vm.browserOpenFailed)
            vm.openAuthorization(url) { true }
            assertFalse(vm.browserOpenFailed)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun usageAndAuthWireModelsContainNoCredentialsAndRejectInvalidPercentages() {
        val json = Json.encodeToString(ChatGptAuthState("provider", "CONNECTED", email = "a@example.test"))
        assertFalse(json.contains("access_token"))
        assertFalse(json.contains("refresh_token"))
        assertFalse(json.contains("id_token"))
        assertNull(ChatGptUsageWindow(300, Double.NaN).remainingPercent)
        assertNull(ChatGptUsageWindow(300, -1.0).remainingPercent)
        assertNull(ChatGptUsageWindow(300, 101.0).remainingPercent)
        assertEquals(100.0, ChatGptUsageWindow(300, 0.0).remainingPercent)
        assertEquals(0.0, ChatGptUsageWindow(10080, 100.0).remainingPercent)
    }
}
