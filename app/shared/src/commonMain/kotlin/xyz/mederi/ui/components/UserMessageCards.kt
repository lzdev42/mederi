package xyz.mederi.ui.components

import xyz.mederi.theme.LocalWorkspaceMotion
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

import androidx.compose.animation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.delay
import xyz.mederi.formatMessageTime
import xyz.mederi.util.rememberClipboardCopy
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.action_rollback
import mederi.app.shared.generated.resources.attachment_meta
import mederi.app.shared.generated.resources.attachment_reader
import mederi.app.shared.generated.resources.chat_copy_full_turn
import mederi.app.shared.generated.resources.chat_copy_last_message
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.copy_done
import mederi.app.shared.generated.resources.message_details
import mederi.app.shared.generated.resources.footer_completed
import mederi.app.shared.generated.resources.footer_reasoning
import mederi.app.shared.generated.resources.input_pasted_text_n
import mederi.app.shared.generated.resources.mode_auto_approve
import mederi.app.shared.generated.resources.mode_manual_approve
import mederi.app.shared.generated.resources.worktrace_collapse
import mederi.app.shared.generated.resources.worktrace_expand
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.AssistantFooterInfo
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.components.atoms.ExpandChevron
import xyz.mederi.ui.components.atoms.ExpandableContent

/**
 * 用户消息中的大段文本折叠卡片 (UserPastedTextCard)
 */
@Composable
fun UserPastedTextCard(
    attachment: xyz.mederi.core.contract.models.PastedTextAttachment,
    onOpenInExtension: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceWorkspace)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clickable { isExpanded = !isExpanded }
            ) {
                ExpandChevron(expanded = isExpanded, tint = colors.textMuted, size = 13.dp)
                Icon(
                    imageVector = FeatherIcons.File,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(WorkspaceStyle.IconSize)
                )
                Text(
                    text = stringResource(Res.string.input_pasted_text_n, attachment.index),
                    color = colors.textPrimary,
                    style = WorkspaceStyle.Title
                )
                Text(
                    text = stringResource(Res.string.attachment_meta, attachment.lineCount, attachment.charCount),
                    color = colors.textMuted,
                    style = WorkspaceStyle.Caption
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onOpenInExtension != null) {
                    Text(
                        text = stringResource(Res.string.attachment_reader),
                        color = colors.textSecondary,
                        style = WorkspaceStyle.Caption,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onOpenInExtension() }
                            .heightIn(min = WorkspaceStyle.ControlHeight)
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                    )
                }
                Text(
                    text = stringResource(if (isExpanded) Res.string.worktrace_collapse else Res.string.worktrace_expand),
                    color = colors.textSecondary,
                    style = WorkspaceStyle.Caption,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { isExpanded = !isExpanded }
                        .heightIn(min = WorkspaceStyle.ControlHeight)
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                )
            }
        }

        ExpandableContent(expanded = isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceCode)
                    .padding(8.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = attachment.text,
                        color = colors.onSurfaceCode,
                        style = WorkspaceStyle.Caption,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .containScroll()
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    )
                }
            }
        }
    }
}

/** 用户消息操作常显；复制和回滚能力不随 hover 隐藏。 */
@Composable
fun UserMessageFooter(
    createdAt: Long,
    onRollback: (() -> Unit)?,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(WorkspaceStyle.CopyFeedbackMillis)
            copied = false
        }
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatMessageTime(createdAt),
            color = colors.textMuted,
            style = WorkspaceStyle.Caption,
            modifier = Modifier.padding(end = 4.dp),
        )
        if (onRollback != null) {
            MessageActionButton(
                icon = FeatherIcons.CornerUpLeft,
                description = stringResource(Res.string.action_rollback),
                onClick = onRollback,
            )
        }
        MessageActionButton(
            icon = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
            description = stringResource(if (copied) Res.string.copy_done else Res.string.copy),
            onClick = { onCopy(); copied = true },
        )
    }
}

