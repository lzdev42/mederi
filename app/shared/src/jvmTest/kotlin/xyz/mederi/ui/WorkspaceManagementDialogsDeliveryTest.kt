package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.*
import kotlinx.coroutines.channels.Channel
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.SkillItem
import xyz.mederi.theme.*
import xyz.mederi.ui.components.*
import xyz.mederi.ui.components.atoms.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WorkspaceManagementDialogsDeliveryTest {
    @Test
    fun skillInstallRejectsDuplicateSubmitKeepsFailureDraftAndClosesOnlyOnSuccess() = runComposeUiTest {
        val results = Channel<Result<SkillItem>>(Channel.UNLIMITED)
        val submitted = mutableListOf<String>(); val open = mutableStateOf(true); var dismisses = 0
        var install = ""; var busy = ""; var cancel = ""
        setContent { AppTheme {
            install = stringResource(Res.string.skill_install_action); busy = stringResource(Res.string.skill_installing)
            cancel = stringResource(Res.string.cancel)
            if (open.value) InstallSkillDialog({ dismisses++; open.value = false }, { url -> submitted += url; results.receive() }, LocalMederiColors.current)
        } }
        onNodeWithText(install).assertIsNotEnabled()
        val raw = "  https://example.invalid/skill.zip?version=2  "
        onNode(hasSetTextAction()).performTextInput(raw)
        onNodeWithText(install).performClick()
        waitUntil { submitted.size == 1 }
        onNodeWithText(busy).assertIsNotEnabled().performTouchInput { click() }
        onNodeWithText(cancel).assertIsNotEnabled().performTouchInput { click() }
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertEquals(0, dismisses); assertEquals(listOf(raw.trim()), submitted) }
        results.trySend(Result.failure(IllegalStateException("fixture install rejected")))
        onNodeWithText("fixture install rejected").assertExists()
        onNode(hasSetTextAction()).assertTextContains(raw)
        onNodeWithText(install).performScrollTo().performClick()
        waitUntil { submitted.size == 2 }
        results.trySend(Result.success(SkillItem("installed", "fixture")))
        waitUntil { dismisses == 1 }
        runOnIdle { assertEquals(listOf(raw.trim(), raw.trim()), submitted) }
    }

    @Test
    fun mcpKeepsJsonWhitespaceAndDraftAcrossFailureAndInitialValueRefresh() = runComposeUiTest {
        val initial = mutableStateOf("  {\n  \"mcpServers\": {}\n}  ")
        val submitted = mutableListOf<String>(); val open = mutableStateOf(true); var dismisses = 0; var save = ""
        setContent { AppTheme {
            save = stringResource(Res.string.mcp_save)
            if (open.value) McpConfigDialog("Edit fixture server", initial.value, { open.value = false; dismisses++ }, { json ->
                submitted += json
                if (submitted.size == 1) Result.failure(IllegalStateException("fixture invalid config")) else Result.success(Unit)
            }, LocalMederiColors.current)
        } }
        val raw = "  {\n  \"mcpServers\": {\n    \"same-name\": {\"args\": [\"a  b\"], \"token\": \"literal-value\"}\n  }\n}  "
        onNode(hasSetTextAction()).performTextReplacement(raw)
        runOnIdle { initial.value = "new upstream value must not overwrite a draft" }
        onNode(hasSetTextAction()).assertTextContains(raw)
        onNodeWithText(save).performScrollTo().performClick()
        onNodeWithText("fixture invalid config").assertExists()
        runOnIdle { assertEquals(0, dismisses); assertEquals(listOf(raw.trim()), submitted) }
        onNodeWithText(save).performScrollTo().performClick()
        waitUntil { dismisses == 1 }
        runOnIdle { assertEquals(listOf(raw.trim(), raw.trim()), submitted) }
    }

    @Test
    fun skillDirectoryTrimsSubmissionAndCancelDoesNotSave() = runComposeUiTest {
        val submitted = mutableListOf<String>(); var dismisses = 0; var save = ""; var cancel = ""
        setContent { AppTheme {
            save = stringResource(Res.string.skill_save); cancel = stringResource(Res.string.cancel)
            SetSkillRootDialog("/original/directory", { dismisses++ }, { path -> submitted += path; Result.failure(IllegalStateException("fixture directory error")) }, LocalMederiColors.current)
        } }
        onNode(hasSetTextAction()).assertTextContains("/original/directory").performTextReplacement("  /new/path with spaces  ")
        onNodeWithText(save).performScrollTo().performClick()
        onNodeWithText("fixture directory error").assertExists()
        onNodeWithText(cancel).performScrollTo().performClick()
        runOnIdle { assertEquals(listOf("/new/path with spaces"), submitted); assertEquals(1, dismisses) }
    }

    @Test
    fun uninstallUsesCommonConfirmationRetainsFailureAndPreventsRepeatedRemoval() = runComposeUiTest {
        val results = Channel<Result<Unit>>(Channel.UNLIMITED); var removes = 0; var dismisses = 0
        val open = mutableStateOf(true); var remove = ""; var busy = ""; var cancel = ""; var message = ""
        setContent { AppTheme {
            remove = stringResource(Res.string.skill_uninstall_confirm_action); busy = stringResource(Res.string.skill_deleting)
            cancel = stringResource(Res.string.cancel); message = stringResource(Res.string.skill_uninstall_confirm, "exact-skill-name")
            if (open.value) UninstallSkillConfirmDialog(SkillItem("exact-skill-name", "Fixture"), false,
                { dismisses++; open.value = false }, { removes++; results.receive() }, LocalMederiColors.current)
        } }
        onNodeWithText(message).assertExists()
        onNodeWithText(remove).performClick()
        waitUntil { removes == 1 }
        onNodeWithText(busy).assertIsNotEnabled().performTouchInput { click() }
        onNodeWithText(cancel).assertIsNotEnabled()
        results.trySend(Result.failure(IllegalStateException("fixture uninstall failed")))
        onNodeWithText("fixture uninstall failed").assertExists()
        runOnIdle { assertEquals(0, dismisses); assertEquals(1, removes) }
        onNodeWithText(remove).performScrollTo().performClick()
        waitUntil { removes == 2 }; results.trySend(Result.success(Unit))
        waitUntil { dismisses == 1 }
    }

    @Test
    fun confirmationCancelAndExternalBusyNeverPerformRemoval() = runComposeUiTest {
        var removes = 0; var dismisses = 0; val externalBusy = mutableStateOf(true); var remove = ""; var cancel = ""; var busy = ""
        setContent { AppTheme {
            remove = stringResource(Res.string.skill_uninstall_confirm_action); busy = stringResource(Res.string.skill_deleting)
            cancel = stringResource(Res.string.cancel)
            UninstallSkillConfirmDialog(SkillItem("fixture", ""), externalBusy.value, { dismisses++ },
                { removes++; Result.success(Unit) }, LocalMederiColors.current)
        } }
        onNodeWithText(busy).assertIsNotEnabled(); onNodeWithText(cancel).assertIsNotEnabled()
        runOnIdle { externalBusy.value = false }
        onNodeWithText(remove).assertIsEnabled()
        onNodeWithText(cancel).performClick()
        runOnIdle { assertEquals(0, removes); assertEquals(1, dismisses) }
    }

    @Test
    fun narrowLargeFontFormKeepsErrorPickerAndActionsReachable() = runComposeUiTest {
        var picks = 0; var saves = 0; var cancels = 0; var cancel = ""
        setContent { AppTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                cancel = stringResource(Res.string.cancel)
                MederiDialogContent(width = 280.dp) {
                    TextSubmissionContent("Configure directory", "A helpful explanation. ".repeat(8), "/long/path/".repeat(10), {},
                        "Directory", "Save directory", "Saving", false, "A complete failure message. ".repeat(8),
                        { saves++ }, { cancels++ }, onPick = { picks++ }, pickLabel = "Choose directory")
                }
            }
        } }
        onNodeWithText("Choose directory").performScrollTo().assertIsDisplayed().performClick()
        onNodeWithText("Save directory").performScrollTo().assertIsDisplayed().performClick()
        onNodeWithText(cancel).performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, picks); assertEquals(1, saves); assertEquals(1, cancels) }
    }

    @Test
    fun renderActualSharedManagementFormInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK)
        setContent { AppTheme(mode.value) {
            Row(Modifier.width(1000.dp).height(620.dp).background(LocalMederiColors.current.surfaceWorkspace)
                .testTag("managementDialogsPreview"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MederiDialogContent(Modifier.weight(1f), width = 480.dp) {
                    TextSubmissionContent(stringResource(Res.string.skill_install), stringResource(Res.string.skill_install_prompt),
                        "https://example.invalid/skills/research.zip", {}, stringResource(Res.string.skill_download_url),
                        stringResource(Res.string.skill_install_action), stringResource(Res.string.skill_installing), false,
                        null, {}, {}, placeholder = stringResource(Res.string.skill_url_placeholder))
                }
                MederiDialogContent(Modifier.weight(1f), width = 480.dp) {
                    TextSubmissionContent("MCP", stringResource(Res.string.mcp_config_hint), DEFAULT_MCP_TEMPLATE, {},
                        stringResource(Res.string.mcp_configuration_json), stringResource(Res.string.mcp_save),
                        stringResource(Res.string.mcp_saving), false, "Fixture: server command could not be verified.", {}, {}, singleLine = false)
                }
            }
        } }
        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) {
            runOnIdle { mode.value = theme }
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("managementDialogsPreview").captureToImage().toAwtImage(), "png",
                    File(directory, "management-dialogs-${theme.name.lowercase()}.png"))
            }
        }
    }
}
