package xyz.mederi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.Project
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.components.ProjectSelectorMenu
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WorkspaceEffectRoutingTest {
    private class Effects {
        val channel = Channel<UiEffect>(Channel.UNLIMITED)
        val starts = AtomicInteger()
        val completions = AtomicInteger()
        val received = CopyOnWriteArrayList<UiEffect>()
        val flow = channel.receiveAsFlow().onStart { starts.incrementAndGet() }
            .onEach { received += it }.onCompletion { completions.incrementAndGet() }
        fun send(effect: UiEffect) { assertTrue(channel.trySend(effect).isSuccess) }
    }

    private class MenuFixture {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val projects = MutableStateFlow(listOf(
            Project("project-a", "Repeated fixture", "/fixture/a", emptyList()),
            Project("project-b", "Repeated fixture", "/fixture/b", emptyList()),
        ))
        private val core = object : AiCore by MockAiCore() {
            override val projects: StateFlow<List<Project>> = this@MenuFixture.projects
        }
        val app = AppState(core, InMemoryPreferencesStore(), scope)
        val selected = mutableListOf<String?>()
        val pickerOpenStates = mutableListOf<Boolean>()
        var openLabel = ""
        var noProjectLabel = ""
        var buttonLabel = ""

        @Composable
        fun Content(host: WorkspaceEffectState) {
            openLabel = stringResource(Res.string.input_open_project)
            noProjectLabel = stringResource(Res.string.input_no_project)
            buttonLabel = stringResource(Res.string.input_select_project)
            CompositionLocalProvider(LocalAppState provides app) {
                ProjectSelectorMenu(
                    onOpenProjectPicker = { pickerOpenStates += host.projectMenuExpanded },
                    onSelect = { selected += it; app.selectProject(it) },
                    iconOnly = true,
                    expanded = host.projectMenuExpanded,
                    onExpandedChange = host::updateProjectMenuExpanded,
                )
            }
        }
        fun close() { scope.cancel() }
    }

    @Test
    fun interleavedChannelCommandsReachTheirIndependentDestinationsWithoutLoss() = runComposeUiTest {
        val effects = Effects()
        lateinit var host: WorkspaceEffectState
        setContent { host = rememberWorkspaceEffectState(effects.flow); Box(Modifier.size(24.dp)) }
        val firstNotice = UiEffect.ShowCompactionNotice("SKIPPED", "low_tokens")
        effects.send(UiEffect.OpenSettings)
        effects.send(firstNotice)
        effects.send(UiEffect.OpenProjectMenu)
        waitForIdle()
        runOnIdle {
            assertTrue(host.settingsVisible)
            assertTrue(host.projectMenuExpanded)
            assertEquals(firstNotice, host.compactionNotice)
        }
        val lastNotice = UiEffect.ShowCompactionNotice("SKIPPED", "too_few")
        effects.send(lastNotice)
        effects.send(UiEffect.OpenSettings)
        waitForIdle()
        runOnIdle {
            assertEquals(lastNotice, host.compactionNotice)
            assertEquals(listOf(UiEffect.OpenSettings, firstNotice, UiEffect.OpenProjectMenu, lastNotice, UiEffect.OpenSettings), effects.received.toList())
            assertEquals(1, effects.starts.get())
        }
    }

    @Test
    fun localDismissalsChangeOnlyTheirOwnStateAndDoNotSendAnyEffects() = runComposeUiTest {
        val effects = Effects()
        lateinit var host: WorkspaceEffectState
        setContent { host = rememberWorkspaceEffectState(effects.flow); Box(Modifier.size(24.dp)) }
        val notice = UiEffect.ShowCompactionNotice("SKIPPED", "low_tokens")
        effects.send(UiEffect.OpenSettings); effects.send(UiEffect.OpenProjectMenu); effects.send(notice)
        waitForIdle()
        runOnIdle {
            host.dismissSettings()
            assertTrue(host.projectMenuExpanded); assertEquals(notice, host.compactionNotice)
            host.updateProjectMenuExpanded(false)
            assertEquals(notice, host.compactionNotice)
            host.dismissCompactionNotice()
        }
        waitForIdle()
        runOnIdle {
            assertFalse(host.settingsVisible); assertFalse(host.projectMenuExpanded); assertNull(host.compactionNotice)
            assertEquals(3, effects.received.size)
            assertTrue(effects.channel.tryReceive().isFailure)
        }
    }

    @Test
    fun recompositionWelcomeAndSessionBranchesKeepTheOuterCollectorAndDoNotReplay() = runComposeUiTest {
        val effects = Effects()
        val welcome = mutableStateOf(true)
        val session = mutableStateOf("session-a")
        lateinit var host: WorkspaceEffectState
        setContent {
            host = rememberWorkspaceEffectState(effects.flow)
            key(session.value) { if (welcome.value) Text("Welcome fixture") else Text("Conversation fixture") }
        }
        effects.send(UiEffect.OpenProjectMenu)
        waitForIdle()
        val original = runOnIdle { host.also { it.updateProjectMenuExpanded(false) } }
        runOnIdle { welcome.value = false; session.value = "session-b" }
        onNodeWithText("Conversation fixture").assertExists()
        runOnIdle { assertSame(original, host); assertFalse(host.projectMenuExpanded); welcome.value = true }
        onNodeWithText("Welcome fixture").assertExists()
        runOnIdle { assertEquals(1, effects.starts.get()); assertEquals(0, effects.completions.get()) }
        effects.send(UiEffect.OpenSettings)
        waitForIdle()
        runOnIdle { assertTrue(host.settingsVisible); assertFalse(host.projectMenuExpanded); assertEquals(2, effects.received.size) }
    }

    @Test
    fun lifecycleResubscriptionDoesNotReplayConsumedCommandsButReceivesNewQueuedCommands() = runComposeUiTest {
        val effects = Effects()
        val mounted = mutableStateOf(true)
        lateinit var host: WorkspaceEffectState
        setContent {
            if (mounted.value) { host = rememberWorkspaceEffectState(effects.flow); Text("Mounted fixture") }
        }
        effects.send(UiEffect.OpenSettings)
        waitForIdle()
        val original = runOnIdle { host.also { assertTrue(it.settingsVisible); mounted.value = false } }
        waitForIdle()
        runOnIdle { assertEquals(1, effects.completions.get()) }
        effects.send(UiEffect.OpenProjectMenu)
        runOnIdle { mounted.value = true }
        waitForIdle()
        runOnIdle {
            assertNotSame(original, host)
            assertFalse(host.settingsVisible)
            assertTrue(host.projectMenuExpanded)
            assertEquals(2, effects.starts.get())
            assertEquals(listOf(UiEffect.OpenSettings, UiEffect.OpenProjectMenu), effects.received.toList())
        }
    }

    @Test
    fun actualProjectMenuDismissesWithEscapeAndReopensFromANewEffect() = runComposeUiTest {
        val effects = Effects(); val menu = MenuFixture()
        lateinit var host: WorkspaceEffectState
        try {
            setContent { AppTheme { host = rememberWorkspaceEffectState(effects.flow); menu.Content(host) } }
            effects.send(UiEffect.OpenProjectMenu)
            onNodeWithText(menu.openLabel).assertIsDisplayed()
            onNodeWithText(menu.openLabel).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithText(menu.openLabel).performKeyInput { pressKey(Key.Escape) }
            onNodeWithText(menu.openLabel).assertDoesNotExist()
            runOnIdle { assertFalse(host.projectMenuExpanded) }
            effects.send(UiEffect.OpenProjectMenu)
            onNodeWithText(menu.openLabel).assertIsDisplayed()
            runOnIdle { assertTrue(host.projectMenuExpanded); assertEquals(2, effects.received.size) }
        } finally { menu.close() }
    }

    @Test
    fun actualMenuSelectsExactProjectIdDespiteDuplicateNamesAndSupportsNoProject() = runComposeUiTest {
        val effects = Effects(); val menu = MenuFixture()
        lateinit var host: WorkspaceEffectState
        try {
            setContent { AppTheme { host = rememberWorkspaceEffectState(effects.flow); menu.Content(host) } }
            effects.send(UiEffect.OpenProjectMenu)
            onAllNodesWithText("Repeated fixture")[1].performClick()
            runOnIdle { assertEquals(listOf<String?>("project-b"), menu.selected); assertFalse(host.projectMenuExpanded) }
            effects.send(UiEffect.OpenProjectMenu)
            onNodeWithText(menu.noProjectLabel).performClick()
            runOnIdle { assertEquals(listOf("project-b", null), menu.selected); assertFalse(host.projectMenuExpanded) }
        } finally { menu.close() }
    }

    @Test
    fun openDirectoryClosesTheActualMenuBeforeCallingThePickerAndButtonCanOpenItAgain() = runComposeUiTest {
        val effects = Effects(); val menu = MenuFixture()
        lateinit var host: WorkspaceEffectState
        try {
            setContent { AppTheme { host = rememberWorkspaceEffectState(effects.flow); menu.Content(host) } }
            effects.send(UiEffect.OpenProjectMenu)
            onNodeWithText(menu.openLabel).performClick()
            runOnIdle { assertEquals(listOf(false), menu.pickerOpenStates); assertFalse(host.projectMenuExpanded); assertTrue(menu.selected.isEmpty()) }
            onNodeWithContentDescription(menu.buttonLabel).performClick()
            onNodeWithText(menu.openLabel).assertIsDisplayed()
            runOnIdle { assertEquals(1, effects.received.size) }
        } finally { menu.close() }
    }

    @Test
    fun dismissedActualMenuStaysClosedWhenWelcomeOrLayoutLeafIsRecreated() = runComposeUiTest {
        val effects = Effects(); val menu = MenuFixture()
        val leaf = mutableStateOf("welcome")
        val mounted = mutableStateOf(true)
        lateinit var host: WorkspaceEffectState
        try {
            setContent { AppTheme {
                host = rememberWorkspaceEffectState(effects.flow)
                if (mounted.value) key(leaf.value) { menu.Content(host) }
            } }
            effects.send(UiEffect.OpenProjectMenu)
            onNodeWithText(menu.openLabel).assertIsDisplayed()
            onNodeWithText(menu.openLabel).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithText(menu.openLabel).performKeyInput { pressKey(Key.Escape) }
            onNodeWithText(menu.openLabel).assertDoesNotExist()
            runOnIdle { leaf.value = "conversation-desktop" }
            waitForIdle()
            onNodeWithContentDescription(menu.buttonLabel).assertExists()
            onNodeWithText(menu.openLabel).assertDoesNotExist()
            runOnIdle { mounted.value = false }
            waitForIdle()
            runOnIdle { leaf.value = "conversation-compact"; mounted.value = true }
            waitForIdle()
            onNodeWithContentDescription(menu.buttonLabel).assertExists()
            onNodeWithText(menu.openLabel).assertDoesNotExist()
            runOnIdle { assertFalse(host.projectMenuExpanded); assertEquals(1, effects.starts.get()); assertEquals(1, effects.received.size) }
            effects.send(UiEffect.OpenProjectMenu)
            onNodeWithText(menu.openLabel).assertIsDisplayed()
        } finally { menu.close() }
    }
}
