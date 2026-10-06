package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.appstate.RemoteServerUiState
import xyz.mederi.ui.appstate.TunnelUiState
import xyz.mederi.ui.components.atoms.CopyButton
import xyz.mederi.ui.components.atoms.MederiPrimaryDecisionButton
import xyz.mederi.ui.components.atoms.MederiSurfaceButton

/**
 * 远程遥控与 Cloudflare 隧道设置面板。
 */
@Composable
fun RemoteSettingsPanel(isCompact: Boolean = false) {
    val appState = LocalAppState.current
    val colors = LocalMederiColors.current
    val scroll = rememberScrollState()
    val hooks = appState.remoteControl

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (hooks != null) {
            RemoteControlCard(appState, colors, isCompact)
        } else {
            SettingsCard(colors = colors) {
                Text(
                    text = stringResource(Res.string.settings_remote_unsupported_platform),
                    color = colors.textMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun RemoteControlCard(
    appState: xyz.mederi.ui.appstate.AppState,
    colors: MederiColors,
    isCompact: Boolean
) {
    val enabled by appState.remoteControlEnabled.collectAsState()
    val savedPassword by appState.remoteControlPassword.collectAsState()
    val serverState by appState.remoteServerState.collectAsState()
    val tunnelState by appState.tunnelState.collectAsState()
    val hooks = appState.remoteControl ?: return
    val localAddr = hooks.localAddress

    var password by remember { mutableStateOf(savedPassword ?: "") }
    var showPassword by remember { mutableStateOf(false) }

    SettingsCard(colors = colors) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // 开关行
            SettingsRow(
                title = stringResource(Res.string.settings_remote_title),
                subtitle = when (serverState) {
                    is RemoteServerUiState.Idle -> stringResource(Res.string.settings_remote_state_idle)
                    is RemoteServerUiState.Starting -> stringResource(Res.string.settings_remote_state_starting)
                    is RemoteServerUiState.Running -> stringResource(Res.string.settings_remote_state_running)
                    is RemoteServerUiState.Failed -> stringResource(Res.string.settings_remote_state_failed)
                },
                icon = FeatherIcons.Sliders,
                iconTint = colors.accentPrimary,
                colors = colors
            ) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { on -> appState.setRemoteControl(on, password) }
                )
            }

            // 状态展示
            when (val ss = serverState) {
                is RemoteServerUiState.Running -> {
                    HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surfaceWorkspace)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(Res.string.settings_remote_lan_address_title),
                                color = colors.textMuted,
                                fontSize = 11.sp
                            )
                            val url = "http://${localAddr ?: "127.0.0.1"}:${ss.port}"
                            Text(
                                text = url,
                                color = colors.accentSuccess,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        CopyButton(
                            text = "http://${localAddr ?: "127.0.0.1"}:${ss.port}",
                            size = 28
                        )
                    }
                }
                is RemoteServerUiState.Failed -> {
                    val reasonText = stringResource(ss.reason.key, *ss.reason.args.toTypedArray())
                    Text(reasonText, color = colors.accentDanger, fontSize = 12.sp)
                }
                else -> {}
            }

            // 密码行：限制最大合理宽度（380dp），避免 780dp 超长拉伸
            if (enabled && serverState !is RemoteServerUiState.Starting) {
                HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.settings_remote_password_label),
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    SettingsInputField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = stringResource(Res.string.settings_remote_password_placeholder),
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Icon(
                                imageVector = if (showPassword) FeatherIcons.EyeOff else FeatherIcons.Eye,
                                contentDescription = null,
                                tint = colors.textMuted,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { showPassword = !showPassword }
                            )
                        },
                        colors = colors
                    )
                    Text(
                        text = stringResource(Res.string.settings_remote_password_note),
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
            }

            // Cloudflare 隧道区块
            if (serverState is RemoteServerUiState.Running) {
                HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(Res.string.settings_tunnel_title),
                        color = colors.textPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    when (val ts = tunnelState) {
                        is TunnelUiState.Idle -> {
                            TunnelInputFields(appState, colors)
                            Text(stringResource(Res.string.settings_tunnel_desc), color = colors.textMuted, fontSize = 11.5.sp)
                            MederiPrimaryDecisionButton(
                                text = stringResource(Res.string.settings_tunnel_start),
                                onClick = { appState.startTunnel() }
                            )
                        }
                        is TunnelUiState.Starting -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.accentPrimary)
                                Text(stringResource(Res.string.settings_tunnel_starting), color = colors.textSecondary, fontSize = 12.sp)
                            }
                        }
                        is TunnelUiState.Running -> {
                            val url = ts.url
                            if (url != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.surfaceWorkspace)
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = stringResource(Res.string.settings_tunnel_public_url_title),
                                            color = colors.textMuted,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = url,
                                            color = colors.accentPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    CopyButton(text = url, size = 28)
                                }
                            }
                            MederiSurfaceButton(
                                text = stringResource(Res.string.settings_tunnel_stop),
                                onClick = { appState.stopTunnel() }
                            )
                        }
                        is TunnelUiState.Failed -> {
                            val msg = if (ts.notInstalled) stringResource(Res.string.settings_tunnel_not_installed)
                            else stringResource(Res.string.settings_tunnel_failed, stringResource(ts.reason.key, *ts.reason.args.toTypedArray()))
                            Text(msg, color = colors.accentDanger, fontSize = 12.sp)
                            // 失败时仍显示输入框，允许用户修改 token/domain 后重试
                            TunnelInputFields(appState, colors)
                            MederiPrimaryDecisionButton(
                                text = stringResource(Res.string.settings_retry),
                                onClick = { appState.startTunnel() }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Cloudflare 隧道的 token + 域名输入框（Idle 和 Failed 状态共用）。
 * Failed 时允许用户修改后重试，而不是只给一个重试按钮。
 */
@Composable
private fun TunnelInputFields(
    appState: xyz.mederi.ui.appstate.AppState,
    colors: MederiColors,
) {
    val token by appState.tunnelToken.collectAsState()
    val domain by appState.tunnelDomain.collectAsState()
    var showToken by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(Res.string.settings_tunnel_token_label),
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        SettingsInputField(
            value = token,
            onValueChange = { appState.setTunnelToken(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(Res.string.settings_tunnel_token_placeholder),
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Icon(
                    imageVector = if (showToken) FeatherIcons.EyeOff else FeatherIcons.Eye,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(16.dp).clickable { showToken = !showToken }
                )
            },
            colors = colors
        )
        Text(
            text = stringResource(Res.string.settings_tunnel_token_note),
            color = colors.textMuted,
            fontSize = 11.sp
        )

        // 域名输入
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.settings_tunnel_domain_label),
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        SettingsInputField(
            value = domain,
            onValueChange = { appState.setTunnelDomain(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(Res.string.settings_tunnel_domain_placeholder),
            singleLine = true,
            colors = colors
        )
    }
}
