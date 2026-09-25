package xyz.mederi.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * 4.5. 计划审批卡片 (PlanApprovalCard)
 *
 * 对标 Proceed 极简设计：
 * - 顶栏：Implementation Plan（点击在右侧扩展窗口打开完整文档）
 * - 正文：AI 生成的 1-2 句精炼摘要
 * - 底部：单只 Proceed 按钮，不批准直接在输入框继续对话
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
    val isApproved = request.status.equals("APPROVED", ignoreCase = true)

    DebugLog.debug("UI", "PlanApprovalCard: rendering planId=${request.id}, title='${request.title.take(30)}', isApproved=$isApproved")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. 卡片主体内容（Header + 摘要，整体区域均可点击以在右侧扩展窗口打开完整文档）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { onOpenInExtension() }
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (request.title.isNotBlank()) {
                            stringResource(Res.string.plan_approval_title, request.title)
                        } else {
                            stringResource(Res.string.plan_approval_title_default)
                        },
                        color = colors.textPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (request.id.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = request.id,
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
            }

            // AI 生成的 1-2 句精炼摘要
            val displayText = request.summary.ifBlank { request.title }
            if (displayText.isNotBlank()) {
                Text(
                    text = displayText,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        // 3. 底部操作：单个 Proceed 按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onApprove,
                enabled = !isApproved,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentPrimary,
                    disabledContainerColor = colors.buttonSecondary
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = if (isApproved) stringResource(Res.string.plan_approval_approved) else stringResource(Res.string.plan_approval_proceed),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isApproved) colors.textMuted else colors.onAccentPrimary
                    )
                    if (!isApproved) {
                        Text(
                            text = "⌘↵",
                            fontSize = 10.5.sp,
                            color = colors.onAccentPrimary.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}
