package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.*
import xyz.mederi.theme.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ProviderFormsDeliveryTest {
    private data class ModelSave(val id: String, val name: String, val images: Boolean,
        val thinking: Boolean, val context: Int?, val output: Int?, val levels: List<String>, val optimizeForRequestCount: Boolean = false)

    @Composable
    private fun FormHost(scroll: Boolean = true, content: @Composable () -> Unit) {
        val colors = LocalMederiColors.current
        Box(Modifier.width(460.dp).height(640.dp).background(colors.surfaceCard).testTag("providerFormSample")) {
            if (scroll) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
            else Box(Modifier.fillMaxSize().padding(16.dp)) { content() }
        }
    }

    private fun model(origin: ModelOrigin) = ModelItemUiState(
        id = "exact-model", providerModelId = "model-id", name = "  Original model  ", origin = origin,
        supportsImages = false, supportsThinking = true, contextWindow = 128000, maxTokens = 8192,
        reasoningLevels = listOf("HIGH", "LOW"),
    )

    @Test
    fun fetchedModelAllowsFillingMissingMetadataAndSavingEveryEditedField() = runComposeUiTest {
        val original = model(ModelOrigin.FETCHED).copy(
            contextWindow = null, maxTokens = null, supportsThinking = false, reasoningLevels = emptyList())
        var saved: ModelSave? = null
        var name = ""; var context = ""; var output = ""; var thinking = ""; var image = ""; var billing = ""
        setContent { AppTheme {
            name = stringResource(Res.string.settings_panel_display_name)
            context = stringResource(Res.string.settings_panel_context_window)
            output = stringResource(Res.string.settings_panel_max_output)
            thinking = stringResource(Res.string.settings_panel_support_thinking)
            image = stringResource(Res.string.settings_panel_support_images)
            billing = stringResource(Res.string.settings_panel_billed_per_request)
            FormHost {
                ModelConfigurationContent(original, "Fetched fixture", "Save fixture", LocalMederiColors.current, {},
                    { id, n, images, t, cw, mt, levels, optimizeForRequestCount ->
                        saved = ModelSave(id, n, images, t, cw, mt, levels, optimizeForRequestCount)
                    })
            }
        } }
        onAllNodes(hasSetTextAction()).assertCountEquals(3)
        onNodeWithContentDescription(name).performTextReplacement("  Local model  ")
        onNodeWithContentDescription(context).performScrollTo().performTextReplacement("262144")
        onNodeWithContentDescription(output).performScrollTo().performTextReplacement("32768")
        onNodeWithContentDescription(thinking).performScrollTo().assertIsEnabled().performClick()
        onNodeWithText("HIGH").performScrollTo().assertIsEnabled().performClick()
        onNodeWithContentDescription(image).performScrollTo().assertIsEnabled().performClick()
        onNodeWithContentDescription(billing).performScrollTo().assertIsOff().performClick()
        onNodeWithText("Save fixture").performScrollTo().performClick()
        runOnIdle {
            assertEquals(ModelSave(original.providerModelId, "Local model", true, true,
                262144, 32768, listOf("HIGH"), true), saved)
        }
    }

    @Test
    fun manualEditKeepsTrimDigitsAndCanonicalReasoningOrder() = runComposeUiTest {
        val original = model(ModelOrigin.MANUAL)
        var saved: ModelSave? = null
        var name = ""; var context = ""; var output = ""
        setContent { AppTheme {
            name = stringResource(Res.string.settings_panel_display_name)
            context = stringResource(Res.string.settings_panel_context_window)
            output = stringResource(Res.string.settings_panel_max_output)
            FormHost {
                ModelConfigurationContent(original, "Manual fixture", "Save fixture", LocalMederiColors.current, {},
                    { id, n, images, t, cw, mt, levels, optimizeForRequestCount ->
                        saved = ModelSave(id, n, images, t, cw, mt, levels, optimizeForRequestCount)
                    })
            }
        } }
        onNodeWithContentDescription(name).performTextReplacement("  New model  ")
        onNodeWithContentDescription(context).performScrollTo().performTextReplacement("1x2a3")
        onNodeWithContentDescription(output).performScrollTo().performTextReplacement("4z56")
        onNodeWithText("Save fixture").performScrollTo().performClick()
        runOnIdle { assertEquals(ModelSave("model-id", "New model", false, true, 123, 456, listOf("LOW", "HIGH")), saved) }
    }

    @Test
    fun newManualModelRejectsBlankIdAndTrimsSubmittedIdentifiersOnlyAtSubmit() = runComposeUiTest {
        var saved: ModelSave? = null
        var idLabel = ""; var nameLabel = ""
        setContent { AppTheme {
            idLabel = stringResource(Res.string.settings_panel_model_id_label)
            nameLabel = stringResource(Res.string.settings_panel_display_name)
            FormHost {
                ModelConfigurationContent(null, "Add fixture", "Add fixture model", LocalMederiColors.current, {},
                    { id, n, images, t, cw, mt, levels, optimizeForRequestCount ->
                        saved = ModelSave(id, n, images, t, cw, mt, levels, optimizeForRequestCount)
                    })
            }
        } }
        onNodeWithText("Add fixture model").performScrollTo().assertIsNotEnabled()
        onNodeWithContentDescription(idLabel).performScrollTo().performTextInput("  private-model  ")
        onNodeWithContentDescription(nameLabel).performTextReplacement("  Private display  ")
        onNodeWithText("Add fixture model").performScrollTo().assertIsEnabled().performClick()
        runOnIdle { assertEquals(ModelSave("private-model", "Private display", false, false, null, null, emptyList()), saved) }
    }

    @Test
    fun keyManagementFiltersRotationAndKeepsDefaultDeleteAndTrimmedAddTargetsExact() = runComposeUiTest {
        val provider = mutableStateOf(ProviderItemUiState("provider-a", "Provider A", false, true,
            apiKeys = listOf(ApiKeyOption("key-a", "Same alias", "***111", true),
                ApiKeyOption(API_KEY_ROTATION_ID, "Rotation fixture", "", false),
                ApiKeyOption("key-b", "Same alias", "***222", false))))
        val defaults = mutableListOf<String>(); val deletes = mutableListOf<String>()
        var added: Triple<String, String, Boolean>? = null
        var setDefault = ""; var delete = ""; var alias = ""; var secret = ""; var save = ""; var defaultToggle = ""
        setContent { AppTheme {
            setDefault = stringResource(Res.string.settings_panel_set_default)
            delete = stringResource(Res.string.settings_panel_delete)
            alias = stringResource(Res.string.settings_panel_key_alias)
            secret = stringResource(Res.string.settings_panel_api_key_plain)
            save = stringResource(Res.string.settings_panel_save_key)
            defaultToggle = stringResource(Res.string.settings_panel_set_default_key)
            FormHost {
                ApiKeyManagementContent(provider.value, LocalMederiColors.current, {},
                    { n, k, d -> added = Triple(n, k, d) }, { defaults += it }, { deletes += it })
            }
        } }
        onNodeWithText("Rotation fixture").assertDoesNotExist()
        onNodeWithText("***111").assertExists()
        onNodeWithText("***222").assertExists()
        onNodeWithText(setDefault).performScrollTo().performClick()
        onAllNodesWithText(delete)[1].performScrollTo().performClick()
        runOnIdle {
            assertEquals(listOf("key-b"), defaults); assertEquals(listOf("key-b"), deletes)
            provider.value = ProviderItemUiState("provider-empty", "Empty", false, false)
        }
        onNodeWithText(save).performScrollTo().assertIsNotEnabled()
        onNodeWithContentDescription(alias).performScrollTo().performTextInput("  private alias  ")
        onNodeWithContentDescription(secret).performScrollTo().performTextInput("  fixture-secret  ")
        onNodeWithContentDescription(defaultToggle).performScrollTo().performClick()
        onNodeWithText(save).performScrollTo().performClick()
        runOnIdle { assertEquals(Triple("private alias", "fixture-secret", false), added) }
    }

    @Test
    fun customFormKeepsProtocolRawValuesAndBusySubmissionGate() = runComposeUiTest {
        val saving = mutableStateOf(false)
        var values: List<Any?>? = null
        var name = ""; var url = ""; var secret = ""; var save = ""; var busy = ""; var cancel = ""
        setContent { AppTheme {
            name = stringResource(Res.string.settings_panel_provider_name_label)
            url = stringResource(Res.string.settings_panel_base_url_label)
            secret = stringResource(Res.string.settings_panel_api_key_secret_label)
            save = stringResource(Res.string.settings_panel_save_connect)
            busy = stringResource(Res.string.settings_panel_connecting)
            cancel = stringResource(Res.string.settings_panel_cancel)
            FormHost(scroll = false) {
                CustomProviderFormContent(saving.value, LocalMederiColors.current, {},
                    { n, protocol, endpoint, key, levels -> values = listOf(n, protocol, endpoint, key, levels) })
            }
        } }
        onNodeWithText(save).performScrollTo().assertIsNotEnabled()
        onNodeWithContentDescription(name).performScrollTo().performTextInput("  Raw provider  ")
        onNodeWithText(ProtocolType.GOOGLE.displayName).performScrollTo().performClick()
        onNodeWithContentDescription(url).performScrollTo().performTextInput(" https://example.invalid/api ")
        onNodeWithContentDescription(secret).performScrollTo().performTextInput(" raw-key ")
        runOnIdle { saving.value = true }
        onNodeWithText(busy).performScrollTo().assertIsNotEnabled()
        onNodeWithText(cancel).assertIsNotEnabled()
        runOnIdle { assertNull(values); saving.value = false }
        onNodeWithText(save).performClick()
        runOnIdle { assertEquals(listOf("  Raw provider  ", ProtocolType.GOOGLE, " https://example.invalid/api ", " raw-key ", emptyMap<String, String?>()), values) }
    }

    @Test
    fun credentialEditsPreserveOriginalWhitespaceAndReasoningPayload() = runComposeUiTest {
        val payload = "  {\"reasoning_effort\":\"low\"}  "
        val provider = ProviderItemUiState("credentials", " Original ", false, true,
            baseUrl = " https://example.invalid/v1 ", apiKey = " old-key ", reasoningLevels = mapOf("LOW" to payload))
        var saved: List<Any?>? = null
        var name = ""; var save = ""; var keyLabel = ""
        setContent { AppTheme {
            keyLabel = stringResource(Res.string.provider_api_key)
            name = stringResource(Res.string.settings_panel_provider_name_label)
            save = stringResource(Res.string.settings_panel_save_reconnect)
            FormHost {
                ProviderCredentialsContent(provider, LocalMederiColors.current, {},
                    { n, url, key, levels -> saved = listOf(n, url, key, levels) })
            }
        } }
        onNodeWithContentDescription(name).performTextReplacement(" Edited name ")
        onNodeWithText(save).performScrollTo().performClick()
        runOnIdle { assertEquals(listOf(" Edited name ", provider.baseUrl, "", provider.reasoningLevels), saved) }
        onNodeWithContentDescription(keyLabel).performScrollTo().performTextInput(" replacement-key ")
        onNodeWithText(save).performScrollTo().performClick()
        runOnIdle { assertEquals(listOf(" Edited name ", provider.baseUrl, " replacement-key ", provider.reasoningLevels), saved) }
    }

    @Test
    fun actualProviderFormsRenderInBothThemesWithoutNetworkOrPlatformDialogs() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        setContent { AppTheme(mode.value) {
            FormHost {
                ModelConfigurationContent(model(ModelOrigin.FETCHED), "Catalog model", "Save fixture", LocalMederiColors.current, {},
                    { _, _, _, _, _, _, _, _ -> })
            }
        } }
        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { mode.value = theme }
            onNodeWithText("Catalog model").assertIsDisplayed()
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("providerFormSample").captureToImage().toAwtImage(), "png",
                    File(directory, "provider-form-${theme.name.lowercase()}.png"))
            }
        }
    }
}
