package xyz.mederi.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * 4.5. 计划审批块 (PlanApprovalCard，02-components §2.5 plan-approval-block：去壳 + 2dp 左 rail)
 *
 * 对标 Proceed 极简设计：
 * - 标题行：FileText 14dp accentPrimary + Implementation Plan（hover → accentText，
 *   点击在右侧扩展窗口打开完整文档）+ MederiPlanIdTag 徽标；
 * - 正文：AI 生成的 1-2 句精炼摘要（12sp / lh 1.55 textSecondary）；
 * - 底部：单只 Proceed 按钮（soft iris primary-decision，不批准直接在输入框继续对话）
 *
 * @param request 计划审批请求数据
 * @param onApprove 批准并开始执行回调
 * @param onOpenInExtension 在右侧扩展窗口打开完整 Markdown 计划
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

    // 去壳：无卡片底/描边，仅 2dp 左 rail（divider hairline）+ 12/2/2 内边距，宽度受限 max 560
    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .drawBehind {
                val rail = 2.dp.toPx()
                drawRect(
                    color = colors.divider,
                    topLeft = Offset(0f, 0f),
                    size = Size(rail, size.height)
                )
            }
            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. 标题行（点击在右侧扩展窗口打开完整文档，hover 标题转 accentText）
        val titleSource = remember { MutableInteractionSource() }
        val titleHovered by titleSource.collectIsHoveredAsState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = titleSource, onClick = { onOpenInExtension() })
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
                    color = if (titleHovered) colors.accentText else colors.textPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (request.id.isNotBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
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