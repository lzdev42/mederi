package xyz.mederi.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.Copy
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Shield
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.*
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.ErrorBoard
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceActionSurface
import xyz.mederi.ui.components.atoms.WorkspaceActionTone
import xyz.mederi.ui.components.atoms.WorkspaceLoadingIndicator

/** 仅装配所选 OAuth 供应商的详情；添加入口属于供应商目录。 */
@Composable
internal fun ChatGptAuthorizationPanel(
    vm: ChatGptAuthorizationViewModel,
    provider: ProviderItemUiState,
    openBrowser: (String) -> Boolean,
) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(provider.id) { vm.inspect(provider.id) }
    DisposableEffect(vm) { onDispose { vm.stopObserving() } }
    LaunchedEffect(provider.syncStatus) {
        if (provider.syncStatus is ModelsSyncStatus.Success && vm.auth?.status == "CONNECTED") vm.inspect(provider.id)
    }
    ChatGptAuthorizationContent(vm.auth, vm.usage, vm.busy, vm.browserOpenFailed,
        onLogin = { vm.start(provider.id, {}, openBrowser) },
        onOpen = { vm.auth?.authUrl?.let { vm.openAuthorization(it, openBrowser) } },
        onCopy = { vm.auth?.authUrl?.let { clipboard.setText(AnnotatedString(it)) } },
        onCancel = vm::cancel, onDisconnect = vm::disconnect, onRefresh = vm::refreshUsage,
        onManage = { if (!openBrowser(CHATGPT_USAGE_URL)) runCatching { uriHandler.openUri(CHATGPT_USAGE_URL) } },
        error = vm.error)
}

/** 详情中的单一连接区，跟随模型列表一起滚动，不在设置页顶部另占固定高度。 */
@Composable
internal fun ChatGptAuthorizationContent(
    state: ChatGptAuthState?, usage: ChatGptUsage?, busy: Boolean,
    browserOpenFailed: Boolean,
    onLogin: () -> Unit, onOpen: () -> Unit, onCopy: () -> Unit, onCancel: () -> Unit,
    onDisconnect: () -> Unit, onRefresh: () -> Unit, onManage: () -> Unit,
    error: String? = null, modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val connected = state?.status == "CONNECTED"
    val pending = state?.status == "PENDING"
    WorkspaceActionSurface(modifier.fillMaxWidth(), tone = WorkspaceActionTone.Neutral) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (connected) FeatherIcons.CheckCircle else FeatherIcons.Shield, null,
                Modifier.size(20.dp), tint = if (connected) colors.accentText else colors.textSecondary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(Res.string.chatgpt_account_connection), style = WorkspaceStyle.Title, color = colors.textPrimary)
                Text(state?.email ?: stringResource(Res.string.chatgpt_catalog_description),
                    style = WorkspaceStyle.Caption, color = colors.textSecondary)
            }
        }
        if (state == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkspaceLoadingIndicator(Modifier.size(WorkspaceStyle.IconSize), strokeWidth = 2.dp)
            Text(stringResource(Res.string.chatgpt_loading), style = WorkspaceStyle.Caption, color = colors.textSecondary)
        } else {
            Text(stringResource(when (state.status) {
                "CONNECTED" -> Res.string.chatgpt_connected
                "PENDING" -> Res.string.chatgpt_pending
                "FAILED" -> Res.string.chatgpt_failed
                else -> Res.string.chatgpt_signed_out
            }), style = WorkspaceStyle.Body, color = colors.textPrimary)
            if (pending) {
                Text(stringResource(Res.string.chatgpt_browser_continue_hint),
                    style = WorkspaceStyle.Caption, color = colors.textSecondary)
                if (browserOpenFailed) Text(stringResource(Res.string.chatgpt_browser_open_failed),
                    style = WorkspaceStyle.Body, color = colors.textPrimary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    pending -> {
                        WorkspaceActionButton(stringResource(Res.string.chatgpt_open_browser), onOpen,
                            primary = true, enabled = !busy, icon = FeatherIcons.ExternalLink)
                        WorkspaceActionButton(stringResource(Res.string.chatgpt_copy_link), onCopy, enabled = state.authUrl != null,
                            icon = FeatherIcons.Copy)
                        WorkspaceActionButton(stringResource(Res.string.chatgpt_cancel), onCancel, enabled = !busy)
                    }
                    connected -> WorkspaceActionButton(stringResource(Res.string.chatgpt_sign_out), onDisconnect, enabled = !busy)
                    else -> WorkspaceActionButton(stringResource(Res.string.chatgpt_reconnect), onLogin,
                        primary = true, enabled = !busy, icon = FeatherIcons.ExternalLink)
                }
            }
            if (connected) {
                Text(stringResource(Res.string.chatgpt_plan_in_use), style = WorkspaceStyle.Caption, color = colors.textSecondary)
                if (usage?.windows.isNullOrEmpty()) Text(stringResource(Res.string.chatgpt_usage_unavailable),
                    style = WorkspaceStyle.Caption, color = colors.textSecondary)
                else {
                    ChatGptUsageWindowText(usage.windows.firstOrNull { it.windowDurationMins == 300 }, Res.string.chatgpt_five_hour)
                    ChatGptUsageWindowText(usage.windows.firstOrNull { it.windowDurationMins == 10080 }, Res.string.chatgpt_weekly)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WorkspaceActionButton(stringResource(Res.string.chatgpt_manage_usage), onManage, icon = FeatherIcons.ExternalLink)
                    if (!usage?.windows.isNullOrEmpty()) WorkspaceActionButton(stringResource(Res.string.chatgpt_refresh_usage), onRefresh, enabled = !busy)
                }
            }
            state.error?.let { ErrorBoard(stringResource(Res.string.chatgpt_error, it)) }
            state.modelSyncError?.let { ErrorBoard(stringResource(Res.string.chatgpt_model_sync_failed, it)) }
        }
        error?.let { ErrorBoard(stringResource(Res.string.chatgpt_error, it)) }
    }
}

@Composable
private fun ChatGptUsageWindowText(window: ChatGptUsageWindow?, label: org.jetbrains.compose.resources.StringResource) {
    val colors = LocalMederiColors.current
    val name = stringResource(label)
    val remaining = window?.remainingPercent
    Text(if (remaining == null) stringResource(Res.string.chatgpt_window_not_provided, name)
        else stringResource(Res.string.chatgpt_window_remaining, name, remaining.toInt()),
        style = WorkspaceStyle.Caption, color = colors.textSecondary)
    window?.resetsAt?.takeIf { it in 0..Long.MAX_VALUE / 1000 }?.let {
        val reset = xyz.mederi.util.TimeFormatter.formatFullDateTime(it * 1000)
        if (reset.isNotBlank()) Text(stringResource(Res.string.chatgpt_window_resets, reset), style = WorkspaceStyle.Caption, color = colors.textSecondary)
    }
}
