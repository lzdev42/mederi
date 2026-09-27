package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Trash2
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.SandboxHooks
import xyz.mederi.core.contract.SandboxStatusInfo
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.components.atoms.MederiPrimaryDecisionButton

/**
 * 执行沙盒安全状态与白名单设置面板。
 */
@Composable
fun SandboxSettingsPanel() {
    val appState = LocalAppState.current
    val colors = LocalMederiColors.current
    val scroll = rememberScrollState()
    val hooks = appState.aiCore as? SandboxHooks

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 沙盒状态卡
        SandboxStatusCard(hooks, colors)

        // 2. 全局白名单卡
        if (hooks != null) {
            SandboxWhitelistCard(appState, colors)
        } else {
            Text(
                text = stringResource(Res.string.settings_sandbox_remote_hint),
                color = colors.textMuted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun SandboxStatusCard(
    hooks: SandboxHooks?,
    colors: MederiColors
) {
    var status by remember(hooks) { mutableStateOf<SandboxStatusInfo?>(null) }
    LaunchedEffect(hooks) { status = hooks?.sandboxStatus() }
    val isQuerying = hooks != null && status == null
    val isAvailable = status?.available == true

    SettingsCard(colors = colors) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsIconBadge(
                        icon = if (isAvailable) FeatherIcons.Shield else FeatherIcons.AlertCircle,
                        tint = if (isAvailable) colors.accentSuccess else colors.accentWarning,
                        size = 34.dp,
                        iconSize = 17.dp
                    )
                    Text(
                        text = when {
                            isQuerying -> stringResource(Res.string.settings_sandbox_querying)
                            status == null -> stringResource(Res.string.settings_sandbox_unknown)
                            status?.available == true -> stringResource(Res.string.settings_sandbox_enabled, status?.backend.orEmpty())
                            else -> stringResource(Res.string.settings_sandbox_unavailable)
                        },
                        color = colors.textPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 右侧 Shell 微型代码标签
                val shellText = status?.shell
                if (!shellText.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceWorkspace)
                            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "shell: $shellText",
                            color = colors.textMuted,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))

            Text(
                text = status?.detail ?: stringResource(Res.string.settings_sandbox_status_unavailable),
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            Text(
                text = stringResource(Res.string.settings_sandbox_write_scope),
                color = colors.textMuted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SandboxWhitelistCard(
    appState: xyz.mederi.ui.appstate.AppState,
    colors: MederiColors
) {
    val paths by appState.sandboxExtraPaths.collectAsState()
    var input by remember { mutableStateOf("") }

    SettingsCard(colors = colors) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(Res.string.settings_sandbox_whitelist_title),
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(Res.string.settings_sandbox_whitelist_desc),
                    color = colors.textMuted,
                    fontSize = 11.5.sp
                )
            }

            // 路径列表
            if (paths.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceWorkspace)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(Res.string.settings_sandbox_whitelist_empty),
                        color = colors.textMuted,
                        fontSize = 12.sp
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceWorkspace)
                        .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                ) {
                    paths.forEachIndexed { index, path ->
                        if (index > 0) {
                            HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = path,
                                color = colors.textPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = FeatherIcons.Trash2,
                                contentDescription = stringResource(Res.string.settings_remove),
                                tint = colors.accentDanger.copy(alpha = 0.8f),
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable { appState.setSandboxExtraPaths(paths - path) }
                            )
                        }
                    }
                }
            }

            // 添加输入栏（高度完全对齐 38dp，消除大输入框和矮按钮的不协调）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsInputField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(Res.string.settings_sandbox_whitelist_placeholder),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.weight(1f),
                    colors = colors
                )
                MederiPrimaryDecisionButton(
                    text = stringResource(Res.string.settings_add),
                    onClick = {
                        val p = input.trim()
                        if (p.isNotEmpty() && p !in paths) {
                            appState.setSandboxExtraPaths(paths + p)
                            input = ""
                        }
                    },
                    enabled = input.trim().isNotEmpty(),
                    icon = FeatherIcons.Plus
                )
            }
        }
    }
}
