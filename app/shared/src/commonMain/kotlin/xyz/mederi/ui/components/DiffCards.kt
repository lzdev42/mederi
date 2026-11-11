package xyz.mederi.ui.components

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import compose.icons.FeatherIcons
import compose.icons.feathericons.File
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.diff_collapse
import mederi.app.shared.generated.resources.diff_expand_all
import mederi.app.shared.generated.resources.diff_files_changed
import mederi.app.shared.generated.resources.diff_review
import xyz.mederi.ui.components.atoms.ExpandableContent
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.TurnDiffSummaryUi
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.ChatLayout
import xyz.mederi.ui.components.atoms.WorkspaceActionButton

/** 文件名优先、完整路径可选择复制；计数始终独立于长路径的宽度。 */
@Composable
fun FileDiffRow(
    path: String,
    additions: Int,
    deletions: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalMederiColors.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val background by animateColorAsState(
        if (onClick != null && (hovered || focused)) colors.surfaceHover else Color.Transparent,
        LocalWorkspaceMotion.current.feedbackSpec(), label = "fileRowBackground",
    )
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = WorkspaceStyle.ControlHeight)
            .clip(RoundedCornerShape(MederiRadius.Control)).background(background)
            .then(if (onClick != null) Modifier.hoverable(source).clickable(
                interactionSource = source, indication = null, role = Role.Button, onClick = onClick,
            ) else Modifier)
            .padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
    ) {
        Icon(FeatherIcons.File, null, tint = colors.textSecondary, modifier = Modifier.size(WorkspaceStyle.IconSize))
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Column {
                Text(path.replace('\\', '/').substringAfterLast('/'), color = colors.textPrimary, style = WorkspaceStyle.Body,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val hasDirectory = path.contains('/') || path.contains('\\')
                if (hasDirectory) Text(path, color = colors.textMuted,
                    style = WorkspaceStyle.Caption.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
            if (additions > 0 || (additions == 0 && deletions == 0)) {
                Text("+$additions", color = colors.successText, style = WorkspaceStyle.Caption)
            }
            if (deletions > 0) Text("-$deletions", color = colors.dangerText, style = WorkspaceStyle.Caption)
        }
    }
}

/** 超过此数量默认折叠，防止大轮次变更撑爆对话流 */
private const val DIFF_COLLAPSE_THRESHOLD = 5

/** 对话中的变更事实与Review入口；操作由调用方路由，不在卡片持有文件状态。 */
@Composable
fun TurnDiffSummaryCard(
    summary: TurnDiffSummaryUi,
    onReviewClick: () -> Unit,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (summary.files.isEmpty()) return
    val colors = LocalMederiColors.current
    val isLarge = summary.files.size > DIFF_COLLAPSE_THRESHOLD
    var expanded by remember(summary.files.size) { mutableStateOf(!isLarge) }

    Column(
        modifier = modifier.widthIn(max = ChatLayout.actionCardMaxWidth).padding(vertical = MederiSpacing.XS),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        ) {
            Text(
                stringResource(Res.string.diff_files_changed, summary.files.size, summary.totalAdditions, summary.totalDeletions),
                color = colors.textPrimary,
                style = WorkspaceStyle.Title,
                modifier = Modifier.weight(1f),
            )
            if (isLarge) {
                WorkspaceActionButton(
                    text = stringResource(if (expanded) Res.string.diff_collapse else Res.string.diff_expand_all, summary.files.size),
                    onClick = { expanded = !expanded },
                )
            }
            WorkspaceActionButton(text = stringResource(Res.string.diff_review), onClick = onReviewClick)
        }
        ExpandableContent(expanded = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
                summary.files.forEach { file ->
                    FileDiffRow(file.path, file.additions, file.deletions, onClick = { onFileClick(file.path) })
                }
            }
        }
    }
}
