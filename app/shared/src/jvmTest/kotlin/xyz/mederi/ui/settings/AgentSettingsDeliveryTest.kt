package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.*
import xyz.mederi.theme.*
import xyz.mederi.ui.UiMessage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AgentSettingsDeliveryTest {
    private val fast = ModelOption("fast-id", "Fast model", "provider", true, reasoningLevels = listOf("LOW", "HIGH"))
    private val other = fast.copy(id = "other-id", name = "Other model")
    private val keys = listOf(ApiKeyOption("key-1", "Same alias", "sk-••11", true), ApiKeyOption("key-2", "Same alias", "sk-••22", false))
    private fun provider(keys: List<ApiKeyOption> = this.keys) = ProviderConfig("provider", "Example", ProviderType.Custom,
        "https://example.invalid/v1", true, listOf(fast, other), supportsApiKey = true, supportsBaseUrl = true, apiKeys = keys)
    private fun role(inherit: Boolean = false) = SubagentConfigItem("RESEARCHER", "Researcher", "Investigates before a change",
        if (inherit) null else fast.id, if (inherit) null else fast.name, if (inherit) null else "HIGH",
        if (inherit) null else API_KEY_ROTATION_ID, inherit)
    private fun actions(events: MutableList<String>) = AgentSettingsActions(
        { events += "roles-retry" }, { events += "global-retry" }, { events += "limit:$it" },
        { role, model -> events += "model:$role:${model?.id}" },
        { role, level -> events += "reasoning:$role:$level" },
        { role, key -> events += "key:$role:$key" })

    @Test
    fun modelMenuAndRestoreUseExactRoleAndModelIdentity() = runComposeUiTest {
        val events = mutableListOf<String>(); val data = mutableStateOf(role(true)); var inherit = ""; var reset = ""
        setContent { AppTheme {
            inherit = stringResource(Res.string.settings_agents_inherit); reset = stringResource(Res.string.settings_agents_reset)
            Box(Modifier.width(600.dp)) { AgentRoleSettings(data.value, listOf(fast, other), listOf(provider()), fast,
                true, false, actions(events)) }
        } }
        onNodeWithText(inherit).performClick()
        onNodeWithText(other.name).performClick()
        runOnIdle { assertEquals(listOf("model:RESEARCHER:other-id"), events); data.value = role() }
        onNodeWithText(reset).performClick()
        runOnIdle { assertEquals("model:RESEARCHER:null", events.last()) }
    }

    @Test
    fun rotationInheritanceAndDuplicateAliasKeysKeepDistinctIds() = runComposeUiTest {
        val events = mutableListOf<String>(); var rotate = ""; var inherit = ""
        setContent { AppTheme {
            rotate = stringResource(Res.string.settings_agents_apikey_rotate)
            inherit = stringResource(Res.string.settings_agents_apikey_inherit)
            Box(Modifier.width(600.dp)) { AgentRoleSettings(role(), listOf(fast), listOf(provider()), fast, true, false, actions(events)) }
        } }
        onNodeWithText(rotate).performClick()
        onAllNodesWithText(rotate).onLast().assertIsSelected().performClick()
        onNodeWithText(rotate).performClick()
        onNodeWithText("sk-••22").performClick()
        onNodeWithText(rotate).performClick()
        onNodeWithText(inherit).performClick()
        runOnIdle { assertEquals(listOf("key:RESEARCHER:$API_KEY_ROTATION_ID", "key:RESEARCHER:key-2", "key:RESEARCHER:null"), events) }
    }

    @Test
    fun reasoningUsesAvailableLevelsAndUnknownKeyIsNotShownAsInheritance() = runComposeUiTest {
        val events = mutableListOf<String>(); var high = ""; var low = ""; var inherit = ""
        val data = mutableStateOf(role().copy(apiKeyId = "removed-key"))
        setContent { AppTheme {
            high = xyz.mederi.ui.components.formatReasoningLevelLabel("HIGH")
            low = xyz.mederi.ui.components.formatReasoningLevelLabel("LOW")
            inherit = stringResource(Res.string.settings_agents_inherit_reasoning)
            Box(Modifier.width(600.dp)) { AgentRoleSettings(data.value, listOf(fast), listOf(provider(keys.take(1))), fast,
                true, false, actions(events)) }
        } }
        onNodeWithText("removed-key").assertIsDisplayed().performClick()
        onNodeWithText("sk-••11", substring = true).performClick()
        onNodeWithText(high).performClick(); onNodeWithText(low).performClick()
        onNodeWithText(high).performClick(); onNodeWithText(inherit).performClick()
        runOnIdle { assertEquals(listOf("key:RESEARCHER:key-1", "reasoning:RESEARCHER:LOW", "reasoning:RESEARCHER:null"), events) }
    }

    @Test
    fun loadingHasNoFakeNumberAndErrorsExposeIndependentReloads() = runComposeUiTest {
        val events = mutableListOf<String>(); val data = mutableStateOf(AgentSettingsUiState()); var reload = ""
        setContent { AppTheme {
            reload = stringResource(Res.string.settings_agents_retry)
            AgentSettingsContent(data.value, emptyList(), emptyList(), null, actions(events), Modifier.width(600.dp).height(700.dp))
        } }
        onNodeWithText("2").assertDoesNotExist()
        runOnIdle { data.value = AgentSettingsUiState(loadingGlobal = false, loadingConfigs = false,
            globalError = UiMessage(Res.string.settings_agents_load_failed, listOf("global-fixture")),
            configsError = UiMessage(Res.string.settings_agents_load_failed, listOf("roles-fixture"))) }
        onNodeWithText("global-fixture", substring = true).assertIsDisplayed()
        onAllNodesWithText(reload)[0].performClick(); onAllNodesWithText(reload)[1].performClick()
        runOnIdle { assertEquals(listOf("global-retry", "roles-retry"), events) }
    }

    @Test
    fun narrowLargeFontKeepsChoicesAndBusyRoleCannotOpenMenus() = runComposeUiTest {
        val events = mutableListOf<String>(); val enabled = mutableStateOf(false); var reset = ""
        setContent { AppTheme {
            val density = LocalDensity.current
            reset = stringResource(Res.string.settings_agents_reset)
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AgentSettingsContent(AgentSettingsUiState(listOf(role().copy(displayName = "A long role name for a narrow window")),
                    SubagentGlobalSettings(4), false, false, savingRole = if (enabled.value) null else "RESEARCHER", configsReady = true),
                    listOf(fast), listOf(provider()), fast, actions(events), Modifier.width(280.dp).height(800.dp), isCompact = true)
            }
        } }
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText(reset))
        onNodeWithText(reset).assertIsNotEnabled()
        runOnIdle { enabled.value = true }
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText(reset))
        onNodeWithText(reset).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(listOf("model:RESEARCHER:null"), events) }
    }

    @Test
    fun renderActualAgentContentInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        setContent { AppTheme(mode.value) {
            SettingsWorkspaceContent(SettingsTab.AGENTS, {}, {}, Modifier.width(960.dp).height(740.dp)
                .background(LocalMederiColors.current.surfaceWorkspace).testTag("agentSample")) { _, compact ->
                AgentSettingsContent(AgentSettingsUiState(listOf(role(), role(true).copy(role = "EXECUTOR", displayName = "Executor")),
                    SubagentGlobalSettings(4), false, false, configsReady = true), listOf(fast, other), listOf(provider()), fast,
                    actions(mutableListOf()), isCompact = compact)
            }
        } }
        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { mode.value = theme }
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("agentSample").captureToImage().toAwtImage(), "png",
                    File(directory, "agents-${theme.name.lowercase()}.png"))
            }
        }
    }
    @Test
    fun inheritedParentModelStillOffersIndependentReasoningAndKeyAndSessionReset() = runComposeUiTest {
        val events = mutableListOf<String>()
        var keyLabel = ""; var reasoningLabel = ""; var restore = ""
        setContent { AppTheme {
            keyLabel = stringResource(Res.string.settings_agents_apikey_inherit)
            reasoningLabel = stringResource(Res.string.settings_agents_inherit_reasoning_short)
            restore = stringResource(Res.string.settings_agents_restore_default)
            Box(Modifier.width(600.dp)) {
                AgentRoleSettings(role(true).copy(usesDefault = false), listOf(fast), listOf(provider()), fast,
                    true, false, actions(events).copy(restoreRoleDefault = { events += "default:$it" }))
            }
        } }
        onNodeWithText(reasoningLabel).assertIsDisplayed()
        onNodeWithText(keyLabel).performClick()
        onNodeWithText("sk-••22").performClick()
        onNodeWithText(restore).performClick()
        runOnIdle { assertEquals(listOf("key:RESEARCHER:key-2", "default:RESEARCHER"), events) }
    }

}
