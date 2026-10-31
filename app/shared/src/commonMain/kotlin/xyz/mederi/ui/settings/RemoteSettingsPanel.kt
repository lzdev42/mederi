package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.*
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.appstate.RemoteServerUiState
import xyz.mederi.ui.appstate.TunnelUiState
import xyz.mederi.ui.components.ErrorBoard
import xyz.mederi.ui.components.atoms.*

/** 宿主能力和运行事实来自AppState；关闭页面没有服务副作用。 */
@Composable
fun RemoteSettingsPanel(isCompact: Boolean = false) {
    val appState = LocalAppState.current
    val enabled by appState.remoteControlEnabled.collectAsState()
    val password by appState.remoteControlPassword.collectAsState()
    val server by appState.remoteServerState.collectAsState()
    val tunnel by appState.tunnelState.collectAsState()
    val token by appState.tunnelToken.collectAsState()
    val domain by appState.tunnelDomain.collectAsState()
    val copy = rememberCopyFeedback()
    var copiedValue by remember { mutableStateOf<String?>(null) }
    CopyFeedbackScope(copy) { copied ->
        RemoteSettingsContent(RemoteSettingsUiState(appState.remoteControl != null, enabled, password,
            server, tunnel, token, domain, appState.remoteControl?.localAddress),
            RemoteSettingsActions(appState::setRemoteControl, appState::setTunnelToken, appState::setTunnelDomain,
                appState::startTunnel, appState::stopTunnel, { appState.setRemoteControl(true, it) },
                { copiedValue = it; copy.copy(it) }), copiedValue = if (copied) copiedValue else null)
    }
}

internal data class RemoteSettingsUiState(
    val supported: Boolean,
    val enabled: Boolean,
    val savedPassword: String?,
    val server: RemoteServerUiState,
    val tunnel: TunnelUiState,
    val token: String,
    val domain: String,
    val localAddress: String?,
)

internal data class RemoteSettingsActions(
    val toggle: (Boolean, String?) -> Unit,
    val token: (String) -> Unit,
    val domain: (String) -> Unit,
    val startTunnel: () -> Unit,
    val stopTunnel: () -> Unit,
    val retryServer: (String) -> Unit,
    val copy: (String) -> Unit,
)

