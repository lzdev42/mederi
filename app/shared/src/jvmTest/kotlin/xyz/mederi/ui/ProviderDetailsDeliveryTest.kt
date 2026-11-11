package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import xyz.mederi.core.contract.models.ProtocolType
import xyz.mederi.theme.*
import xyz.mederi.ui.settings.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ProviderDetailsDeliveryTest {
    private fun model(id: String = "model-a", enabled: Boolean = true) = ModelItemUiState(id, "vendor/$id", "Model $id",
        isEnabled = enabled, supportsThinking = true, supportsImages = true, contextWindow = 128000,
        maxTokens = 8192, reasoningLevels = listOf("NONE", "LOW", "HIGH"))
    private fun provider(builtin: Boolean = false) = ProviderItemUiState("provider-a", "A provider with a readable identity", builtin,
        true, protocolType = ProtocolType.OPENAI_CHAT, baseUrl = "https://api.example.invalid/a/long/provider/endpoint/v1",
        apiKey = "masked-value", reasoningLevels = mapOf("NONE" to null, "LOW" to "{}", "HIGH" to "{}"),
        models = listOf(model(), model("model-b", false)))
    private fun actions(events: MutableList<String>) = ProviderDetailActions(
        { events += "keys" }, { events += "credentials" }, { events += "disconnect" },
        { events += "query:$it" }, { events += "filter:${it.name}" }, { events += "all:$it" },
        { events += "refresh" }, { events += "setup" }, { events += "add" },
        { events += "toggle:${it.id}" }, { events += "edit:${it.id}" }, { events += "delete:${it.id}" },
        { events += "delete-provider" }, { enabled, text -> events += "retry:$enabled:$text" })

    @Test
    fun toolbarActionsQueryAndAllFiltersAreIndependent() = runComposeUiTest {
        val events = mutableListOf<String>(); val filter = mutableStateOf(CapabilityFilter.ALL)
        val query = mutableStateOf(""); val labels = mutableMapOf<CapabilityFilter, String>()
        var show = ""; var hide = ""; var refresh = ""; var setup = ""; var add = ""
        setContent { AppTheme {
            show = stringResource(Res.string.settings_panel_show_all); hide = stringResource(Res.string.settings_panel_hide_all)
            refresh = stringResource(Res.string.settings_panel_refresh_models); setup = stringResource(Res.string.settings_panel_auto_setup)
            add = stringResource(Res.string.settings_panel_add_model)
            labels[CapabilityFilter.ALL] = stringResource(Res.string.settings_filter_all_count, 2)
            labels[CapabilityFilter.ENABLED_ONLY] = stringResource(Res.string.settings_filter_enabled_count, 1)
            labels[CapabilityFilter.REASONING] = stringResource(Res.string.settings_filter_reasoning)
            labels[CapabilityFilter.IMAGE] = stringResource(Res.string.settings_filter_image)
            labels[CapabilityFilter.FREE] = stringResource(Res.string.settings_filter_free)
            Box(Modifier.width(620.dp)) {
                ProviderModelToolbar(provider(), query.value, filter.value, actions(events).copy(
                    search = { query.value = it; events += "query:$it" }, filter = { filter.value = it; events += "filter:${it.name}" }))
            }
        } }
        onNodeWithText(show).performClick(); onNodeWithText(hide).performClick()
        onNodeWithText(refresh).performClick(); onNodeWithText(setup).performClick(); onNodeWithText(add).performClick()
        onNode(hasSetTextAction()).performTextInput("exact-model-id")
        for (option in CapabilityFilter.entries) {
            onNode(hasText(labels.getValue(option)) and isSelectable()).performClick().assertIsSelected()
        }
        runOnIdle {
            assertEquals(listOf("all:true", "all:false", "refresh", "setup", "add", "query:exact-model-id") +
                CapabilityFilter.entries.map { "filter:${it.name}" }, events)
        }
    }

    @Test
    fun modelRowKeepsEnableEditDeleteTargetsAndReadableHiddenState() = runComposeUiTest {
        val enabled = mutableStateOf(false); val events = mutableListOf<String>()
        var enable = ""; var edit = ""; var delete = ""; var hidden = ""
        setContent { AppTheme {
            enable = stringResource(Res.string.provider_model_enable); edit = stringResource(Res.string.settings_panel_edit)
            delete = stringResource(Res.string.settings_panel_delete); hidden = stringResource(Res.string.provider_model_hidden)
            Box(Modifier.width(280.dp)) {
                ModelItemRow(model(enabled = enabled.value), LocalMederiColors.current, true,
                    { events += "toggle:model-a"; enabled.value = !enabled.value }, { events += "edit:model-a" }, { events += "delete:model-a" })
            }
        } }
        onNodeWithText(hidden).assertIsDisplayed()
        onNodeWithText("vendor/model-a").assertIsDisplayed()
        onNodeWithContentDescription(edit).performClick()
        onNodeWithContentDescription(delete).performClick()
        onNodeWithContentDescription(enable).performClick()
        runOnIdle { assertEquals(listOf("edit:model-a", "delete:model-a", "toggle:model-a"), events) }
        onNodeWithText(hidden).assertDoesNotExist()
    }

    @Test
    fun providerCredentialsRemainCompleteAndBuiltinControlsStayRestricted() = runComposeUiTest {
        val data = mutableStateOf(provider()); val events = mutableListOf<String>()
        var keys = ""; var edit = ""; var disconnect = ""; var delete = ""
        setContent { AppTheme {
            keys = stringResource(Res.string.settings_panel_configure_keys)
            edit = stringResource(Res.string.settings_panel_edit_reasoning)
            disconnect = stringResource(Res.string.settings_panel_disconnect)
            delete = stringResource(Res.string.settings_panel_delete)
            ProviderDetailContent(data.value, emptyList(), "", CapabilityFilter.ALL, actions(events), Modifier.width(600.dp).height(700.dp))
        } }
        onNodeWithText(data.value.baseUrl).assertIsDisplayed()
        val titleBounds = onNodeWithText(data.value.name).fetchSemanticsNode().boundsInRoot
        val deleteBounds = onNodeWithText(delete).fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(titleBounds.center.y - deleteBounds.center.y) <= 1f)
        onNodeWithText(keys).performScrollTo().performClick()
        onNodeWithText(edit).performScrollTo().performClick()
        onNodeWithText(disconnect).performScrollTo().performClick()
        onNodeWithText(delete).performScrollTo().performClick()
        runOnIdle { assertEquals(listOf("keys", "credentials", "disconnect", "delete-provider"), events); data.value = provider(true) }
        onNodeWithText(edit).assertDoesNotExist()
        onNodeWithText(delete).assertDoesNotExist()
        onNodeWithText(keys).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun syncingDisablesRefreshWhileOtherToolsRemainAvailable() = runComposeUiTest {
        val events = mutableListOf<String>(); var syncing = ""; var add = ""
        setContent { AppTheme {
            syncing = stringResource(Res.string.settings_panel_syncing); add = stringResource(Res.string.settings_panel_add_model)
            Box(Modifier.width(500.dp)) { ProviderModelToolbar(provider().copy(syncStatus = ModelsSyncStatus.Syncing), "", CapabilityFilter.ALL, actions(events)) }
        } }
        onNodeWithText(syncing).assertIsNotEnabled()
        onNodeWithText(add).assertIsEnabled().performClick()
        runOnIdle { assertEquals(listOf("add"), events) }
    }

    @Test
    fun syncFailureUsesSharedErrorAndRetryWithoutFakeDismissOrDetails() = runComposeUiTest {
        val state = mutableStateOf<ModelsSyncStatus>(ModelsSyncStatus.Error("Fixture sync failure"))
        val events = mutableListOf<String>(); var refresh = ""; var detail = ""; var close = ""; var unsupported = ""; var empty = ""
        setContent { AppTheme {
            refresh = stringResource(Res.string.settings_panel_refresh_models); detail = stringResource(Res.string.errorboard_detail_report)
            close = stringResource(Res.string.errorboard_close); unsupported = stringResource(Res.string.settings_panel_sync_unsupported)
            empty = stringResource(Res.string.settings_panel_sync_empty)
            val data = provider().copy(syncStatus = state.value, models = emptyList())
            ProviderDetailContent(data, emptyList(), "", CapabilityFilter.ALL, actions(events), Modifier.width(500.dp).height(650.dp))
        } }
        onNodeWithText("Fixture sync failure", substring = true).performScrollTo().assertIsDisplayed()
        onAllNodesWithText(refresh).assertCountEquals(2)
        onAllNodesWithText(refresh)[1].performScrollTo().performClick()
        onNodeWithText(detail).assertDoesNotExist(); onNodeWithContentDescription(close).assertDoesNotExist()
        runOnIdle { assertEquals(listOf("refresh"), events); state.value = ModelsSyncStatus.UnsupportedEndpoint }
        onNodeWithText(unsupported).performScrollTo().assertIsDisplayed()
        runOnIdle { state.value = ModelsSyncStatus.Empty }
        onNodeWithText(empty).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun largeFontLongModelKeepsAllActionsReachable() = runComposeUiTest {
        val events = mutableListOf<String>(); var toggle = ""; var edit = ""; var delete = ""
        setContent { AppTheme {
            toggle = stringResource(Res.string.provider_model_disable); edit = stringResource(Res.string.settings_panel_edit)
            delete = stringResource(Res.string.settings_panel_delete)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Column(Modifier.width(280.dp).height(600.dp).verticalScroll(rememberScrollState())) {
                    ModelItemRow(model().copy(name = "A very long model identity with deployment and provider details"), LocalMederiColors.current,
                        true, { events += "toggle" }, { events += "edit" }, { events += "delete" })
                }
            }
        } }
        for (description in listOf(toggle, edit, delete)) onNodeWithContentDescription(description).performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertEquals(listOf("toggle", "edit", "delete"), events) }
    }

    @Test
    fun switchingProviderStartsAtItsOwnIdentityInsteadOfThePreviousModelOffset() = runComposeUiTest {
        val data = mutableStateOf(provider().copy(models = (0 until 40).map { model("model-$it") }))
        setContent { AppTheme {
            ProviderDetailContent(data.value, data.value.models, "", CapabilityFilter.ALL, actions(mutableListOf()),
                Modifier.width(600.dp).height(650.dp))
        } }
        onNode(hasScrollToIndexAction()).performScrollToIndex(42)
        onNodeWithText("Model model-39").assertIsDisplayed()
        runOnIdle { data.value = provider().copy(id = "provider-b", name = "Another provider") }
        onNodeWithText("Another provider").assertIsDisplayed()
    }

    @Test
    fun retryRowPlacesCompactSwitchAndRightSideDelayInputWithEmptyDefaultPlaceholder() = runComposeUiTest {
        val data = mutableStateOf(provider().copy(autoRetryEnabled = true, retryMaxDelaySeconds = null))
        val saved = mutableListOf<Pair<Boolean, String>>()
        var autoRetryLabel = ""; var delayLabel = ""; var placeholder = ""; var saveLabel = ""
        var density = 1f
        setContent { AppTheme {
            density = LocalDensity.current.density
            autoRetryLabel = stringResource(Res.string.provider_auto_retry_label)
            delayLabel = stringResource(Res.string.provider_retry_delay_label)
            placeholder = stringResource(Res.string.provider_retry_placeholder)
            saveLabel = stringResource(Res.string.provider_retry_save)
            Box(Modifier.width(560.dp)) {
                ProviderRetrySettingRow(data.value, LocalMederiColors.current) { enabled, text ->
                    saved += enabled to text
                }
            }
        } }
        onNodeWithText(placeholder).assertIsDisplayed()
        val switchBounds = onNodeWithContentDescription(autoRetryLabel).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val inputNode = onNodeWithContentDescription(delayLabel).assertIsDisplayed().assertIsEnabled()
        val inputBounds = inputNode.fetchSemanticsNode().boundsInRoot
        val saveBounds = onNodeWithText(saveLabel).assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertTrue(kotlin.math.abs(switchBounds.center.y - inputBounds.center.y) <= 4f * density,
            "Switch and delay input should stay on one horizontal row")
        assertTrue(inputBounds.left > switchBounds.right, "Delay input should be placed on the right of switch")
        assertTrue(saveBounds.left >= inputBounds.right - 1f && kotlin.math.abs(saveBounds.center.y - inputBounds.center.y) <= 4f * density,
            "Save action should align inline to the right of delay input")
        assertTrue(inputBounds.height <= 36f * density, "Delay input should stay compact (~30dp), was ${inputBounds.height / density}dp")
        assertTrue(saveBounds.height <= 36f * density, "Save button should stay compact (~30dp), was ${saveBounds.height / density}dp")

        onNodeWithText(saveLabel).performClick()
        inputNode.performTextInput(" 15 ")
        onNodeWithText(saveLabel).performClick()
        inputNode.performTextReplacement("")
        onNodeWithText(saveLabel).performClick()
        runOnIdle {
            assertEquals(listOf(true to "", true to " 15 ", true to ""), saved)
            data.value = data.value.copy(retryMaxDelaySeconds = 8)
        }
        onNodeWithText("8").assertIsDisplayed()
        onNodeWithContentDescription(autoRetryLabel).performClick()
        onNodeWithContentDescription(delayLabel).assertIsNotEnabled()
        onNodeWithText(saveLabel).performClick()
        runOnIdle { assertEquals(false to "8", saved.last()) }
    }

    @Test
    fun narrowLargeFontRetryRowWrapsAndKeepsControlsReachable() = runComposeUiTest {
        val data = provider().copy(autoRetryEnabled = true, retryMaxDelaySeconds = null)
        val saved = mutableListOf<Pair<Boolean, String>>()
        var autoRetryLabel = ""; var delayLabel = ""; var saveLabel = ""
        setContent { AppTheme {
            autoRetryLabel = stringResource(Res.string.provider_auto_retry_label)
            delayLabel = stringResource(Res.string.provider_retry_delay_label)
            saveLabel = stringResource(Res.string.provider_retry_save)
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, 2f)) {
                Column(Modifier.width(280.dp).height(400.dp).verticalScroll(rememberScrollState())) {
                    ProviderRetrySettingRow(data, LocalMederiColors.current) { enabled, text ->
                        saved += enabled to text
                    }
                }
            }
        } }
        val switchBounds = onNodeWithContentDescription(autoRetryLabel).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val inputBounds = onNodeWithContentDescription(delayLabel).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(inputBounds.top >= switchBounds.bottom - 1f, "Narrow/2x fontScale layout should wrap cleanly onto a second row")
        onNodeWithContentDescription(delayLabel).performTextInput("20")
        onNodeWithText(saveLabel).performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertEquals(listOf(true to "20"), saved) }
    }

    @Test
    fun renderActualDetailComponentsInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        setContent { AppTheme(mode.value) {
            SettingsWorkspaceContent(SettingsTab.PROVIDERS, {}, {}, Modifier.width(960.dp).height(740.dp)
                .background(LocalMederiColors.current.surfaceWorkspace).testTag("detailSample")) { _, _ ->
                ProviderDetailContent(provider(), provider().models, "", CapabilityFilter.ALL, actions(mutableListOf()),
                    modifier = Modifier.fillMaxSize().padding(12.dp))
            }
        } }
        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { mode.value = theme }
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("detailSample").captureToImage().toAwtImage(), "png",
                    File(directory, "provider-details-${theme.name.lowercase()}.png"))
            }
        }
    }
}