/**
 * 回复操作常显，模型/审批模式/推理/耗时/完成时间按需展开。
 * 展开只改变本地呈现状态，不修改消息与交付事实。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantMessageFooter(
    footer: AssistantFooterInfo,
    lastMessageText: String = "",
    fullTurnText: String = "",
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val copyToClipboard = rememberClipboardCopy()
    var copiedLast by remember { mutableStateOf(false) }
    var copiedFull by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(copiedLast) {
        if (copiedLast) { delay(WorkspaceStyle.CopyFeedbackMillis); copiedLast = false }
    }
    LaunchedEffect(copiedFull) {
        if (copiedFull) { delay(WorkspaceStyle.CopyFeedbackMillis); copiedFull = false }
    }
    val segments = mutableListOf<String>()
    val autoApproveStr = stringResource(Res.string.mode_auto_approve)
    val manualApproveStr = stringResource(Res.string.mode_manual_approve)
    footer.modelName?.takeIf { it.isNotBlank() }?.let(segments::add)
    footer.agentMode?.let {
        segments.add(when (it) {
            "AUTONOMOUS" -> autoApproveStr
            "APPROVAL" -> manualApproveStr
            else -> it
        })
    }
    footer.thinkingLevel?.takeIf { it.isNotBlank() && it != "NONE" }?.let {
        segments.add(stringResource(Res.string.footer_reasoning, it))
    }
    footer.durationMs?.takeIf { it > 0 }?.let { segments.add(formatSeconds(it)) }
    footer.completedAtMs?.takeIf { it > 0 }?.let {
        segments.add(stringResource(Res.string.footer_completed, formatMessageTime(it)))
    }
    if (segments.isEmpty() && lastMessageText.isBlank() && fullTurnText.isBlank()) return
    Column(modifier = modifier.padding(top = 4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (lastMessageText.isNotBlank()) {
                MessageActionButton(
                    icon = if (copiedLast) FeatherIcons.Check else FeatherIcons.Copy,
                    label = stringResource(if (copiedLast) Res.string.copy_done else Res.string.copy),
                    description = stringResource(Res.string.chat_copy_last_message),
                    onClick = { copyToClipboard(lastMessageText); copiedLast = true },
                )
            }
            if (fullTurnText.isNotBlank()) {
                MessageActionButton(
                    icon = if (copiedFull) FeatherIcons.Check else FeatherIcons.Layers,
                    label = stringResource(if (copiedFull) Res.string.copy_done else Res.string.chat_copy_full_turn),
                    description = stringResource(Res.string.chat_copy_full_turn),
                    onClick = { copyToClipboard(fullTurnText); copiedFull = true },
                )
            }
            if (segments.isNotEmpty()) {
                MessageActionButton(
                    icon = FeatherIcons.Info,
                    label = stringResource(Res.string.message_details),
                    description = stringResource(Res.string.message_details),
                    active = detailsExpanded,
                    onClick = { detailsExpanded = !detailsExpanded },
                )
            }
        }
        AnimatedVisibility(
            visible = detailsExpanded,
            enter = if (LocalWorkspaceMotion.current.reducedMotion) EnterTransition.None else expandVertically(LocalWorkspaceMotion.current.disclosureSpec()) + fadeIn(LocalWorkspaceMotion.current.disclosureSpec()),
            exit = if (LocalWorkspaceMotion.current.reducedMotion) ExitTransition.None else shrinkVertically(LocalWorkspaceMotion.current.disclosureSpec()) + fadeOut(LocalWorkspaceMotion.current.disclosureSpec()),
        ) {
            SelectionContainer {
                Text(
                    text = segments.joinToString(" · "),
                    color = colors.textSecondary,
                    style = WorkspaceStyle.Caption,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/** 消息区域的轻量操作，只接收展示值与回调，不持有消息业务状态。 */
@Composable
private fun MessageActionButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    label: String? = null,
    active: Boolean = false,
) {
    val colors = LocalMederiColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = if (hovered || active) colors.surfaceHover else Color.Transparent,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "messageActionBackground",
    )
    Row(
        modifier = Modifier
            .heightIn(min = WorkspaceStyle.IconTarget)
            .widthIn(min = WorkspaceStyle.IconTarget)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(interactionSource = interactionSource, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) colors.accentText else colors.textSecondary,
            modifier = Modifier.size(WorkspaceStyle.IconSize),
        )
        if (label != null) {
            Text(
                text = label,
                color = if (active) colors.accentText else colors.textSecondary,
                style = WorkspaceStyle.Caption,
            )
        }
    }
}
