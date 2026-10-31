package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Moon
import compose.icons.feathericons.Sun
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.*
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.components.atoms.WorkspaceNavigationTab

/** Route只接现有设备偏好，不复制主题/语言状态或改变持久化。 */
@Composable
fun GeneralSettingsPanel(isCompact: Boolean = false) {
    val appState = LocalAppState.current
    val theme by appState.theme.collectAsState()
    val language by appState.language.collectAsState()
    val reducedMotion by appState.reducedMotion.collectAsState()
    GeneralSettingsContent(theme, language, appState::setTheme, appState::setLanguage,
        reducedMotion = reducedMotion, onReducedMotionChange = appState::setReducedMotion)
}

@Composable
internal fun GeneralSettingsContent(
    theme: AppThemeMode,
    language: AppLanguage,
    onThemeChange: (AppThemeMode) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    onReducedMotionChange: (Boolean) -> Unit = {},
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(28.dp)) {
        SettingsSection(stringResource(Res.string.settings_appearance_title)) {
            FlowRow(Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppThemeMode.entries.forEach { option ->
                    WorkspaceNavigationTab(
                        stringResource(if (option == AppThemeMode.DARK) Res.string.theme_dark else Res.string.theme_light),
                        theme == option, { onThemeChange(option) },
                        icon = if (option == AppThemeMode.DARK) FeatherIcons.Moon else FeatherIcons.Sun,
                        role = Role.RadioButton,
                    )
                }
            }
        }
        SettingsSection(stringResource(Res.string.settings_motion_title), stringResource(Res.string.settings_motion_desc)) {
            SegmentedPillToggle(if (reducedMotion) 1 else 0,
                listOf(stringResource(Res.string.settings_motion_standard), stringResource(Res.string.settings_motion_reduced)),
                { onReducedMotionChange(it == 1) })
        }
        SettingsSection(stringResource(Res.string.settings_language_title), stringResource(Res.string.settings_language_desc)) {
            FlowRow(Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { option ->
                    WorkspaceNavigationTab(
                        if (option == AppLanguage.SYSTEM) stringResource(Res.string.language_system) else option.nativeName,
                        language == option, { onLanguageChange(option) }, icon = FeatherIcons.Globe, role = Role.RadioButton,
                    )
                }
            }
        }
    }
}
