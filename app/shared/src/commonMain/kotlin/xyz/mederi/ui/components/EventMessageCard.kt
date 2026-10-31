package xyz.mederi.ui.components

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.WorkspaceStyle
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Check
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Info
import compose.icons.feathericons.Terminal
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.event_message_open_report_title
import mederi.app.shared.generated.resources.event_message_strip_completed
import mederi.app.shared.generated.resources.event_message_strip_completed_role
import mederi.app.shared.generated.resources.event_message_strip_failed
import mederi.app.shared.generated.resources.event_message_strip_failed_role
import mederi.app.shared.generated.resources.event_message_strip_stopped
import mederi.app.shared.generated.resources.event_message_strip_stopped_role
import mederi.app.shared.generated.resources.event_message_subagent_title
import mederi.app.shared.generated.resources.event_message_subagent_title_role
import mederi.app.shared.generated.resources.event_message_view_detail
import mederi.app.shared.generated.resources.event_message_bg_task_completed
import mederi.app.shared.generated.resources.event_message_bg_task_error
import mederi.app.shared.generated.resources.event_message_bg_task_stopped
import mederi.app.shared.generated.resources.event_message_bg_task_timer_fired
import mederi.app.shared.generated.resources.event_message_bg_task_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.ChatListItem

/**
 * 子代理/后台任务的轻量终态事件行。状态由图标与本地化文字共同表达，
 * 不用整圈边框强调历史事件；详情入口常显，整行支持键盘与触控打开报告。
 * 点击仅回传本地化标题，报告异步读取由窗口表现层负责。
 */
@Composable
fun EventMessageCard(
    item: ChatListItem.EventMessageCard,
    onOpenReport: ((title: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current

    // 终态判定的**唯一真理源**在 SubagentLabels.subagentTerminalKind：
    // 历史事故 = 这里只判 ERROR/FAILED 二值、其余一律走 else，导致 STOPPED（手动取消）
    // 被显示成「已完成」。STOPPED 必须单独命中自己的分支。
    val terminalKind = subagentTerminalKind(item.status)
    // 错误色语义：ERROR + OTHER（未知/非终态兜底，不得回落成「已完成」）
    val useErrorStyle = terminalKind == SubagentTerminalKind.ERROR || terminalKind == SubagentTerminalKind.OTHER
    // 中性色语义：STOPPED（被取消不是成功，不用成功绿）
    val useNeutralStyle = terminalKind == SubagentTerminalKind.STOPPED

    // 后台任务事件（background_task_*）与子代理事件（subagent_*）走不同文案/标题，
    // 但共享终态判定（terminalKind）和图标/颜色逻辑。
    val isBackgroundTask = item.eventType.startsWith("background_task_")

    // hasRole / roleLabel 先无条件计算（hasSpecificRole / roleLabelOf 是 @Composable 函数，
    // 避免在条件分支内调用 @Composable 的编译器问题），仅子代理路径使用。
    val hasRole = hasSpecificRole(item.role)
    val roleLabel = roleLabelOf(item.role)
    val displayTitle = if (isBackgroundTask) {
        stringResource(Res.string.event_message_bg_task_title)
    } else {
        if (hasRole) {
            stringResource(Res.string.event_message_subagent_title_role, roleLabel)
        } else {
            stringResource(Res.string.event_message_subagent_title)
        }
    }
    val openReportTitle = stringResource(Res.string.event_message_open_report_title, displayTitle)

    // 状态行 = 纯状态文案；后台任务（background_task_*）与子代理事件（subagent_*）分支不同文案，
    // 但共享终态判定（terminalKind）。摘要只在右侧阅读器里看
    val statusText = if (isBackgroundTask) {
        // 后台任务文案：timer 型 COMPLETED 用"定时器已触发"，其余按 terminalKind 走通用文案
        if (item.taskType == "timer" && terminalKind == SubagentTerminalKind.COMPLETED) {
            stringResource(Res.string.event_message_bg_task_timer_fired)
        } else {
            when (terminalKind) {
                SubagentTerminalKind.COMPLETED -> stringResource(Res.string.event_message_bg_task_completed)
                SubagentTerminalKind.ERROR, SubagentTerminalKind.OTHER ->
                    stringResource(Res.string.event_message_bg_task_error)
                SubagentTerminalKind.STOPPED -> stringResource(Res.string.event_message_bg_task_stopped)
            }
        }
    } else {
        when (terminalKind) {
            SubagentTerminalKind.COMPLETED -> if (hasRole) {
                stringResource(Res.string.event_message_strip_completed_role, roleLabel)
            } else {
                stringResource(Res.string.event_message_strip_completed)
            }
            SubagentTerminalKind.ERROR -> if (hasRole) {
                stringResource(Res.string.event_message_strip_failed_role, roleLabel)
            } else {
                stringResource(Res.string.event_message_strip_failed)
            }
            SubagentTerminalKind.STOPPED -> if (hasRole) {
                stringResource(Res.string.event_message_strip_stopped_role, roleLabel)
            } else {
                stringResource(Res.string.event_message_strip_stopped)
            }
            // 兜底按失败展示，绝不回落成「已完成」
            SubagentTerminalKind.OTHER -> if (hasRole) {
                stringResource(Res.string.event_message_strip_failed_role, roleLabel)
            } else {
                stringResource(Res.string.event_message_strip_failed)
            }
        }
    }

    val detailLabel = stringResource(Res.string.event_message_view_detail)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = if (hovered && onOpenReport != null) colors.surfaceHover else Color.Transparent,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "eventRowBackground",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(WorkspaceStyle.ObjectRadius))
            .background(background)
            .then(if (onOpenReport != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    role = Role.Button,
                    onClickLabel = detailLabel,
                ) { onOpenReport(openReportTitle) }
            } else Modifier)
            .heightIn(min = WorkspaceStyle.ControlHeight)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (isBackgroundTask) {
                if (item.taskType == "timer") FeatherIcons.Clock else FeatherIcons.Terminal
            } else {
                when {
                    useErrorStyle -> FeatherIcons.AlertCircle
                    useNeutralStyle -> FeatherIcons.Info
                    else -> FeatherIcons.Check
                }
            },
            contentDescription = null,
            tint = when {
                useErrorStyle -> colors.accentDanger
                useNeutralStyle -> colors.textSecondary
                else -> colors.accentSuccess
            },
            modifier = Modifier.size(WorkspaceStyle.IconSize),
        )
        Text(
            text = statusText,
            color = colors.textSecondary,
            style = WorkspaceStyle.Body,
            modifier = Modifier.weight(1f),
        )
        if (onOpenReport != null) {
            Text(
                text = detailLabel,
                color = colors.textSecondary,
                style = WorkspaceStyle.Caption,
            )
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(WorkspaceStyle.IconSize),
            )
        }
    }
}
