package xyz.mederi.ui.components.atoms

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Check
import compose.icons.feathericons.Circle
import compose.icons.feathericons.MinusCircle
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle

/** 仅为图形表现分类；Todo与Plan各自保留领域状态及唯一数据源。 */
internal enum class WorkspaceTaskStepState { Pending, Running, Completed, Failed, Cancelled }

/** 中性任务步骤：状态不只靠颜色，标题自由换行，附属动作有独立热区。 */
@Composable
internal fun WorkspaceTaskStep(
    text: String,
    statusLabel: String,
    state: WorkspaceTaskStepState,
    modifier: Modifier = Modifier,
    colors: MederiColors = LocalMederiColors.current,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val icon = when (state) {
        WorkspaceTaskStepState.Pending -> FeatherIcons.Circle
        WorkspaceTaskStepState.Running -> FeatherIcons.Activity
        WorkspaceTaskStepState.Completed -> FeatherIcons.Check
        WorkspaceTaskStepState.Failed -> FeatherIcons.AlertCircle
        WorkspaceTaskStepState.Cancelled -> FeatherIcons.MinusCircle
    }
    val iconColor = when (state) {
        WorkspaceTaskStepState.Running -> colors.accentText
        WorkspaceTaskStepState.Failed -> colors.accentDanger
        else -> colors.textMuted
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = MederiSpacing.XS),
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = iconColor,
            modifier = Modifier.padding(top = MederiSpacing.Micro).size(WorkspaceStyle.IconSize))
        Column(
            modifier = Modifier.weight(1f).semantics { stateDescription = statusLabel },
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.Micro),
        ) {
            Text(
                text = text,
                style = WorkspaceStyle.Body,
                color = if (state == WorkspaceTaskStepState.Completed || state == WorkspaceTaskStepState.Cancelled) {
                    colors.textSecondary
                } else colors.textPrimary,
                textDecoration = if (state == WorkspaceTaskStepState.Completed) TextDecoration.LineThrough else TextDecoration.None,
            )
            if (statusLabel.isNotBlank()) Text(statusLabel, style = WorkspaceStyle.Caption, color = colors.textMuted)
        }
        actions()
    }
}
