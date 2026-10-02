package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.plan_approval_approved
import mederi.app.shared.generated.resources.plan_approval_proceed
import mederi.app.shared.generated.resources.plan_approval_title
import mederi.app.shared.generated.resources.plan_approval_title_default
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.PlanApprovalRequest
import xyz.mederi.ui.DebugLog
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.components.atoms.MederiPlanIdTag
import xyz.mederi.ui.components.atoms.MederiPrimaryDecisionButton

/**
 * 4.5. 计划审批块 (PlanApprovalCard，02-components §2.5 plan-approval-block：待办层卡片)
 *
 * 属于「需要用户操作」的待办层，与 QuestionCard / ErrorBoard 同规格：
 * accentBg 淡底 + accentBorder 描边 + 8dp 圆角 + 12dp 内边距（此前为去壳 + 2dp 左 rail，
 * 有壳后不再叠加左 rail，避免框套框）。
 * 内容：
 * - 标题行：FileText 14dp accentPrimary + Implementation Plan（整卡 hover → accentText，
 *   整卡点击在右侧扩展窗口打开完整文档）+ MederiPlanIdTag 徽标；
 * - 正文：AI 生成的 1-2 句精炼摘要（12sp / lh 1.55 textSecondary）；
 * - 底部：单只 Proceed 按钮（soft iris primary-decision，不批准直接在输入框继续对话）
 *
 * @param request 计划审批请求数据
 * @param onApprove 批准并开始执行回调
 * @param onOpenInExtension 在右侧扩展窗口打开完整 Markdown 计划（整卡热区）
 */
@Composable
fun PlanApprovalCard(
    request: PlanApprovalRequest?,
    onApprove: () -> Unit,
    onOpenInExtension: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (request == null) return
    val colors = LocalMederiColors.current
    val isPending = request.status.equals("PENDING", ignoreCase = true) ||
        request.status.equals("PENDING_APPROVAL", ignoreCase = true)

    DebugLog.debug("UI", "PlanApprovalCard: rendering planId=${request.id}, title='${request.title.take(30)}', isPending=$isPending, status=${request.status}")

    // 待办层卡片壳：8dp 圆角（clip / border 共用同一 shape，避免重复字面量）
    val shellShape = RoundedCornerShape(8.dp)

    // 整卡可点开计划文档：interactionSource 挂在整卡上，hover 同时驱动标题转 accentText
    val cardSource = remember { MutableInteractionSource() }
    val cardHovered by cardSource.collectIsHoveredAsState()

    // accentBg 淡底 + accentBorder 描边 + 12dp 内边距，宽度受限 max 560（无左 rail，避免框套框）
    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .clip(shellShape)
            .background(colors.accentBg)
            .border(1.dp, colors.accentBorder, shellShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = cardSource) { onOpenInExtension() }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. 标题行（点击热区已提到整卡，此处只保留排版；hover 由整卡 interactionSource 驱动）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FeatherIcons.FileText,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (request.title.isNotBlank()) {
                        stringResource(Res.string.plan_approval_title, request.title)
                    } else {
                        stringResource(Res.string.plan_approval_title_default)
                    },
                    color = if (cardHovered) colors.accentText else colors.textPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (request.id.isNotBlank()) {
                MederiPlanIdTag(request.id)
            }
        }

        // 2. AI 生成的 1-2 句精炼摘要
        val displayText = request.summary.ifBlank { request.title }
        if (displayText.isNotBlank()) {
            Text(
                text = displayText,
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 18.6.sp
            )
        }

        // 3. 底部操作：单个 Proceed 按钮（仅待审批态启用，已批准/执行中/已完成均保持禁用）
        MederiPrimaryDecisionButton(
            text = if (isPending) {
                stringResource(Res.string.plan_approval_proceed) + "  ⌘↵"
            } else {
                stringResource(Res.string.plan_approval_approved)
            },
            onClick = onApprove,
            enabled = isPending,
        )
    }
}