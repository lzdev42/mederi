package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.AppInfo
import xyz.mederi.theme.*
import xyz.mederi.ui.components.atoms.CopyFeedbackScope
import xyz.mederi.ui.components.atoms.rememberCopyFeedback
import xyz.mederi.ui.components.atoms.WorkspaceActionButton

@Composable
fun SystemSettingsPanel(isCompact: Boolean = false) {
    val items = listOf(
        stringResource(Res.string.settings_system_version) to AppInfo.VERSION,
        stringResource(Res.string.settings_system_config_dir) to "~/.mederi/",
        stringResource(Res.string.settings_system_prefs_file) to "~/.mederi/preferences.json",
    )
    val feedback = rememberCopyFeedback()
    var copiedValue by remember { mutableStateOf<String?>(null) }
    CopyFeedbackScope(feedback) { copied ->
        SystemSettingsContent(items, { copiedValue = it; feedback.copy(it) },
            copiedValue = if (copied) copiedValue else null)
    }
}

/** 完整值可选择和复制，长路径先换行而不挤掉动作；反馈由调用层提供。 */
@Composable
internal fun SystemSettingsContent(
    items: List<Pair<String, String>>,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    copiedValue: String? = null,
) {
    val colors = LocalMederiColors.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items.forEach { (label, value) ->
            SettingsCopyableValue(label, value, onCopy, copiedValue == value)
        }
    }
}
