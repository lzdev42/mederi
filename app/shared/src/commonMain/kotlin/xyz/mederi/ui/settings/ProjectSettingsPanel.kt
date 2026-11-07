package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.WorkspaceIconButton

/** 由窗口 Route 叠加，复用设置壳；窄屏占满可用区域而非塞进侧边栏。 */
@Composable
internal fun ProjectSettingsPanel(projectId: String, onClose: () -> Unit) {
    val colors = LocalMederiColors.current
    SettingsOverlay(onClose) { panelModifier ->
        Column(panelModifier.onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onClose(); true } else false
        }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = MederiSpacing.LG, vertical = MederiSpacing.SM),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.project_settings_title), color = colors.textPrimary,
                    style = WorkspaceStyle.Title, modifier = Modifier.weight(1f))
                WorkspaceIconButton(FeatherIcons.X, onClose, stringResource(Res.string.close))
            }
            SandboxSettingsPanel(projectId, Modifier.weight(1f).fillMaxWidth()
                .padding(horizontal = MederiSpacing.LG, vertical = MederiSpacing.MD))
        }
    }
}
