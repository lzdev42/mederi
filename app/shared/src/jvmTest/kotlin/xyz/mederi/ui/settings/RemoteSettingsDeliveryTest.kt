package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import xyz.mederi.ui.components.atoms.MederiMiniSwitch
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.SandboxStatusInfo
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.theme.*
import xyz.mederi.ui.UiMessage
import xyz.mederi.ui.appstate.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class RemoteSettingsDeliveryTest {
    private fun state() = RemoteSettingsUiState(true, true, "saved-password", RemoteServerUiState.Running(8182, true),
        TunnelUiState.Idle, "token-original", "app.example.invalid", "192.168.1.9")
    private fun actions(events: MutableList<String>) = RemoteSettingsActions(
        { on, pass -> events += "toggle:$on:$pass" }, { events += "token:$it" }, { events += "domain:$it" },
        { events += "start-tunnel" }, { events += "stop-tunnel" }, { events += "retry-server:$it" }, { events += "copy:$it" })

    @Test
    fun passwordDraftAndVisibilityDoNotSaveUntilTheOriginalToggleAction() = runComposeUiTest {
        val events = mutableListOf<String>(); val data = mutableStateOf(state()); var password = ""; var remote = ""; var show = ""; var hide = ""
        setContent { AppTheme {
            password = stringResource(Res.string.settings_remote_password_label); remote = stringResource(Res.string.settings_remote_title)
            show = stringResource(Res.string.settings_secret_show); hide = stringResource(Res.string.settings_secret_hide)
            RemoteSettingsContent(data.value, actions(events), Modifier.width(650.dp).height(780.dp))
        } }
        onNodeWithContentDescription(password).performScrollTo().performTextReplacement("  new password  ")
        runOnIdle { data.value = data.value.copy(savedPassword = "upstream update"); assertTrue(events.isEmpty()) }
        onNodeWithContentDescription(password).assertTextContains("  new password  ")
        onAllNodesWithContentDescription(show)[0].performScrollTo().performClick()
        onNodeWithContentDescription(hide).performClick()
        runOnIdle { assertTrue(events.isEmpty()) }
        onNodeWithContentDescription(remote).performScrollTo().assertIsOn().performClick()
        runOnIdle { assertEquals(listOf("toggle:false:  new password  "), events) }
    }

    @Test
    fun tokenAndDomainForwardRawValuesAndRetryUsesTheOriginalTunnelAction() = runComposeUiTest {
        val events = mutableListOf<String>(); var token = ""; var domain = ""; var retry = ""
        val data = mutableStateOf(state().copy(tunnel = TunnelUiState.Failed(UiMessage(Res.string.err_generic, listOf("fixture tunnel error")))))
        setContent { AppTheme {
            token = stringResource(Res.string.settings_tunnel_token_label); domain = stringResource(Res.string.settings_tunnel_domain_label)
            retry = stringResource(Res.string.settings_retry)
            RemoteSettingsContent(data.value, actions(events).copy(
                token = { events += "token:$it"; data.value = data.value.copy(token = it) },
                domain = { events += "domain:$it"; data.value = data.value.copy(domain = it) }), Modifier.width(650.dp).height(780.dp))
        } }
        onNodeWithContentDescription(token).performScrollTo().performTextReplacement("  token raw  ")
        onNodeWithContentDescription(domain).performScrollTo().performTextReplacement("  host.raw.invalid  ")
        onNodeWithText("fixture tunnel error", substring = true).performScrollTo().assertIsDisplayed()
        onNodeWithText(retry).performScrollTo().performClick()
        runOnIdle { assertEquals(listOf("token:  token raw  ", "domain:  host.raw.invalid  ", "start-tunnel"), events) }
    }

    @Test
    fun actualPortAndBothAddressCopiesKeepExactFullValues() = runComposeUiTest {
        val events = mutableListOf<String>(); var copy = ""; var fallback = ""
        val publicUrl = "https://very-long.public.example.invalid/access/path?fixture=value"
        setContent { AppTheme {
            copy = stringResource(Res.string.copy); fallback = stringResource(Res.string.settings_remote_port_fallback, 8182)
            RemoteSettingsContent(state().copy(tunnel = TunnelUiState.Running(publicUrl)), actions(events), Modifier.width(650.dp).height(780.dp))
        } }
        onNodeWithText(fallback).assertExists()
        onNodeWithText("http://192.168.1.9:8182").assertExists()
        onAllNodesWithText(copy)[0].performScrollTo().performClick()
        onNodeWithText(publicUrl).performScrollTo().assertIsDisplayed()
        onAllNodesWithText(copy)[1].performScrollTo().performClick()
        runOnIdle { assertEquals(listOf("copy:http://192.168.1.9:8182", "copy:$publicUrl"), events) }
    }

    @Test
    fun missingAddressesAreNotInventedAndRunningTunnelStillHasAStopAction() = runComposeUiTest {
        val events = mutableListOf<String>(); var missing = ""; var unknown = ""; var copy = ""; var stop = ""
        setContent { AppTheme {
            missing = stringResource(Res.string.settings_remote_no_lan); unknown = stringResource(Res.string.settings_tunnel_url_unknown)
            copy = stringResource(Res.string.copy); stop = stringResource(Res.string.settings_tunnel_stop)
            RemoteSettingsContent(state().copy(localAddress = null, tunnel = TunnelUiState.Running(null)), actions(events), Modifier.width(650.dp).height(780.dp))
        } }
        onNodeWithText(missing).assertExists(); onNodeWithText(unknown).assertExists()
        onNodeWithText(copy).assertDoesNotExist(); onNodeWithText("127.0.0.1", substring = true).assertDoesNotExist()
        onNodeWithText(stop).performScrollTo().performClick()
        runOnIdle { assertEquals(listOf("stop-tunnel"), events) }
    }

    @Test
    fun startingTunnelHasNoRepeatStartOrEditableInputsAndNotInstalledUsesSharedError() = runComposeUiTest {
        val data = mutableStateOf(state().copy(tunnel = TunnelUiState.Starting)); val events = mutableListOf<String>()
        var token = ""; var start = ""; var notInstalled = ""; var retry = ""
        setContent { AppTheme {
            token = stringResource(Res.string.settings_tunnel_token_label); start = stringResource(Res.string.settings_tunnel_start)
            notInstalled = stringResource(Res.string.settings_tunnel_not_installed); retry = stringResource(Res.string.settings_retry)
            RemoteSettingsContent(data.value, actions(events), Modifier.width(650.dp).height(780.dp))
        } }
        onNodeWithContentDescription(token).assertDoesNotExist(); onNodeWithText(start).assertDoesNotExist()
        runOnIdle { data.value = data.value.copy(tunnel = TunnelUiState.Failed(UiMessage(Res.string.err_generic, listOf("backend")), true)) }
        onNodeWithText(notInstalled).performScrollTo().assertIsDisplayed()
        onNodeWithText(retry).performScrollTo().performClick()
        runOnIdle { assertEquals(listOf("start-tunnel"), events) }
    }

    @Test
    fun unsupportedHostExposesNoFakeActionsAndServerFailureCanReallyRetry() = runComposeUiTest {
        val data = mutableStateOf(state().copy(supported = false)); val events = mutableListOf<String>(); var unsupported = ""; var retry = ""
        setContent { AppTheme {
            unsupported = stringResource(Res.string.settings_remote_unsupported_platform); retry = stringResource(Res.string.settings_retry)
            RemoteSettingsContent(data.value, actions(events), Modifier.width(650.dp).height(780.dp))
        } }
        onNodeWithText(unsupported).assertIsDisplayed(); onAllNodes(isToggleable()).assertCountEquals(0)
        runOnIdle { data.value = state().copy(server = RemoteServerUiState.Failed(UiMessage(Res.string.err_generic, listOf("fixture server error")))) }
        onNodeWithText("fixture server error").assertIsDisplayed()
        onNodeWithText(retry).performClick()
        runOnIdle { assertEquals(listOf("retry-server:saved-password"), events) }
    }

    @Test
    fun narrowLargeFontKeepsFullAddressAndStopActionReachable() = runComposeUiTest {
        val events = mutableListOf<String>(); var stop = ""
        setContent { AppTheme(reducedMotion = true) {
            val density = LocalDensity.current
            stop = stringResource(Res.string.settings_tunnel_stop)
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                RemoteSettingsContent(state().copy(tunnel = TunnelUiState.Running("https://long.domain.example.invalid/".repeat(4))),
                    actions(events), Modifier.width(280.dp).height(760.dp))
            }
        } }
        onNodeWithText(stop).performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertEquals(listOf("stop-tunnel"), events) }
    }

    @Test
    fun routeCanBeHiddenAndReopenedWithoutStoppingOrRestartingItsHost() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var starts = 0; var stops = 0; val visible = mutableStateOf(true); var title = ""
        val app = AppState(MockAiCore(), InMemoryPreferencesStore(), scope)
        app.remoteControl = object : RemoteControlHooks {
            override suspend fun start(port: Int, password: String?): RemoteStartResult { starts++; return RemoteStartResult.Started(8182) }
            override fun stop() { stops++ }
            override val isRunning = true
            override val localAddress = "192.168.1.9"
            override fun isCloudflaredInstalled() = false
            override fun startTunnel(port: Int, token: String, domain: String?) = TunnelStartResult.NotInstalled
            override fun stopTunnel() = Unit
        }
        try {
            setContent { AppTheme {
                title = stringResource(Res.string.settings_remote_title)
                CompositionLocalProvider(LocalAppState provides app) {
                    if (visible.value) Box(Modifier.width(650.dp).height(780.dp)) { RemoteSettingsPanel() }
                }
            } }
            runOnIdle { assertEquals(0, starts); assertEquals(0, stops) }
            onNodeWithContentDescription(title).assertIsOff().performClick()
            waitUntil { app.remoteServerState.value is RemoteServerUiState.Running }
            runOnIdle { visible.value = false }; waitForIdle()
            runOnIdle { assertEquals(1, starts); assertEquals(0, stops); visible.value = true }
            onNodeWithContentDescription(title).assertIsOn()
            runOnIdle { assertEquals(1, starts); assertEquals(0, stops) }
        } finally { scope.cancel() }
    }

    @Test
    fun commonSwitchHasCheckedAndDisabledSemanticsAndKeyboardAction() = runComposeUiTest {
        val enabled = mutableStateOf(false); val checked = mutableStateOf(false); val events = mutableListOf<Boolean>()
        setContent { AppTheme {
            MederiMiniSwitch(checked.value, { events += it }, enabled = enabled.value, contentDescription = "Fixture switch")
        } }
        val control = onNodeWithContentDescription("Fixture switch")
        control.assertIsOff().assertIsNotEnabled().performTouchInput { click() }
        runOnIdle { assertTrue(events.isEmpty()); enabled.value = true }
        control.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        control.performKeyInput { pressKey(Key.Enter) }
        control.assertIsOff()
        runOnIdle { assertEquals(listOf(true), events); checked.value = true }
        control.assertIsOn()
        assertTrue(control.fetchSemanticsNode().boundsInRoot.height >= 30f)
    }

    @Test
    fun renderActualRemoteContentInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        val page = mutableStateOf(SettingsTab.REMOTE)
        setContent { AppTheme(mode.value) {
            SettingsWorkspaceContent(page.value, {}, {}, Modifier.width(960.dp).height(800.dp)
                .background(LocalMederiColors.current.surfaceWorkspace).testTag("remotePreview")) { _, _ ->
                if (page.value == SettingsTab.REMOTE) RemoteSettingsContent(state(), actions(mutableListOf()))
                else SandboxSettingsContent(SandboxSettingsUiState(projectId = "project-1",
                    settings = xyz.mederi.core.contract.models.SandboxSettings(extraWritablePaths =
                        listOf("/workspace/shared libraries", "/workspace/shared-assets/long-directory-name")),
                    status = SandboxStatusInfo("fixture sandbox", true, "sh",
                        "Fixture: writes are restricted to configured locations.")),
                    {}, {}, {}, Modifier.padding(12.dp))
            }
        } }
        for (sample in listOf(SettingsTab.REMOTE, SettingsTab.SANDBOX)) for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { mode.value = theme; page.value = sample }
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("remotePreview").captureToImage().toAwtImage(), "png", File(directory, "${sample.name.lowercase()}-${theme.name.lowercase()}.png"))
            }
        }
    }
}
