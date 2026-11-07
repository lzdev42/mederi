package xyz.mederi.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
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

/** 紧凑单行错误提示：摘要省略，详情/继续/关闭同一行，无卡片边框与大面积底色。 */
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
    // 过程提示使用工作台 30dp 紧凑控件，不让 Material 默认 48dp 热区撑高整条。
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides WorkspaceStyle.ControlHeight) {
        Row(
            modifier = modifier.fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .then(if (onShowDetail != null) Modifier.clickable(role = Role.Button, onClick = onShowDetail) else Modifier)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(FeatherIcons.AlertCircle, null, tint = colors.accentDanger,
                modifier = Modifier.size(WorkspaceStyle.IconSize))
            Text(
                text = errorMessage,
                color = colors.textSecondary,
                style = WorkspaceStyle.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onShowDetail != null) WorkspaceActionButton(
                text = stringResource(Res.string.errorboard_detail_report),
                onClick = onShowDetail,
            )
            if (showContinue && onContinue != null) WorkspaceActionButton(
                text = continueLabel ?: stringResource(Res.string.errorboard_continue),
                onClick = onContinue,
                primary = true,
            )
            if (onDismiss != null) MederiMinimalIconButton(
                icon = FeatherIcons.X,
                onClick = onDismiss,
                contentDescription = stringResource(Res.string.errorboard_close),
                modifier = Modifier.size(WorkspaceStyle.IconTarget),
            )
        }
    }
}
