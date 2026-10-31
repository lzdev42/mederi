package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.*
import xyz.mederi.ui.settings.*
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SettingsFirstDeliveryTest {
    @Test
    fun sevenCategoriesForwardExactTargetsAndReflectSelection() = runComposeUiTest {
        val selected = mutableStateOf(SettingsTab.GENERAL)
        val choices = mutableListOf<SettingsTab>()
        val labels = mutableMapOf<SettingsTab, String>()
        setContent { AppTheme {
            SettingsTab.entries.forEach { labels[it] = settingsTabLabel(it) }
            SettingsWorkspaceContent(selected.value, { selected.value = it; choices += it }, {}, Modifier.width(920.dp).height(640.dp)) { tab, _ ->
                Text("Content ${tab.name}")
            }
        } }
        for (tab in SettingsTab.entries) {
            onNode(hasText(labels.getValue(tab)) and isSelectable()).performClick().assertIsSelected()
            onNodeWithText("Content ${tab.name}").assertIsDisplayed()
        }
        runOnIdle { assertEquals(SettingsTab.entries.toList(), choices) }
    }

    @Test
    fun compactAndLargeFontKeepCloseAndNavigationReachable() = runComposeUiTest {
        val selected = mutableStateOf(SettingsTab.GENERAL); var closes = 0; var close = ""; var system = ""
        setContent { AppTheme {
            close = stringResource(Res.string.close); system = settingsTabLabel(SettingsTab.SYSTEM)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                SettingsWorkspaceContent(selected.value, { selected.value = it }, { closes++ }, Modifier.width(320.dp).height(600.dp)) { _, compact ->
                    Text(if (compact) "Compact content" else "Wide content")
                }
            }
        } }
        onNodeWithText("Compact content").assertIsDisplayed()
        onNode(hasText(system) and isSelectable()).performScrollTo().performClick().assertIsSelected()
        onNodeWithContentDescription(close).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun categoryKeyboardSelectionAndEscapeRemainSeparate() = runComposeUiTest {
        val selected = mutableStateOf(SettingsTab.GENERAL); var closes = 0; var system = ""
        setContent { AppTheme {
            system = settingsTabLabel(SettingsTab.SYSTEM)
            SettingsWorkspaceContent(selected.value, { selected.value = it }, { closes++ }, Modifier.width(920.dp).height(640.dp)) { _, _ -> }
        } }
        val target = onNode(hasText(system) and isSelectable())
        target.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        target.performKeyInput { pressKey(Key.Enter) }
        target.assertIsSelected()
        target.performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertEquals(1, closes); assertEquals(SettingsTab.SYSTEM, selected.value) }
    }

    @Test
    fun contentAndBlankPanelDoNotDismissButBackdropDoes() = runComposeUiTest {
        var closes = 0
        setContent { AppTheme {
            Box(Modifier.width(900.dp).height(600.dp).testTag("host")) {
                SettingsOverlay({ closes++ }) { modifier ->
                    Column(modifier.testTag("panel")) { Text("Inside the settings panel") }
                }
            }
        } }
        onNodeWithText("Inside the settings panel").performTouchInput { click() }
        onNodeWithTag("panel").performTouchInput { click() }
        runOnIdle { assertEquals(0, closes) }
        onNodeWithTag("host").performTouchInput { click(Offset(2f, 2f)) }
        runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun themeAndAllLanguagesPreserveExactValuesWithoutLocalConfigurationCopies() = runComposeUiTest {
        val theme = mutableStateOf(AppThemeMode.DARK); val language = mutableStateOf(AppLanguage.SYSTEM)
        val languages = mutableListOf<AppLanguage>(); val themes = mutableListOf<AppThemeMode>()
        var light = ""; var system = ""
        setContent { AppTheme {
            light = stringResource(Res.string.theme_light); system = stringResource(Res.string.language_system)
            GeneralSettingsContent(theme.value, language.value,
                { theme.value = it; themes += it }, { language.value = it; languages += it }, Modifier.width(320.dp).height(600.dp))
        } }
        onNodeWithText(light).performClick().assertIsSelected()
        for (option in AppLanguage.entries) {
            onNodeWithText(if (option == AppLanguage.SYSTEM) system else option.nativeName)
                .performScrollTo().performClick().assertIsSelected()
        }
        runOnIdle { assertEquals(listOf(AppThemeMode.LIGHT), themes); assertEquals(AppLanguage.entries.toList(), languages) }
        runOnIdle { language.value = AppLanguage.ZH }
        onNodeWithText(AppLanguage.ZH.nativeName).performScrollTo().assertIsSelected()
    }

    @Test
    fun narrowLargeFontSystemValuesAreCompleteAndCopyIsExact() = runComposeUiTest {
        val value = "/home/someone/a long workspace directory/configuration/preferences.json"
        val copies = mutableListOf<String>(); var copy = ""; var done = ""
        val copied = mutableStateOf<String?>(null)
        setContent { AppTheme {
            copy = stringResource(Res.string.copy); done = stringResource(Res.string.copy_done)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                SystemSettingsContent(listOf("Preferences file" to value), { copies += it; copied.value = it },
                    Modifier.width(280.dp).height(600.dp), copied.value)
            }
        } }
        onNodeWithText(value).assertIsDisplayed()
        onNodeWithText(copy).performClick()
        onNodeWithText(done).assertIsDisplayed()
        runOnIdle { assertEquals(listOf(value), copies) }
    }

    @Test
    fun responsiveSettingsRowDoesNotLoseLongDescriptionOrAction() = runComposeUiTest {
        var actions = 0
        setContent { AppTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(280.dp)) {
                    SettingsRow("A long setting name", "This description wraps without hiding the operation") {
                        WorkspaceActionButton("Configure", { actions++ })
                    }
                }
            }
        } }
        onNodeWithText("This description wraps without hiding the operation").assertIsDisplayed()
        onNodeWithText("Configure").performClick()
        runOnIdle { assertEquals(1, actions) }
    }

    @Test
    fun renderActualSettingsShellAndSamplePagesInBothThemes() = runComposeUiTest {
        val mode = mutableStateOf(AppThemeMode.DARK); val selected = mutableStateOf(SettingsTab.GENERAL)
        setContent { AppTheme(mode.value) {
            SettingsWorkspaceContent(selected.value, { selected.value = it }, {},
                Modifier.width(960.dp).height(720.dp).testTag("settingsSample")) { tab, _ ->
                if (tab == SettingsTab.SYSTEM) SystemSettingsContent(listOf("Version" to "1.0", "Preferences" to "~/.mederi/preferences.json"), {})
                else GeneralSettingsContent(mode.value, AppLanguage.SYSTEM, {}, {})
            }
        } }
        for (theme in listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)) for (tab in listOf(SettingsTab.GENERAL, SettingsTab.SYSTEM)) {
            runOnIdle { mode.value = theme; selected.value = tab }
            System.getenv("MEDERI_UI_PREVIEW_DIR")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                ImageIO.write(onNodeWithTag("settingsSample").captureToImage().toAwtImage(), "png",
                    File(directory, "settings-${tab.name.lowercase()}-${theme.name.lowercase()}.png"))
            }
        }
    }
}
