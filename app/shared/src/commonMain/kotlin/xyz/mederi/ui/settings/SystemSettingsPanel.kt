package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Info
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.AppInfo
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.components.atoms.CopyButton

/**
 * 系统与运行环境信息面板。
 * 遵循系统设置规范：将零散信息收敛为统一的分组卡片，避免零散大长条铺满。
 */
@Composable
fun SystemSettingsPanel(isCompact: Boolean = false) {
    val colors = LocalMederiColors.current
    val scroll = rememberScrollState()

    val items = listOf(
        Triple(stringResource(Res.string.settings_system_version), AppInfo.VERSION, FeatherIcons.Info),
        Triple(stringResource(Res.string.settings_system_config_dir), "~/.mederi/", FeatherIcons.Folder),
        Triple(stringResource(Res.string.settings_system_prefs_file), "~/.mederi/preferences.json", FeatherIcons.FileText)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsCard(colors = colors, padding = PaddingValues(0.dp)) {
            items.forEachIndexed { index, (label, value, icon) ->
                if (index > 0) {
                    HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    ) {
                        SettingsIconBadge(
                            icon = icon,
                            tint = colors.accentPrimary,
                            size = 32.dp,
                            iconSize = 16.dp
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = label,
                                color = colors.textMuted,
                                fontSize = 11.5.sp
                            )
                            Text(
                                text = value,
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    CopyButton(text = value, size = 28)
                }
            }
        }
    }
}
