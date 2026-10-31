package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
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
import xyz.mederi.theme.*
import xyz.mederi.ui.settings.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ProviderCatalogDeliveryTest {
    private val presets = (0 until 300).map { "Vendor ${it.toString().padStart(3, '0')}" }
    private val configured = listOf(
        ProviderItemUiState("configured", "Vendor 002", true, false, baseUrl = "http://127.0.0.1/configured"),
        ProviderItemUiState("private", "Private service", false, false, baseUrl = "http://127.0.0.1/private"),
    )

    @Composable
    private fun Fixture(
        state: MutableState<ProviderSettingsUiState>, modifier: Modifier = Modifier,
        selectPreset: ((String) -> Unit)? = null, submit: (String) -> Unit = {},
    ) {
        ProviderSettingsContent(state.value, presets,
            { state.value = state.value.copy(providerQuery = it, providerIdsFilter = null) },
            { state.value = state.value.copy(presetQuery = it) },
            { state.value = state.value.copy(page = ProviderPage.Catalog) },
            { state.value = state.value.copy(page = ProviderPage.Manage) },
            { state.value = state.value.copy(page = ProviderPage.Custom) },
            { state.value = state.value.copy(page = ProviderPage.Detail, selectedProviderId = it) },
            selectPreset ?: { state.value = state.value.copy(page = ProviderPage.Preset(it)) },
            { state.value = state.value.copy(errorMessage = null) }, modifier,
            detail = { provider, _ -> Text("Detail: ${provider.id}") },
            custom = { Text("Custom endpoint form") },
            preset = { name -> ProviderPresetCredentials(name, state.value.isSavingCredentials, submit,
                { state.value = state.value.copy(page = ProviderPage.Catalog) }) })
    }

    @Test
    fun managementContainsConfiguredProvidersOnlyAndSearchDoesNotTouchModelQuery() = runComposeUiTest {
        val state = mutableStateOf(ProviderSettingsUiState(configured, "configured", searchQuery = "model-filter"))
        var search = ""; var add = ""
        setContent { AppTheme {
            search = stringResource(Res.string.provider_manage_search)
            add = stringResource(Res.string.settings_panel_add_provider)
            Fixture(state, Modifier.width(900.dp).height(650.dp))
        } }
        onNodeWithText("Vendor 002").assertIsDisplayed()
        onNodeWithText("Vendor 003").assertDoesNotExist()
        onNodeWithText(search).performTextInput("private")
        onNodeWithText("Vendor 002").assertDoesNotExist()
        onNodeWithText("Private service").assertIsDisplayed()
        onNodeWithText("Detail: configured").assertIsDisplayed()
        runOnIdle { assertEquals("model-filter", state.value.searchQuery) }
        onNodeWithText(add).performClick()
        runOnIdle { assertEquals(ProviderPage.Catalog, state.value.page); assertEquals("private", state.value.providerQuery) }
    }

    @Test
    fun threeHundredPresetRowsStayScrollableAndConfiguredStatusHasExactTarget() = runComposeUiTest {
        val state = mutableStateOf(ProviderSettingsUiState(configured, page = ProviderPage.Catalog))
        val selected = mutableListOf<String>(); var managed = ""; var search = ""
        setContent { AppTheme {
            managed = stringResource(Res.string.provider_catalog_manage)
            search = stringResource(Res.string.provider_catalog_search)
            Fixture(state, Modifier.width(600.dp).height(650.dp), { selected += it })
        } }
        onNodeWithText(managed, substring = true).assertIsDisplayed()
        onNodeWithText("Vendor 002").performClick()
        runOnIdle { assertEquals(listOf("Vendor 002"), selected) }
        onNode(hasScrollToIndexAction()).performScrollToIndex(299)
        onNodeWithText("Vendor 299").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(listOf("Vendor 002", "Vendor 299"), selected) }
        onNodeWithText(search).performTextReplacement("Vendor 00")
        onNodeWithText("Vendor 000").assertIsDisplayed()
        onNodeWithText("Vendor 299").assertDoesNotExist()
    }

    @Test
    fun returningFromCredentialsKeepsCatalogQueryAndScrollPosition() = runComposeUiTest {
        val state = mutableStateOf(ProviderSettingsUiState(page = ProviderPage.Catalog, presetQuery = "Vendor"))
        var back = ""
        setContent { AppTheme {
            back = stringResource(Res.string.provider_preset_back)
            Fixture(state, Modifier.width(500.dp).height(650.dp))
        } }
        onNode(hasScrollToIndexAction()).performScrollToIndex(299)
        onNodeWithText("Vendor 299").performClick()
        onNodeWithText(back).performClick()
        onNodeWithText("Vendor 299").assertIsDisplayed()
        runOnIdle { assertEquals("Vendor", state.value.presetQuery) }
    }

    @Test
    fun noResultsStillOffersCustomConnectionAndClearSearch() = runComposeUiTest {
        val state = mutableStateOf(ProviderSettingsUiState(page = ProviderPage.Catalog, presetQuery = "unavailable-vendor"))
        var empty = ""; var custom = ""; var clear = ""
        setContent { AppTheme {
            empty = stringResource(Res.string.provider_search_empty)
            custom = stringResource(Res.string.provider_custom_entry)
            clear = stringResource(Res.string.provider_search_clear)
            Fixture(state, Modifier.width(320.dp).height(650.dp))
        } }
        onNodeWithText(empty).assertIsDisplayed()
        onNodeWithText(clear).performClick()
        onNodeWithText("Vendor 000").assertIsDisplayed()
        onNodeWithText(custom).performClick()
        onNodeWithText("Custom endpoint form").assertIsDisplayed()
    }

    @Test
    fun credentialsKeepDraftAcrossFailureAndDoNotInventADetailAction() = runComposeUiTest {
        val state = mutableStateOf(ProviderSettingsUiState(page = ProviderPage.Preset("Vendor 010")))
        val submitted = mutableListOf<String>(); var add = ""; var back = ""; var detail = ""
        setContent { AppTheme {
            add = stringResource(Res.string.settings_panel_add)
            back = stringResource(Res.string.provider_preset_back)
            detail = stringResource(Res.string.errorboard_detail_report)
            Fixture(state, Modifier.width(400.dp).height(650.dp), submit = { submitted += it })
        } }
        onNodeWithText(add).assertIsNotEnabled()
        onNode(hasSetTextAction()).performTextInput(" fixture-key ")
        onNodeWithText(add).performClick()
        runOnIdle {
            assertEquals(listOf("fixture-key"), submitted)
            state.value = state.value.copy(isSavingCredentials = true)
        }
        onNodeWithText(back).assertIsNotEnabled()
        onAllNodes(hasSetTextAction()).assertCountEquals(0)
        runOnIdle { state.value = state.value.copy(isSavingCredentials = false,
            errorMessage = UiMessage(Res.string.provider_add_failed, listOf("fixture failure"))) }
        onNodeWithText("fixture failure", substring = true).assertIsDisplayed()
        onNodeWithText(detail).assertDoesNotExist()
        onNodeWithText(add).assertIsEnabled().performClick()
        runOnIdle { assertEquals(listOf("fixture-key", "fixture-key"), submitted) }
    }

    @Test
    fun narrowLargeFontCatalogKeepsSearchCustomAndBackReachable() = runComposeUiTest {
        val state = mutableStateOf(ProviderSettingsUiState(page = ProviderPage.Catalog, presetQuery = "299"))
        var custom = ""; var back = ""
        setContent { AppTheme {
            custom = stringResource(Res.string.provider_custom_entry)
            back = stringResource(Res.string.provider_catalog_back)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Fixture(state, Modifier.width(320.dp).height(700.dp))
            }
        } }
        onNodeWithText(custom).assertIsDisplayed()
        onNodeWithText("Vendor 299").assertIsDisplayed()
        onNodeWithText(back).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(ProviderPage.Manage, state.value.page) }
    }

    @Test
    fun compactSelectionShowsDetailAndBackReturnsToSameConfiguredList() = runComposeUiTest {
        val state = mutableStateOf(ProviderSettingsUiState(configured, "configured", providerQuery = "private"))
        var back = ""
        setContent { AppTheme {
            back = stringResource(Res.string.settings_panel_back_list)
            Fixture(state, Modifier.width(400.dp).height(650.dp))
        } }
        onNodeWithText("Private service").performClick()
        onNodeWithText("Detail: private").assertIsDisplayed()
        onNodeWithText(back).performClick()
        onNodeWithText("Private service").assertIsDisplayed()
        runOnIdle { assertEquals("private", state.value.providerQuery) }
    }

    @Test
    fun managementHeaderCentersHorizontallyAndStacksForLargeText() = runComposeUiTest {
        val narrow = mutableStateOf(false); var add = ""; var count = ""; var adds = 0
        setContent { AppTheme {
            add = stringResource(Res.string.settings_panel_add_provider)
            count = stringResource(Res.string.settings_panel_providers_count, 6)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (narrow.value) 1.5f else 1f)) {
                ProviderManagementHeader(6, { adds++ }, modifier = Modifier.width(if (narrow.value) 180.dp else 320.dp).testTag("header"))
            }
        } }
        var label = onNodeWithText(count).fetchSemanticsNode().boundsInRoot
        var button = onNodeWithText(add).fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(label.center.y - button.center.y) <= 1f)
        System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
            val directory = File(path).apply { mkdirs() }
            ImageIO.write(onNodeWithTag("header").captureToImage().toAwtImage(), "png", File(directory, "provider-header-horizontal.png"))
        }
        onNodeWithText(add).performClick()
        runOnIdle { narrow.value = true }
        label = onNodeWithText(count).fetchSemanticsNode().boundsInRoot
        button = onNodeWithText(add).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(button.top >= label.bottom)
        val parent = onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        assertTrue(button.left >= parent.left && button.right <= parent.right)
        System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
            val directory = File(path).apply { mkdirs() }
            ImageIO.write(onNodeWithTag("header").captureToImage().toAwtImage(), "png", File(directory, "provider-header-stacked.png"))
        }
        onNodeWithText(add).performClick()
        runOnIdle { assertEquals(2, adds) }
    }

    @Test
    fun managementToolbarSharesListEdgesAndNoLongerPushesDetailDown() = runComposeUiTest {
        val state = mutableStateOf(ProviderSettingsUiState(configured, "configured"))
        var count = ""; var search = ""; var add = ""
        setContent { AppTheme {
            count = stringResource(Res.string.settings_panel_providers_count, configured.size)
            search = stringResource(Res.string.provider_manage_search)
            add = stringResource(Res.string.settings_panel_add_provider)
            Fixture(state, Modifier.width(900.dp).height(650.dp))
        } }
        val label = onNodeWithText(count).fetchSemanticsNode().boundsInRoot
        val field = onNodeWithText(search).fetchSemanticsNode().boundsInRoot
        val button = onNodeWithText(add).fetchSemanticsNode().boundsInRoot
        val detail = onNodeWithText("Detail: configured").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(label.center.y - button.center.y) <= 1f)
        assertTrue(label.left >= field.left && button.right <= field.right + 1f)
        assertTrue(detail.top <= field.top, "The detail should start alongside the toolbar, not below it")
    }

    @Test
    fun renderActualDirectoryAndCredentialComponentsInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        val state = mutableStateOf(ProviderSettingsUiState(configured, "configured", page = ProviderPage.Catalog, presetQuery = "00"))
        setContent { AppTheme(mode.value) {
            SettingsWorkspaceContent(SettingsTab.PROVIDERS, {}, {},
                Modifier.width(960.dp).height(720.dp).background(LocalMederiColors.current.surfaceWorkspace).testTag("providerSample")) { _, _ ->
                Fixture(state)
            }
        } }
        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) for (credential in listOf(false, true)) {
            runOnIdle { mode.value = theme; state.value = state.value.copy(page = if (credential) ProviderPage.Preset("Vendor 010") else ProviderPage.Catalog) }
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                val page = if (credential) "credentials" else "directory"
                ImageIO.write(onNodeWithTag("providerSample").captureToImage().toAwtImage(), "png",
                    File(directory, "provider-$page-${theme.name.lowercase()}.png"))
            }
        }
    }
}