@Composable
internal fun RemoteSettingsContent(
    state: RemoteSettingsUiState,
    actions: RemoteSettingsActions,
    modifier: Modifier = Modifier,
    copiedValue: String? = null,
) {
    val colors = LocalMederiColors.current
    var password by remember { mutableStateOf(state.savedPassword.orEmpty()) }
    var passwordEdited by remember { mutableStateOf(false) }
    // 初始偏好水合可以填入字段；之后的状态回流不覆盖用户正在编辑的密码草稿。
    LaunchedEffect(state.savedPassword) {
        if (!passwordEdited || password == state.savedPassword.orEmpty()) {
            password = state.savedPassword.orEmpty(); passwordEdited = false
        }
    }
    Column(modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (!state.supported) {
            Text(stringResource(Res.string.settings_remote_unsupported_platform), style = WorkspaceStyle.Body, color = colors.textSecondary)
        } else {
            SettingsRow(stringResource(Res.string.settings_remote_title), when (state.server) {
                RemoteServerUiState.Idle -> stringResource(Res.string.settings_remote_state_idle)
                RemoteServerUiState.Starting -> stringResource(Res.string.settings_remote_state_starting)
                is RemoteServerUiState.Running -> stringResource(Res.string.settings_remote_state_running)
                is RemoteServerUiState.Failed -> stringResource(Res.string.settings_remote_state_failed)
            }, icon = FeatherIcons.Sliders) {
                MederiMiniSwitch(state.enabled, { actions.toggle(it, password) },
                    contentDescription = stringResource(Res.string.settings_remote_title))
            }
            when (val server = state.server) {
                RemoteServerUiState.Starting -> WorkspaceLoadingIndicator()
                is RemoteServerUiState.Failed -> ErrorBoard(stringResource(server.reason.key, *server.reason.args.toTypedArray()),
                    showContinue = true, onContinue = { actions.retryServer(password) }, continueLabel = stringResource(Res.string.settings_retry))
                is RemoteServerUiState.Running -> {
                    if (server.portFallback) Text(stringResource(Res.string.settings_remote_port_fallback, server.port),
                        style = WorkspaceStyle.Caption, color = colors.textSecondary)
                    if (!state.localAddress.isNullOrBlank()) {
                        val url = "http://${state.localAddress}:${server.port}"
                        SettingsCopyableValue(stringResource(Res.string.settings_remote_lan_address_title), url, actions.copy, copiedValue == url)
                    } else Text(stringResource(Res.string.settings_remote_no_lan), style = WorkspaceStyle.Body, color = colors.textSecondary)
                }
                else -> Unit
            }
            if (state.enabled && state.server !is RemoteServerUiState.Starting) {
                SettingsSecretField(stringResource(Res.string.settings_remote_password_label), password,
                    { password = it; passwordEdited = true }, stringResource(Res.string.settings_remote_password_placeholder),
                    stringResource(Res.string.settings_remote_password_note))
            }
            if (state.server is RemoteServerUiState.Running) {
                SettingsSection(stringResource(Res.string.settings_tunnel_title), stringResource(Res.string.settings_tunnel_desc)) {
                    when (val tunnel = state.tunnel) {
                        TunnelUiState.Idle, is TunnelUiState.Failed -> {
                            SettingsSecretField(stringResource(Res.string.settings_tunnel_token_label), state.token, actions.token,
                                stringResource(Res.string.settings_tunnel_token_placeholder), stringResource(Res.string.settings_tunnel_token_note))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(Res.string.settings_tunnel_domain_label), style = WorkspaceStyle.Caption, color = colors.textSecondary)
                                val domainLabel = stringResource(Res.string.settings_tunnel_domain_label)
                                WorkspaceTextField(state.domain, actions.domain, Modifier.fillMaxWidth().semantics { contentDescription = domainLabel },
                                    placeholder = stringResource(Res.string.settings_tunnel_domain_placeholder), monospace = true)
                            }
                            if (tunnel is TunnelUiState.Failed) {
                                val message = if (tunnel.notInstalled) stringResource(Res.string.settings_tunnel_not_installed)
                                else stringResource(Res.string.settings_tunnel_failed, stringResource(tunnel.reason.key, *tunnel.reason.args.toTypedArray()))
                                ErrorBoard(message, showContinue = true, onContinue = actions.startTunnel,
                                    continueLabel = stringResource(Res.string.settings_retry))
                            } else WorkspaceActionButton(stringResource(Res.string.settings_tunnel_start), actions.startTunnel, primary = true)
                        }
                        TunnelUiState.Starting -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WorkspaceLoadingIndicator()
                            Text(stringResource(Res.string.settings_tunnel_starting), style = WorkspaceStyle.Body, color = colors.textSecondary)
                        }
                        is TunnelUiState.Running -> {
                            Text(stringResource(Res.string.settings_tunnel_running), style = WorkspaceStyle.Body, color = colors.textPrimary)
                            if (!tunnel.url.isNullOrBlank()) SettingsCopyableValue(stringResource(Res.string.settings_tunnel_public_url_title),
                                tunnel.url, actions.copy, copiedValue == tunnel.url)
                            else Text(stringResource(Res.string.settings_tunnel_url_unknown), style = WorkspaceStyle.Caption, color = colors.textSecondary)
                            WorkspaceActionButton(stringResource(Res.string.settings_tunnel_stop), actions.stopTunnel, icon = FeatherIcons.Square)
                        }
                    }
                }
            }
        }
    }
}

/** 密码和Token复用同一字段，显隐仅是本地表现，不触发保存/重启。 */
@Composable
internal fun SettingsSecretField(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String, hint: String) {
    val colors = LocalMederiColors.current
    var reveal by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = WorkspaceStyle.Caption, color = colors.textSecondary)
        WorkspaceTextField(value, onValueChange, Modifier.fillMaxWidth().semantics { contentDescription = label }, placeholder = placeholder,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { WorkspaceIconButton(if (reveal) FeatherIcons.EyeOff else FeatherIcons.Eye,
                { reveal = !reveal }, stringResource(if (reveal) Res.string.settings_secret_hide else Res.string.settings_secret_show)) })
        Text(hint, style = WorkspaceStyle.Caption, color = colors.textSecondary)
    }
}
