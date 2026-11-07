package xyz.mederi.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.RefreshCw
import kotlinx.coroutines.delay
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.status_retry_in
import mederi.app.shared.generated.resources.status_retrying_progress
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.TurnStatus
import xyz.mederi.ui.parseRetryHint

/** 仅显示重试次数、随机等待倒计时与供应商原因；恢复响应即收起。 */
@Composable
fun StatusBar(
    status: TurnStatus,
    statusHint: String? = null,
    modifier: Modifier = Modifier,
) {
    if (!status.shouldDisplayInStatusBar) return
    val colors = LocalMederiColors.current
    val retryHint = parseRetryHint(statusHint)
    var now by remember(statusHint) { mutableLongStateOf(xyz.mederi.currentTimeMillis()) }
    LaunchedEffect(statusHint) {
        while (true) {
            delay(1000)
            now = xyz.mederi.currentTimeMillis()
        }
    }
    val countdown = retryHint?.retryAtMillis?.let { ((it - now + 999) / 1000).coerceAtLeast(0) }
    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                FeatherIcons.RefreshCw, contentDescription = null, tint = colors.accentWarning,
                modifier = Modifier.size(WorkspaceStyle.IconSize).align(Alignment.CenterVertically),
            )
            Text(
                text = stringResource(Res.string.status_retrying_progress, retryHint?.attempt ?: "?"),
                color = colors.textPrimary,
                style = WorkspaceStyle.Title,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            if (countdown != null && countdown > 0) {
                Text(
                    text = stringResource(Res.string.status_retry_in, countdown),
                    color = colors.textSecondary,
                    style = WorkspaceStyle.Caption,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
        retryHint?.serverMsg?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                color = colors.textSecondary,
                style = WorkspaceStyle.Caption,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp),
            )
        }
    }
}
