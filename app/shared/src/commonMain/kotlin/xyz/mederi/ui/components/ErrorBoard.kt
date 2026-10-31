package xyz.mederi.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.errorboard_close
import mederi.app.shared.generated.resources.errorboard_continue
import mederi.app.shared.generated.resources.errorboard_detail_report
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.MederiMinimalIconButton
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceActionSurface
import xyz.mederi.ui.components.atoms.WorkspaceActionTone

/** 错误与恢复入口：中性底，语义色只用于边界与图标；详情/继续/关闭各保留原回调。 */
@Composable
fun ErrorBoard(
    errorMessage: String?,
    onShowDetail: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    showContinue: Boolean = false,
    onContinue: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    continueLabel: String? = null,
) {
    if (errorMessage == null) return
    val colors = LocalMederiColors.current
    WorkspaceActionSurface(
        modifier = modifier.fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .then(if (onShowDetail != null) Modifier.clickable(role = Role.Button, onClick = onShowDetail) else Modifier),
        tone = WorkspaceActionTone.Danger,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(FeatherIcons.AlertCircle, null, tint = colors.accentDanger,
                modifier = Modifier.size(WorkspaceStyle.IconSize))
            Text(
                text = errorMessage,
                color = colors.textPrimary,
                style = WorkspaceStyle.Body,
                maxLines = if (onShowDetail == null) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) MederiMinimalIconButton(
                icon = FeatherIcons.X,
                onClick = onDismiss,
                contentDescription = stringResource(Res.string.errorboard_close),
                modifier = Modifier.size(WorkspaceStyle.IconTarget),
            )
        }
        if (onShowDetail != null || (showContinue && onContinue != null)) FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (onShowDetail != null) WorkspaceActionButton(
                text = stringResource(Res.string.errorboard_detail_report),
                onClick = onShowDetail,
            )
            if (showContinue && onContinue != null) {
                WorkspaceActionButton(
                    text = continueLabel ?: stringResource(Res.string.errorboard_continue),
                    onClick = onContinue,
                    primary = true,
                )
            }
        }
    }
}
