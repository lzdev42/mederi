package xyz.mederi.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import xyz.emuci.inkcompose.MarkdownView
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.components.atoms.MederiMinimalIconButton

/**
 * 通用事件消息卡片 (EventMessageCard)
 *
 * 渲染系统主动上报事件（如子代理执行结束/报错等）：
 * - 顶部：状态图标、角色与状态徽章、在新窗口打开操作
 * - 中部：关联子任务与落盘报告路径（若有）
 * - 汇报正文：支持就地展开/折叠，直接通过 inkcompose (MarkdownView) 渲染完整的 Markdown 汇报。
 */
@Composable
fun EventMessageCard(
    item: ChatListItem.EventMessageCard,
    onOpenReport: ((title: String, content: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    var isExpanded by remember { mutableStateOf(false) }

    val isCompleted = item.status.equals("COMPLETED", ignoreCase = true)
    val isError = item.status.equals("ERROR", ignoreCase = true) || item.status.equals("FAILED", ignoreCase = true)

    val statusColor = when {
        isCompleted -> colors.accentSuccess
        isError -> colors.statusError
        else -> colors.textMuted
    }

    val statusIcon = when {
        isCompleted -> FeatherIcons.CheckCircle
        isError -> FeatherIcons.AlertTriangle
        else -> FeatherIcons.Info
    }

    // 尝试读取完整报告正文：若有落盘路径且能读出内容则使用文件，否则使用 summary
    val reportContent = remember(item.reportPath, item.summary) {
        val path = item.reportPath
        val fileText = if (!path.isNullOrBlank()) {
            runCatching { java.io.File(path).takeIf { it.exists() }?.readText() }.getOrNull()
        } else null
        if (!fileText.isNullOrBlank()) fileText else item.summary
    }

    val displayTitle = "${item.role.lowercase().replaceFirstChar { it.uppercase() }} Subagent"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. 顶栏：图标 + 角色标题 + 状态 Badge + 操作区
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = item.status,
                    tint = statusColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = colors.textPrimary
                )
                // 状态 Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusColor.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.status.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = statusColor
                    )
                }
            }

            // 右侧操作
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (onOpenReport != null && reportContent.isNotBlank()) {
                    MederiMinimalIconButton(
                        icon = FeatherIcons.Maximize2,
                        onClick = { onOpenReport("$displayTitle Report", reportContent) },
                        contentDescription = "Open in side panel",
                    )
                }
                // 折叠/展开按钮
                MederiMinimalIconButton(
                    icon = if (isExpanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    onClick = { isExpanded = !isExpanded },
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                )
            }
        }

        // 2. 元数据行（子任务与报告路径）
        if (!item.subtaskInfo.isNullOrBlank() || !item.reportPath.isNullOrBlank()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (!item.subtaskInfo.isNullOrBlank()) {
                    Text(
                        text = item.subtaskInfo,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = colors.textSecondary
                    )
                }
                if (!item.reportPath.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceWorkspace)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.FileText,
                            contentDescription = "Report Path",
                            tint = colors.textMuted,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = item.reportPath,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 3. 摘要预览（未展开时展示简要单行）
        if (!isExpanded && item.summary.isNotBlank()) {
            Text(
                text = item.summary.lines().firstOrNull { it.isNotBlank() } ?: item.summary,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = true }
                    .padding(vertical = 2.dp)
            )
        }

        // 4. 就地展开的 Markdown 汇报正文 (使用 inkcompose 原生渲染)
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceWorkspace)
                        .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(6.dp))
                        .padding(12.dp)
                ) {
                    if (reportContent.isNotBlank()) {
                        MarkdownView(
                            content = reportContent,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = "(No report content available)",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = colors.textMuted
                        )
                    }
                }
            }
        }
    }
}
