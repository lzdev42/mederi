package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.action_rollback
import mederi.app.shared.generated.resources.attachment_meta
import mederi.app.shared.generated.resources.attachment_reader
import mederi.app.shared.generated.resources.chat_copy_full_turn
import mederi.app.shared.generated.resources.chat_copy_last_message
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.copy_done
import mederi.app.shared.generated.resources.footer_completed
import mederi.app.shared.generated.resources.footer_reasoning
import mederi.app.shared.generated.resources.input_pasted_text_n
import mederi.app.shared.generated.resources.mode_auto_approve
import mederi.app.shared.generated.resources.mode_manual_approve
import mederi.app.shared.generated.resources.worktrace_collapse
import mederi.app.shared.generated.resources.worktrace_expand
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.AssistantFooterInfo
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.components.atoms.ExpandChevron
import xyz.mederi.ui.components.atoms.ExpandableContent
import xyz.mederi.ui.components.atoms.MederiGhostButton
import xyz.mederi.ui.components.atoms.MederiMinimalIconButton

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
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
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
                    tint = colors.accentSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = stringResource(Res.string.input_pasted_text_n, attachment.index),
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(Res.string.attachment_meta, attachment.lineCount, attachment.charCount),
                    color = colors.textMuted,
                    fontSize = 11.sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onOpenInExtension != null) {
                    Text(
                        text = stringResource(Res.string.attachment_reader),
                        color = colors.accentSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onOpenInExtension() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = stringResource(if (isExpanded) Res.string.worktrace_collapse else Res.string.worktrace_expand),
                    color = colors.accentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { isExpanded = !isExpanded }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
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
                    .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = attachment.text,
                        color = colors.onSurfaceCode,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
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

/**
 * 用户消息底栏：常驻时间戳、回退操作、复制操作。
 */

@Composable
fun UserMessageFooter(
    createdAt: Long,
    onRollback: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }

    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. 时间戳（时钟图标 + HH:mm）
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FeatherIcons.Clock,
                contentDescription = null,
                tint = colors.textMuted.copy(alpha = 0.7f),
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = xyz.mederi.formatMessageTime(createdAt),
                color = colors.textMuted.copy(alpha = 0.8f),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // 2. 退回并重新编辑按钮（回退一步 = 撤回本条及后续记录，内容粘贴回输入框，可切换模型/模式后再发）
        MederiMinimalIconButton(
            icon = FeatherIcons.CornerUpLeft,
            onClick = onRollback,
            contentDescription = stringResource(Res.string.action_rollback),
        )

        // 3. 复制按钮（copied 态以 Check 图标切换表达，颜色收敛为 Minimal 的 textSecondary 系）
        MederiMinimalIconButton(
            icon = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
            onClick = {
                onCopy()
                copied = true
            },
            contentDescription = stringResource(if (copied) Res.string.copy_done else Res.string.copy),
        )
    }
}

/**
 * assistant 消息底部 footer：模型名 · 审批/自主 · 推理档 · 消耗时长 · 回复结束时间，
 * 以及本轮回复的复制操作按钮（只复制最后一条回复 / 复制本轮完整内容）。
 */

@Composable
fun AssistantMessageFooter(
    footer: AssistantFooterInfo,
    lastMessageText: String = "",
    fullTurnText: String = "",
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val clipboardManager = LocalClipboardManager.current
    var copiedLast by remember { mutableStateOf(false) }
    var copiedFull by remember { mutableStateOf(false) }

    LaunchedEffect(copiedLast) {
        if (copiedLast) {
            delay(1500)
            copiedLast = false
        }
    }
    LaunchedEffect(copiedFull) {
        if (copiedFull) {
            delay(1500)
            copiedFull = false
        }
    }

    val segments = mutableListOf<String>()

    val autoApproveStr = stringResource(Res.string.mode_auto_approve)
    val manualApproveStr = stringResource(Res.string.mode_manual_approve)
    footer.modelName?.takeIf { it.isNotBlank() }?.let { segments.add(it) }
    footer.agentMode?.let {
        segments.add(
            when (it) {
                "AUTONOMOUS" -> autoApproveStr
                "APPROVAL" -> manualApproveStr
                else -> it
            }
        )
    }
    footer.thinkingLevel?.takeIf { it.isNotBlank() && it != "NONE" }?.let { segments.add(stringResource(Res.string.footer_reasoning, it)) }
    footer.durationMs?.let { ms ->
        if (ms > 0) segments.add(formatSeconds(ms))
    }
    footer.completedAtMs?.let { ms ->
        if (ms > 0) segments.add(stringResource(Res.string.footer_completed, xyz.mederi.formatMessageTime(ms)))
    }

    if (segments.isEmpty() && lastMessageText.isBlank() && fullTurnText.isBlank()) return

    Row(
        modifier = modifier.padding(start = 2.dp, end = 2.dp, top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：模型元数据
        Row(
            modifier = Modifier.weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (segments.isNotEmpty()) {
                Icon(
                    imageVector = FeatherIcons.Info,
                    contentDescription = null,
                    tint = colors.textMuted.copy(alpha = 0.6f),
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = segments.joinToString(" · "),
                    color = colors.textMuted.copy(alpha = 0.8f),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 右侧：复制按钮
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (lastMessageText.isNotBlank()) {
                val textLast = if (copiedLast) stringResource(Res.string.copy_done) else stringResource(Res.string.chat_copy_last_message)
                MederiGhostButton(
                    text = textLast,
                    icon = if (copiedLast) FeatherIcons.Check else FeatherIcons.Copy,
                    onClick = {
                        clipboardManager.setText(AnnotatedString(lastMessageText))
                        copiedLast = true
                    },
                )
            }

            if (fullTurnText.isNotBlank()) {
                val textFull = if (copiedFull) stringResource(Res.string.copy_done) else stringResource(Res.string.chat_copy_full_turn)
                MederiGhostButton(
                    text = textFull,
                    icon = if (copiedFull) FeatherIcons.Check else FeatherIcons.Copy,
                    onClick = {
                        clipboardManager.setText(AnnotatedString(fullTurnText))
                        copiedFull = true
                    },
                )
            }
        }
    }
}
