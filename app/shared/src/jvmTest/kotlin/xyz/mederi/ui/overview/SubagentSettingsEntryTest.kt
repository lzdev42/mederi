package xyz.mederi.ui.overview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
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
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.appstate.LocalAppState
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SubagentSettingsEntryTest {
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
                            WorkspaceAgentOverviewStack(vm, raw, OverviewWindowState().apply { visible = false }, 600.dp, 400.dp)
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
