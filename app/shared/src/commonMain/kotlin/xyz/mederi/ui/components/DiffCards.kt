package xyz.mederi.ui.components

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.File
import compose.icons.feathericons.FilePlus
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.diff_files_changed_many
import mederi.app.shared.generated.resources.diff_files_changed_one
import mederi.app.shared.generated.resources.diff_review
import xyz.mederi.ui.components.atoms.ExpandChevron
import xyz.mederi.ui.components.atoms.ExpandableContent
import xyz.mederi.ui.components.atoms.MederiCard
import xyz.mederi.ui.components.atoms.expansionSemantics
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.TurnDiffSummaryUi
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.ChatLayout

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

/** 对话中的变更事实与 Review 入口；默认折叠，点击摘要头展开/收起文件列表，操作由调用方路由，不在卡片持有文件状态。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TurnDiffSummaryCard(
    summary: TurnDiffSummaryUi,
    onReviewClick: () -> Unit,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (summary.files.isEmpty()) return
    val colors = LocalMederiColors.current
    var expanded by remember(summary) { mutableStateOf(false) }

    val headerInteraction = remember { MutableInteractionSource() }
    val headerHovered by headerInteraction.collectIsHoveredAsState()
    val headerFocused by headerInteraction.collectIsFocusedAsState()
    val headerBg by animateColorAsState(
        targetValue = if (headerHovered || headerFocused) colors.surfaceHover else Color.Transparent,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "turnDiffHeaderBackground",
    )
    val chevronTint by animateColorAsState(
        targetValue = if (headerHovered || headerFocused || expanded) colors.textSecondary else colors.textMuted,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "turnDiffChevronTint",
    )

    val filesLabel = if (summary.files.size == 1) {
        stringResource(Res.string.diff_files_changed_one, summary.files.size)
    } else {
        stringResource(Res.string.diff_files_changed_many, summary.files.size)
    }

    MederiCard(
        modifier = modifier
            .widthIn(max = ChatLayout.actionCardMaxWidth)
            .padding(vertical = MederiSpacing.XS),
        padding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MederiSpacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = WorkspaceStyle.ControlHeight)
                    .clip(RoundedCornerShape(MederiRadius.Control))
                    .background(headerBg)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .hoverable(headerInteraction)
                    .expansionSemantics(expanded) { expanded = it }
                    .clickable(
                        interactionSource = headerInteraction,
                        indication = null,
                        role = Role.Button,
                        onClick = { expanded = !expanded },
                    )
                    .padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.XS),
                contentAlignment = Alignment.CenterStart,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
                    verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = filesLabel,
                        color = colors.textPrimary,
                        style = WorkspaceStyle.Title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
                    ) {
                        Text(
                            text = "+${summary.totalAdditions}",
                            color = colors.successText,
                            style = WorkspaceStyle.Body.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                        )
                        Text(
                            text = "-${summary.totalDeletions}",
                            color = colors.dangerText,
                            style = WorkspaceStyle.Body.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(MederiSpacing.Micro))
                        ExpandChevron(
                            expanded = expanded,
                            tint = chevronTint,
                            size = WorkspaceStyle.IconSize,
                        )
                    }
                }
            }

            DiffReviewButton(
                text = stringResource(Res.string.diff_review),
                onClick = onReviewClick,
            )
        }

        ExpandableContent(expanded = expanded) {
            HorizontalDivider(thickness = 1.dp, color = colors.divider.copy(alpha = 0.6f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MederiSpacing.Tight),
                verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
            ) {
                summary.files.forEach { file ->
                    FileDiffRow(
                        path = file.path,
                        additions = file.additions,
                        deletions = file.deletions,
                        onClick = { onFileClick(file.path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffReviewButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val active = hovered || focused
    val background by animateColorAsState(
        targetValue = if (active) colors.surfaceHover else colors.surfaceCard,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "diffReviewBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) colors.borderStrong else colors.surfaceCardBorder,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "diffReviewBorder",
    )
    val iconColor by animateColorAsState(
        targetValue = if (active) colors.textPrimary else colors.textSecondary,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "diffReviewIcon",
    )
    val shape = RoundedCornerShape(MederiRadius.Control)
    Row(
        modifier = modifier
            .heightIn(min = WorkspaceStyle.ControlHeight)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = MederiSpacing.Compact, vertical = MederiSpacing.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.Tight),
    ) {
        Icon(
            imageVector = FeatherIcons.FilePlus,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            color = colors.textPrimary,
            style = WorkspaceStyle.Caption.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}
