package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.sidebar_close
import mederi.app.shared.generated.resources.sidebar_new_conversation
import mederi.app.shared.generated.resources.sidebar_pin
import mederi.app.shared.generated.resources.sidebar_recent_conversations
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.core.contract.models.Project
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.theme.AppTheme
import xyz.mederi.theme.AppThemeMode
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.appstate.LocalAppState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SidebarDrawerTest {
    @Test
    fun recentGroupShowsFiveAcrossProjectsAndUsesTheActiveTheme() = runComposeUiTest {
        val projectFeed = MutableStateFlow(
            listOf(project("Alpha", 2, 6, 4), project("Beta", 7, 3, 5, 1))
        )
        val core = object : AiCore by MockAiCore() {
            override val projects = projectFeed
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(core, InMemoryPreferencesStore(), scope)
        val store = ViewModelStore()
        val vm = SidebarViewModel(app).also { store.put("sidebar", it) }
        val theme = mutableStateOf(AppThemeMode.DARK)
        var newConversationLabel = ""
        var recentLabel = ""
        var closeLabel = ""
        var pinLabel = ""
        var accentBackground = Color.Unspecified
        var closeCount = 0
        try {
            app.selectProject("Alpha")
            setContent {
                AppTheme(theme.value) {
                    newConversationLabel = stringResource(Res.string.sidebar_new_conversation)
                    recentLabel = stringResource(Res.string.sidebar_recent_conversations)
                    closeLabel = stringResource(Res.string.sidebar_close)
                    pinLabel = stringResource(Res.string.sidebar_pin)
                    accentBackground = LocalMederiColors.current.accentBg
                    CompositionLocalProvider(LocalAppState provides app) {
                        Sidebar(
                            modifier = Modifier.size(260.dp, 640.dp),
                            viewModel = vm,
                            isDrawer = true,
                            onRequestClose = { closeCount++ },
                        )
                    }
                }
            }
            waitUntilExactlyOneExists(hasText("Conversation 7"))
            onNodeWithContentDescription(closeLabel).assertDoesNotExist()
            onNodeWithContentDescription(pinLabel).assertDoesNotExist()
            val orderedLabels = listOf(recentLabel) + (7 downTo 3).map { "Conversation $it" } + "Beta"
            val positions = orderedLabels.map { onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
            assertTrue(positions.zipWithNext().all { (top, next) -> top < next })
            onNodeWithText("Conversation 2").assertDoesNotExist()

            onNodeWithText(recentLabel).performClick()
            onNodeWithText("Conversation 7").assertDoesNotExist()
            onNodeWithText("Beta").assertIsDisplayed()
            onNodeWithText(recentLabel).performClick()
            onNodeWithText("Conversation 7").assertIsDisplayed().performClick()
            assertEquals("Beta", app.selectedProjectId.value)
            assertEquals("7", app.selectedConversationId.value)
            assertEquals(1, closeCount)

            val createButton = onNodeWithText(newConversationLabel).assertIsDisplayed()
            val buttonColors = AppThemeMode.entries.map { mode ->
                runOnIdle { theme.value = mode }
                val pixels = createButton.captureToImage().toPixelMap()
                val background = pixels[pixels.width / 20, pixels.height / 2].toArgb()
                assertEquals(accentBackground.toArgb(), background)
                background
            }
            assertNotEquals(buttonColors.first(), buttonColors.last())
            createButton.performClick()
            assertNull(app.selectedConversationId.value)
            assertEquals("Beta", app.selectedProjectId.value)
            assertEquals(2, closeCount)

            runOnIdle { projectFeed.value = emptyList() }
            waitUntilDoesNotExist(hasText(recentLabel))
        } finally {
            store.clear()
            scope.cancel()
        }
    }

    @Test
    fun resizeHandleCoversTheWindowBackgroundInBothThemes() = runComposeUiTest {
        val theme = mutableStateOf(AppThemeMode.DARK)
        var sidebarBackground = Color.Unspecified
        setContent {
            AppTheme(theme.value) {
                sidebarBackground = LocalMederiColors.current.surfaceSidebar
                Box(Modifier.size(24.dp, 80.dp).background(Color.White)) {
                    SidebarResizeHandle(onDragDelta = {}, modifier = Modifier.testTag("sidebarResizeHandle"))
                }
            }
        }
        for (mode in AppThemeMode.entries) {
            runOnIdle { theme.value = mode }
            val pixels = onNodeWithTag("sidebarResizeHandle").captureToImage().toPixelMap()
            assertEquals(sidebarBackground.toArgb(), pixels[pixels.width / 2, pixels.height / 2].toArgb())
        }
    }

    private fun project(name: String, vararg times: Long) = Project(
        id = name,
        name = name,
        directory = "/$name",
        conversations = times.map { time ->
            Conversation(time.toString(), name, "Conversation $time", ConversationStatus.Idle, 0, time)
        },
    )
}
