package xyz.mederi.ui.overview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.dto.CreateConversationInput
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.RawMessagesViewModel
import xyz.mederi.ui.Workspace
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.appstate.LocalAppState
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SubagentSettingsEntryTest {
    @Test
    fun narrowDesktopCanOpenOverviewAndUsageThenRestoreWidePreference() = runSkikoComposeUiTest(
        size = Size(1536f, 768f),
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preferences = InMemoryPreferencesStore()
        runBlocking { OverviewWindowState().apply { visible = false }.save(preferences) }
        val app = AppState(MockAiCore(), preferences, scope)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val vm = WorkspaceViewModel(app).also { owner.viewModelStore.put("workspace", it) }
        val width = mutableStateOf(380.dp)
        var overview = ""
        var usage = ""
        var viewStats = ""
        var statsTitle = ""
        var backToOverview = ""
        var backToChat = ""
        var showFloating = ""
        try {
            setContent {
                AppTheme {
                    overview = stringResource(Res.string.overview_window_title)
                    usage = stringResource(Res.string.overview_usage_row_title)
                    viewStats = stringResource(Res.string.overview_usage_stats)
                    statsTitle = stringResource(Res.string.overview_usage_stats_title)
                    backToOverview = stringResource(Res.string.overview_back_to_overview)
                    backToChat = stringResource(Res.string.overview_back_to_chat)
                    showFloating = stringResource(Res.string.overview_show)
                    CompositionLocalProvider(LocalAppState provides app, LocalViewModelStoreOwner provides owner) {
                        Box(Modifier.size(width.value, 650.dp)) {
                            Workspace(
                                viewModel = vm,
                                isCompact = false,
                                projectMenuExpanded = false,
                                onProjectMenuExpandedChange = {},
                            )
                        }
                    }
                }
            }
            onNodeWithText(overview).assertIsDisplayed().performClick()
            onNodeWithText(usage).performClick()
            onNodeWithText(viewStats).performScrollTo().performClick()
            onNodeWithText(statsTitle).assertIsDisplayed()
            onNodeWithText(backToOverview).performClick()
            onNodeWithText(viewStats).performScrollTo().assertIsDisplayed()
            onNodeWithText(backToChat).performClick()
            onNodeWithText(overview).assertIsDisplayed().performClick()

            // 900dp 超过旧断点，但浮窗仍会遮挡居中的消息列。
            runOnIdle { width.value = 900.dp }
            onNodeWithText(backToChat).assertIsDisplayed()
            onNodeWithContentDescription(showFloating).assertDoesNotExist()
            // 46dp Dock 之外，820dp 正文列需要在两侧各留 308dp。
            runOnIdle { width.value = 1481.dp }
            onNodeWithText(backToChat).assertIsDisplayed()
            runOnIdle { width.value = 1482.dp }
            onNodeWithContentDescription(showFloating).assertIsDisplayed()
            onNodeWithText(backToChat).assertDoesNotExist()
            runOnIdle { width.value = 380.dp }
            onNodeWithText(overview).assertIsDisplayed()
            onNodeWithText(backToChat).assertDoesNotExist()
        } finally {
            owner.viewModelStore.clear()
            scope.cancel()
        }
    }

    @Test
    fun settingsGearOpensForUnsentConversationWithoutCreatingOne() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var creates = 0
        val core = object : AiCore by MockAiCore() {
            override suspend fun createConversation(input: CreateConversationInput): Result<Conversation> {
                creates++
                error("Opening draft settings must not create a conversation")
            }
        }
        val app = AppState(core, InMemoryPreferencesStore(), scope)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val vm = WorkspaceViewModel(app).also { owner.viewModelStore.put("workspace", it) }
        val raw = RawMessagesViewModel(app).also { owner.viewModelStore.put("raw", it) }
        var title = ""
        try {
            setContent {
                AppTheme {
                    title = stringResource(Res.string.settings_agents_session_title)
                    CompositionLocalProvider(LocalAppState provides app, LocalViewModelStoreOwner provides owner) {
                        Box(Modifier.size(400.dp, 600.dp)) {
                            // 子 Agent 行与概览一体：齿轮入口随概览可见（概览隐藏时整窗不组合）
                            FloatingOverviewWindow(vm, raw, OverviewWindowState(), 600.dp, 400.dp)
                        }
                    }
                }
            }
            onNodeWithContentDescription(title).assertIsDisplayed().performClick()
            onNodeWithText(title).assertIsDisplayed()
            runOnIdle { assertNull(vm.conversationId); assertEquals(0, creates) }
        } finally {
            owner.viewModelStore.clear()
            scope.cancel()
        }
    }
}
